"""Read-only, agentless Linux telemetry. Optional tools never turn missing data into zero.

Runs on whatever python3 the server has, so it stays within Python 3.6.
"""
import json
import os
import socket
import ssl
import subprocess
import time
import http.client
import base64
import sys

deadline = time.monotonic() + 12
service_hostname = base64.b64decode(sys.argv[1]).decode('utf-8') if len(sys.argv) > 1 else 'localhost'


def command(args, timeout=2):
    try:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                timeout=min(timeout, remaining), check=False, universal_newlines=True)
        successful = result.returncode == 0
        if args[0] == 'smartctl':
            # Bits 3..7 report actual drive faults: their JSON must still reach monitoring.
            successful = result.returncode >= 0 and result.returncode & 7 == 0
        return result.stdout if successful else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def read(path):
    with open(path, encoding='ascii') as stream:
        return stream.read()


metrics, devices = {}, []


def device(kind, name, values):
    values = {key: value for key, value in values.items()
              if isinstance(value, (int, float)) and value >= 0}
    if values:
        devices.append(dict(kind=kind, name=name, metrics=values))


def aggregate(kind, code, reducer=max):
    values = [d['metrics'][code] for d in devices if d['kind'] == kind and code in d['metrics']]
    if values:
        metrics[code] = reducer(values)


def counters():
    cpu = [int(v) for v in read('/proc/stat').splitlines()[0].split()[1:9]]
    network = {}
    for line in read('/proc/net/dev').splitlines()[2:]:
        name, values = line.split(':', 1)
        if name.strip() != 'lo':
            network[name.strip()] = [int(v) for v in values.split()]
    disks = {}
    for line in read('/proc/diskstats').splitlines():
        values = line.split()
        name = values[2]
        # Whole physical/virtual block devices only: exclude partitions and double counting dm/loop.
        if os.path.exists('/sys/block/' + name + '/device') or name.startswith(('vd', 'xvd', 'nvme')):
            if os.path.exists('/sys/block/' + name):
                disks[name] = [int(v) for v in values[3:]]
    return cpu, network, disks


try:
    first_time = time.monotonic()
    first = counters()
    time.sleep(1)
    second = counters()
    elapsed = time.monotonic() - first_time
    total = sum(second[0]) - sum(first[0])
    wait = second[0][4] - first[0][4]
    if total > 0 and 0 <= wait <= total:
        metrics['CPU_IOWAIT_PERCENT'] = wait * 100 / total
    for name, end in second[1].items():
        start = first[1].get(name)
        if start is None or any(b < a for a, b in zip(start, end)):
            continue
        device('network', name, {
            'NETWORK_RECEIVE_BYTES_PER_SECOND': (end[0] - start[0]) / elapsed,
            'NETWORK_TRANSMIT_BYTES_PER_SECOND': (end[8] - start[8]) / elapsed,
            'NETWORK_ERRORS_PER_SECOND': (end[2] + end[10] - start[2] - start[10]) / elapsed,
            'NETWORK_DROPS_PER_SECOND': (end[3] + end[11] - start[3] - start[11]) / elapsed})
    for name, end in second[2].items():
        start = first[2].get(name)
        if start is None or len(end) < 11 or any(b < a for index, (a, b) in enumerate(zip(start, end)) if index != 8):
            continue
        operations = end[0] + end[4] - start[0] - start[4]
        values = {
            'DISK_READ_BYTES_PER_SECOND': (end[2] - start[2]) * 512 / elapsed,
            'DISK_WRITE_BYTES_PER_SECOND': (end[6] - start[6]) * 512 / elapsed,
            'DISK_BUSY_PERCENT': min(100, (end[9] - start[9]) / (elapsed * 10))}
        if operations > 0:
            values['DISK_LATENCY_MILLISECONDS'] = (end[3] + end[7] - start[3] - start[7]) / operations
        device('disk', name, values)
except (OSError, ValueError, IndexError):
    pass

try:
    info = {line.split(':', 1)[0]: int(line.split()[1]) for line in read('/proc/meminfo').splitlines()}
    total = info['SwapTotal']
    if total > 0 and 0 <= info['SwapFree'] <= total:
        used = total - info['SwapFree']
        metrics.update(SWAP_USAGE_PERCENT=100 * used / total, SWAP_USED_BYTES=used * 1024)
    load = [float(v) for v in read('/proc/loadavg').split()[:3]]
    metrics.update(zip(('LOAD_AVERAGE_1', 'LOAD_AVERAGE_5', 'LOAD_AVERAGE_15'), load))
    cores = os.cpu_count()
    if cores:
        metrics['LOAD_PER_CORE'] = load[0] / cores
except (OSError, ValueError, KeyError):
    pass

try:
    mounts = read('/proc/self/mounts').splitlines()
    seen = set()
    for line in mounts:
        source, path, fs, options = line.split()[:4]
        if fs in ('proc', 'sysfs', 'devtmpfs', 'tmpfs', 'overlay', 'squashfs', 'cgroup', 'cgroup2',
                  'debugfs', 'tracefs', 'securityfs', 'pstore', 'mqueue', 'hugetlbfs', 'fusectl'):
            continue
        if 'ro' in options.split(','):
            continue
        for escaped, literal in (('\\040', ' '), ('\\011', '\t'), ('\\134', '\\')):
            path = path.replace(escaped, literal)
        try:
            # Do not block on network filesystems during inventory collection.
            if fs in ('nfs', 'nfs4', 'cifs', 'smb3', '9p') or fs.startswith('fuse'):
                continue
            stat = os.statvfs(path)
            identity = os.stat(path).st_dev
            if identity in seen or stat.f_blocks <= 0:
                continue
            seen.add(identity)
            used = stat.f_blocks - stat.f_bfree
            usable = used + stat.f_bavail
            values = {'DISK_FREE_BYTES': stat.f_bavail * stat.f_frsize}
            if usable > 0:
                values['DISK_USAGE_PERCENT'] = 100 * used / usable
            if stat.f_files > 0:
                values['INODE_USAGE_PERCENT'] = 100 * (stat.f_files - stat.f_ffree) / stat.f_files
            device('filesystem', path, values)
        except OSError:
            continue
except (OSError, ValueError):
    pass

# Probe only loopback web services. No user supplied URLs, redirects or remote targets.
for port, secure in ((80, False), (443, True)):
    values = {}
    started = time.monotonic()
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=1):
            values['SERVICE_AVAILABLE'] = 1
    except OSError:
        values['SERVICE_AVAILABLE'] = 0
    if values['SERVICE_AVAILABLE']:
        try:
            conn = (http.client.HTTPSConnection(service_hostname, port, timeout=1)
                    if secure else http.client.HTTPConnection(service_hostname, port, timeout=1))
            # Host/SNI identifies the SSH destination; the socket is always loopback.
            conn._create_connection = lambda *args, **kwargs: socket.create_connection(('127.0.0.1', port), timeout=1)
            try:
                conn.request('HEAD', '/')
                response = conn.getresponse()
                values['SERVICE_AVAILABLE'] = int(response.status < 500)
                values['SERVICE_RESPONSE_MILLISECONDS'] = (time.monotonic() - started) * 1000
            finally:
                conn.close()
        except (OSError, http.client.HTTPException):
            values['SERVICE_AVAILABLE'] = 0
        if secure:
            # Certificate expiry is a diagnostic, separate from the verified HTTPS health probe.
            try:
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                context.check_hostname = False
                context.verify_mode = ssl.CERT_NONE
                with socket.create_connection(('127.0.0.1', port), timeout=1) as sock:
                    with context.wrap_socket(sock, server_hostname=service_hostname) as tls:
                        pem = ssl.DER_cert_to_PEM_cert(tls.getpeercert(binary_form=True))
                result = subprocess.run(['openssl', 'x509', '-noout', '-enddate'], input=pem,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=1, check=False)
                if result.returncode == 0:
                    expiry = ssl.cert_time_to_seconds(result.stdout.strip().split('=', 1)[1])
                    values['TLS_DAYS_REMAINING'] = max(0, (expiry - time.time()) / 86400)
            except (OSError, ValueError, IndexError, subprocess.TimeoutExpired):
                pass
    device('service', ('https://' if secure else 'http://') + service_hostname + ':' + str(port) + ' (loopback)', values)

# Read existing SMART data only; never start self-tests or elevate privileges.
for name in sorted(os.listdir('/sys/block')):
    if name.startswith(('loop', 'ram', 'dm-', 'md')):
        continue
    raw = command(['smartctl', '-a', '-j', '/dev/' + name], timeout=1)
    if raw is None:
        continue
    try:
        data = json.loads(raw)
        values = {}
        if 'passed' in data.get('smart_status', {}):
            values['DISK_HEALTHY'] = int(data['smart_status']['passed'])
        if 'current' in data.get('temperature', {}):
            values['DISK_TEMPERATURE_CELSIUS'] = data['temperature']['current']
        nvme = data.get('nvme_smart_health_information_log', {})
        if 'percentage_used' in nvme:
            values['DISK_WEAR_PERCENT'] = min(100, nvme['percentage_used'])
        if 'media_errors' in nvme:
            values['DISK_MEDIA_ERRORS'] = nvme['media_errors']
        device('hardware', name, values)
    except (ValueError, KeyError, TypeError):
        continue

# Docker CLI batches all containers; never inspect or request stats once per container.
raw = command(['docker', 'stats', '--all', '--no-stream', '--no-trunc', '--format', '{{json .}}'], timeout=3)
container_values = {}
if raw:
    for line in raw.splitlines():
        try:
            item = json.loads(line)
            values = {}
            for field, code in (('CPUPerc', 'CPU_USAGE_PERCENT'), ('MemPerc', 'MEMORY_USAGE_PERCENT')):
                value = float(item[field].rstrip('%'))
                # Docker CPU can exceed 100 across multiple cores. Normalize to host capacity.
                if field == 'CPUPerc' and os.cpu_count():
                    value /= os.cpu_count()
                if 0 <= value <= 100:
                    values[code] = value
            container_values[item['ID']] = values
        except (ValueError, KeyError, TypeError):
            continue
ids = command(['docker', 'ps', '--all', '--no-trunc', '--format', '{{.ID}}'])
if ids:
    # Bounded command argv without shell interpolation; keep metadata if stats is unavailable.
    identifiers = ids.splitlines()
    for offset in range(0, len(identifiers), 100):
        raw = command(['docker', 'inspect', '--format',
                       '{{.Id}}\t{{.RestartCount}}\t{{if .State.Health}}{{.State.Health.Status}}{{end}}']
                      + identifiers[offset:offset + 100])
        if raw:
            for line in raw.splitlines():
                try:
                    identifier, restarts, health = line.split('\t')
                    values = container_values.setdefault(identifier, {})
                    values['CONTAINER_RESTART_COUNT'] = int(restarts)
                    if health in ('healthy', 'unhealthy', 'starting'):
                        values['CONTAINER_HEALTHY'] = int(health == 'healthy')
                except (ValueError, KeyError):
                    continue
for identifier, values in container_values.items():
    device('container', identifier, values)

for kind, code, reducer in (
    ('filesystem', 'DISK_USAGE_PERCENT', max), ('filesystem', 'DISK_FREE_BYTES', min),
    ('filesystem', 'INODE_USAGE_PERCENT', max), ('disk', 'DISK_READ_BYTES_PER_SECOND', sum),
    ('disk', 'DISK_WRITE_BYTES_PER_SECOND', sum), ('disk', 'DISK_BUSY_PERCENT', max),
    ('disk', 'DISK_LATENCY_MILLISECONDS', max), ('network', 'NETWORK_RECEIVE_BYTES_PER_SECOND', sum),
    ('network', 'NETWORK_TRANSMIT_BYTES_PER_SECOND', sum), ('network', 'NETWORK_ERRORS_PER_SECOND', sum),
    ('network', 'NETWORK_DROPS_PER_SECOND', sum), ('service', 'SERVICE_AVAILABLE', min),
    ('service', 'SERVICE_RESPONSE_MILLISECONDS', max), ('service', 'TLS_DAYS_REMAINING', min),
    ('hardware', 'DISK_HEALTHY', min), ('hardware', 'DISK_TEMPERATURE_CELSIUS', max),
    ('hardware', 'DISK_WEAR_PERCENT', max), ('hardware', 'DISK_MEDIA_ERRORS', sum)):
    aggregate(kind, code, reducer)

print('telemetry\t' + json.dumps(dict(metrics=metrics, devices=devices), separators=(',', ':')))
