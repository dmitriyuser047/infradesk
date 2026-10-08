package ru.bitec.app.ops
package integration.ssh

/** Read-only bounded socket/container observation. Arguments contain only public identities. */
private[ssh] object ProtocolSocketProbe {
  val program: String = """import json, re, subprocess, sys
def run(args):
    p = subprocess.run(args, capture_output=True, timeout=4, text=True)
    if p.returncode != 0 or len(p.stdout) > 32768 or len(p.stderr) > 32768:
        raise ValueError('observation')
    return p.stdout
def observe():
    port, transport, name, directory, image = sys.argv[1:]
    if not port.isdigit() or not 1 <= int(port) <= 65535 or transport not in ('tcp', 'udp'):
        return 'OBSERVATION_UNKNOWN'
    output = run(['ss', '-H', '-lntp' if transport == 'tcp' else '-lnup', 'sport', '=', ':' + port])
    lines = [s for s in output.splitlines() if s.strip()]
    if not lines:
        return 'FREE'
    if len(lines) > 64 or any(len(s.split()) < 5 or s.split()[0] != ('LISTEN' if transport == 'tcp' else 'UNCONN') for s in lines):
        return 'OBSERVATION_UNKNOWN'
    if not name:
        return 'FOREIGN_LISTENER'
    fmt = '{"files":{{json (index .Config.Labels "com.docker.compose.project.config_files")}},"image":{{json .Config.Image}},"network":{{json .HostConfig.NetworkMode}},"running":{{json .State.Running}}}'
    c = json.loads(run(['docker', 'inspect', '--format', fmt, name]))
    if not isinstance(c, dict) or set(c) != {'files', 'image', 'network', 'running'}:
        return 'OBSERVATION_UNKNOWN'
    if (c['files'] != directory + '/compose.yml' or c['image'] != image
        or c['network'] != 'host' or c['running'] is not True):
        return 'OBSERVATION_UNKNOWN'
    rows = run(['docker', 'top', name, '-eo', 'pid,comm']).splitlines()
    if not rows or rows[0].split() != ['PID', 'COMMAND'] and rows[0].split() != ['PID', 'COMM']:
        return 'OBSERVATION_UNKNOWN'
    pids = set()
    for row in rows[1:]:
        fields = row.split()
        if len(fields) != 2 or not fields[0].isdigit() or int(fields[0]) <= 0:
            return 'OBSERVATION_UNKNOWN'
        if fields[1] == 'xray':
            pids.add(fields[0])
    if not pids or len(pids) > 16:
        return 'OBSERVATION_UNKNOWN'
    for line in lines:
        owners = re.findall(r'pid=([0-9]+),', line)
        if not owners:
            return 'OBSERVATION_UNKNOWN'
        if any(pid not in pids for pid in owners):
            return 'FOREIGN_LISTENER'
    return 'OWNED_EXPECTED'
try:
    print(observe())
except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
    print('OBSERVATION_UNKNOWN')
"""
}
