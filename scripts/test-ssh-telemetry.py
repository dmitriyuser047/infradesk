"""Deterministic Linux collector contracts; run with Python's standard library."""
import contextlib
import io
import json
import pathlib
import types
import unittest
from unittest.mock import patch

PROGRAM = pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/ssh/telemetry.py'


class TelemetryTest(unittest.TestCase):
    def collect(self, reset=False):
        program = PROGRAM.read_text()
        clock = [100.0]
        reads = {}
        files = {
            '/proc/meminfo': 'SwapTotal: 1000 kB\nSwapFree: 250 kB\n',
            '/proc/loadavg': '8 4 2 1/10 100',
            '/proc/self/mounts': '/dev/sda / ext4 rw 0 0\n/dev/sda /bind ext4 rw 0 0\n'
                                 'tmpfs /run tmpfs rw 0 0\n/dev/sdb /ro ext4 ro 0 0\n'
                                 'remote /remote nfs rw 0 0\n',
        }

        def read(path, *args, **kwargs):
            reads[path] = reads.get(path, 0) + 1
            second = reads[path] > 1
            if path == '/proc/stat':
                return io.StringIO('cpu 100 0 0 100 10 0 0 0\n' if not second else 'cpu 130 0 0 160 20 0 0 0\n')
            if path == '/proc/net/dev':
                numbers = [1000, 0, 2, 1, 0, 0, 0, 0, 2000, 0, 3, 2, 0, 0, 0, 0]
                if second:
                    numbers = [v + 10 for v in numbers]
                    if reset:
                        numbers[0] = 0
                return io.StringIO('header\nheader\neth0: ' + ' '.join(map(str, numbers)))
            if path == '/proc/diskstats':
                # In-progress I/O decreases normally; it is not a reset.
                counters = '10 0 100 20 10 0 200 30 5 40 40' if not second else '12 0 104 24 12 0 208 38 0 50 50'
                return io.StringIO('8 0 sda ' + counters + '\n8 1 sda1 ' + counters)
            if path in files:
                return io.StringIO(files[path])
            raise FileNotFoundError(path)

        def command(args, **kwargs):
            if args[0] == 'smartctl':
                return types.SimpleNamespace(returncode=8, stdout=json.dumps({
                    'smart_status': {'passed': False}, 'temperature': {'current': 42},
                    'nvme_smart_health_information_log': {'percentage_used': 23, 'media_errors': 4}}))
            raise FileNotFoundError(args[0])

        output = io.StringIO()
        stat = types.SimpleNamespace(f_blocks=100, f_bfree=20, f_bavail=10, f_frsize=4096,
                                     f_files=1000, f_ffree=50)
        with patch('builtins.open', read), patch('os.listdir', return_value=['sda']), \
             patch('os.path.exists', side_effect=lambda path: path in ('/sys/block/sda', '/sys/block/sda/device')), \
             patch('os.statvfs', return_value=stat, create=True), patch('os.stat', return_value=types.SimpleNamespace(st_dev=1)), \
             patch('os.cpu_count', return_value=4), patch('time.monotonic', side_effect=lambda: clock[0]), \
             patch('time.sleep', side_effect=lambda duration: clock.__setitem__(0, clock[0] + duration)), \
             patch('subprocess.run', command), patch('socket.create_connection', side_effect=OSError), \
             patch('sys.argv', ['telemetry.py']), contextlib.redirect_stdout(output):
            exec(compile(program, str(PROGRAM), 'exec'), {})
        return json.loads(output.getvalue().split('\t', 1)[1])

    def test_capacity_inode_swap_and_load(self):
        result = self.collect()
        metrics = result['metrics']
        self.assertAlmostEqual(metrics['DISK_USAGE_PERCENT'], 100 * 80 / 90)
        self.assertEqual(metrics['DISK_FREE_BYTES'], 40960)
        self.assertEqual(metrics['INODE_USAGE_PERCENT'], 95)
        self.assertEqual(metrics['SWAP_USAGE_PERCENT'], 75)
        self.assertEqual(metrics['LOAD_PER_CORE'], 2)
        self.assertEqual([d['name'] for d in result['devices'] if d['kind'] == 'filesystem'], ['/'])

    def test_rates_latency_and_normal_inflight_decrease(self):
        metrics = self.collect()['metrics']
        self.assertEqual(metrics['DISK_READ_BYTES_PER_SECOND'], 2048)
        self.assertEqual(metrics['DISK_WRITE_BYTES_PER_SECOND'], 4096)
        self.assertEqual(metrics['DISK_LATENCY_MILLISECONDS'], 3)
        self.assertEqual(metrics['DISK_BUSY_PERCENT'], 1)
        self.assertEqual(metrics['NETWORK_RECEIVE_BYTES_PER_SECOND'], 10)
        self.assertEqual(metrics['NETWORK_ERRORS_PER_SECOND'], 20)
        self.assertEqual(metrics['CPU_IOWAIT_PERCENT'], 10)

    def test_reset_has_no_false_network_zero(self):
        self.assertNotIn('NETWORK_RECEIVE_BYTES_PER_SECOND', self.collect(reset=True)['metrics'])

    def test_smart_failure_exit_status_is_a_reading(self):
        metrics = self.collect()['metrics']
        self.assertEqual(metrics['DISK_HEALTHY'], 0)
        self.assertEqual(metrics['DISK_TEMPERATURE_CELSIUS'], 42)
        self.assertEqual(metrics['DISK_WEAR_PERCENT'], 23)
        self.assertEqual(metrics['DISK_MEDIA_ERRORS'], 4)
        self.assertNotIn('TLS_DAYS_REMAINING', metrics)
        self.assertNotIn('CONTAINER_HEALTHY', metrics)


if __name__ == '__main__':
    unittest.main()
