#!/usr/bin/python3
"""Test-only systemd boundary: the production cleanup itself executes in a detached timer.
The nftables kernel and packet path are real; systemd DBus is unavailable inside this container.
"""
import json, os, signal, subprocess, sys
from pathlib import Path

args = sys.argv[1:]
root = Path('/fixture/timers')
root.mkdir(exist_ok=True)
if sys.argv[0].endswith('systemd-run'):
    unit = next(x.split('=', 1)[1] for x in args if x.startswith('--unit='))
    marker = next(x.split('=', 2)[2] for x in args if x.startswith('--timer-property=Description='))
    delay = int(next(x.split('=', 1)[1][:-1] for x in args if x.startswith('--on-active=')))
    command = args[args.index('/usr/bin/python3'):]
    timer = subprocess.Popen(['/usr/bin/python3', '-c',
        'import json,subprocess,sys,time; time.sleep(int(sys.argv[1])); subprocess.run(json.loads(sys.argv[2]),timeout=10)',
        str(delay), json.dumps(command)], start_new_session=True, stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (root / unit).write_text(json.dumps({'description': marker, 'pid': timer.pid, 'delay': delay, 'command': command}))
elif args == ['show', '--property=SystemState', '--value']:
    print('running')
elif args[0] == 'show':
    path = root / args[1].removesuffix('.timer')
    if '--property=LoadState' in args:
        if (root/'unavailable').exists(): sys.exit(1)
        exists = path.exists()
        description = json.loads(path.read_text())['description'] if exists else args[1]
        if (root/'foreign').exists(): exists = True; description = 'foreign timer'
        print('Description='+description)
        print('LoadState='+('loaded' if exists else 'not-found'))
        print('ActiveState='+('active' if exists else 'inactive'))
    elif path.exists():
        print(json.loads(path.read_text())['description'])
    else:
        sys.exit(1)
elif args[0] == 'stop':
    path = root / args[1].removesuffix('.timer')
    if path.exists():
        try:
            os.kill(json.loads(path.read_text())['pid'], signal.SIGTERM)
        except ProcessLookupError:
            pass
        path.unlink()
else:
    sys.exit(1)
