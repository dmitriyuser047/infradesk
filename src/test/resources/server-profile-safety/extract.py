from pathlib import Path
import re,sys
repo=Path(sys.argv[1]); out=Path(sys.argv[2])
s=(repo/'src/main/scala/integration/ssh/ProfileManagedFiles.scala').read_text(encoding='utf-8')
parents=re.search(r'private val SafeParents = """(.*?)"""',s,re.S).group(1)
for name in ['Prepare','Commit','Rollback']:
 body=re.search(r'val '+name+r' = "set -eu; " \+ SafeParents \+ """(.*?)"""',s,re.S).group(1)
 (out/(name+'.sh')).write_text('set -eu; '+parents+body,encoding='utf-8',newline='\n')
for file,names in [('SshProfileObserver.scala',['FileProbe']),('ProfilePackageInstaller.scala',['Simulate','CaddyDefaultProof'])]:
 s=(repo/'src/main/scala/integration/ssh'/file).read_text(encoding='utf-8')
 for name in names:
  body=re.search(r'val '+name+r'\s*=\s*"""(.*?)"""',s,re.S).group(1)
  (out/(name+'.sh')).write_text(body,encoding='utf-8',newline='\n')

s=(repo/'src/main/scala/integration/ssh/SshRemnawaveNodeRemote.scala').read_text(encoding='utf-8')
proof=re.search(r'private val ManagedFileProof = """(.*?)"""\.stripMargin',s,re.S).group(1)
def margin(text): return '\n'.join(line.split('|',1)[1] if '|' in line and line.split('|',1)[0].strip()=='' else line for line in text.splitlines())
for name in ['InstallationProof','Start']:
 body=re.search(r'private val '+name+r' = "set -u; " \+ ManagedFileProof \+ """(.*?)"""\.stripMargin',s,re.S).group(1)
 (out/('Node'+name+'.sh')).write_text('set -u; '+margin(proof)+margin(body),encoding='utf-8',newline='\n')
body=re.search(r'private val Preflight = """(.*?)"""\.stripMargin',s,re.S).group(1)
(out/'NodePreflight.sh').write_text(margin(body),encoding='utf-8',newline='\n')
