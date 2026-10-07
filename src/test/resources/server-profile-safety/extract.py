from pathlib import Path
import json,re,sys
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
proof=re.search(r'private(?:\[ssh\])? val ManagedFileProof = """(.*?)"""\.stripMargin',s,re.S).group(1)
def margin(text): return '\n'.join(line.split('|',1)[1] if '|' in line and line.split('|',1)[0].strip()=='' else line for line in text.splitlines())
catalog=json.loads((repo/'src/main/resources/integration/remnawave/node-image-releases.json').read_text(encoding='utf-8'))
refs=[]
for release in catalog:
 refs.append(release['imageRepository']+'@'+release['manifestDigest'])
 refs.extend(release['imageRepository']+'@'+p['manifestDigest'] for p in release['platforms'])
refs.extend(['remnawave/node:2.8.0','remnawave/node:3.4.1'])
controlled_body=re.search(r'private\[ssh\] def controlledComposeProof.*?s"""(.*?)"""\.stripMargin',s,re.S).group(1)
controlled_snippet=margin(controlled_body).replace('$$','$').replace('${domain.integration.RemnawaveNodeReleaseCatalog.managedReferences.mkString("|")}', '|'.join(dict.fromkeys(refs))).replace('$failure','return 1')
original='[ "$(sha256sum "$d/compose.yml" | cut -d\' \' -f1)" = "$composeHash" ] || return 1'
assert original in margin(proof), 'Original managed proof changed; review normalization extraction'
controlled=margin(proof).replace(original, controlled_snippet)
for name in ['InstallationProof','Start']:
 body=re.search(r'private lazy val '+name+r' = "set -u; " \+ SshManagedNodeImages.ManagedImageProof \+ """(.*?)"""\.stripMargin',s,re.S).group(1)
 (out/('Node'+name+'.sh')).write_text('set -u; '+controlled+margin(body),encoding='utf-8',newline='\n')
body=re.search(r'private val Preflight = """(.*?)"""\.stripMargin',s,re.S).group(1)
(out/'NodePreflight.sh').write_text(margin(body),encoding='utf-8',newline='\n')
body=re.search(r'private(?:\[ssh\])? val RecoveryProbe = """(.*?)"""\.stripMargin',s,re.S).group(1)
(out/'NodeRecoveryProbe.sh').write_text(margin(body).replace('CONTROLLED_COMPOSE_PROOF', controlled_snippet),encoding='utf-8',newline='\n')
probe=margin(body).replace('CONTROLLED_COMPOSE_PROOF', controlled_snippet)
for name in ['PrepareInstallation','CleanupInstallationStaging','PublishInstallation','RetireInstallation']:
 body=re.search(r'private val '+name+r' = .*? \+ """(.*?)"""\.stripMargin',s,re.S)
 assert body, f'Could not extract {name}; review shell construction'
 script='recovery_probe() {\n'+probe+'\n}\n'+margin(body.group(1))
 (out/('Node'+name+'.sh')).write_text(script,encoding='utf-8',newline='\n')

(out/'NodeImageOwnership.sh').write_text('set -u; '+controlled+'\nmanaged_files "$1" "$2" "$3" "$4" "$5" && printf OWNED || printf UNMANAGED\n',encoding='utf-8',newline='\n')
(out/'NodeImageRefs.txt').write_text(refs[0]+'\n'+refs[-3]+'\n',encoding='utf-8',newline='\n')
