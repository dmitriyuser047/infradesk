#!/usr/bin/python3
"""Only Docker/CA boundary is replaced; production Python, filesystem and netfilter remain real."""
import json, os, signal, subprocess, sys, time
from pathlib import Path
args = sys.argv[1:]
root = Path('/fixture/containers'); root.mkdir(exist_ok=True)
if args[0] == 'pull':
    sys.exit(0)
if args[0] == 'inspect':
    path = root / args[1]
    if not path.exists():
        print('No such container', file=sys.stderr); sys.exit(1)
    print(json.dumps([json.loads(path.read_text())])); sys.exit(0)
if args[0] == 'rm':
    path = root / args[-1]
    if path.exists():
        item = json.loads(path.read_text())
        try: os.kill(item['fixturePid'], signal.SIGTERM)
        except ProcessLookupError: pass
        path.unlink()
    sys.exit(0)
if args[0] != 'run': sys.exit(1)
name = args[args.index('--name') + 1]
label = args[args.index('--label') + 1].split('=', 1)[1]
image = next(a for a in args if a.startswith('certbot/certbot@'))
path = root / name
path.write_text(json.dumps({'Config': {'Image': image, 'Labels': {'infradesk.acme.owner': label}}, 'fixturePid': os.getpid()}))
if Path('/fixture/stall').exists():
    time.sleep(120); sys.exit(1)
domain = args[args.index('--cert-name') + 1]
mount = next(a for a in args if a.startswith('type=bind,src=') and a.endswith(',dst=/etc/letsencrypt'))
config = Path(mount.split('src=', 1)[1].split(',dst=', 1)[0])
archive = config / 'archive' / domain; archive.mkdir(parents=True, mode=0o700)
live = config / 'live' / domain; live.mkdir(parents=True, mode=0o700)
def openssl(*arguments):
    subprocess.run(['openssl', *arguments], check=True, capture_output=True)
openssl('req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes',
    '-keyout', '/fixture/ca.key', '-out', '/fixture/ca.pem', '-days', '365', '-subj', '/CN=InfraDesk fixture CA',
    '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
openssl('req', '-new', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes',
    '-keyout', str(archive/'privkey1.pem'), '-out', '/fixture/leaf.csr', '-subj', '/CN='+domain)
Path('/fixture/leaf.ext').write_text('subjectAltName=DNS:'+domain+'\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n')
openssl('x509', '-req', '-in', '/fixture/leaf.csr', '-CA', '/fixture/ca.pem', '-CAkey', '/fixture/ca.key',
    '-CAcreateserial', '-out', str(archive/'fullchain1.pem'), '-days', '90', '-extfile', '/fixture/leaf.ext')
with (archive/'fullchain1.pem').open('a') as chain:
    chain.write(Path('/fixture/ca.pem').read_text())
os.chmod(archive/'privkey1.pem', 0o600)
for name in ['privkey', 'fullchain']:
    (live/(name+'.pem')).symlink_to('../../archive/'+domain+'/'+name+'1.pem')
path.unlink()
