package ru.bitec.app.ops
package integration.ssh

import domain.integration.NodeTlsHttp01
import java.time.Instant
import java.util.UUID

/** One reviewed ACME implementation. Network access is leased on TCP/80 only; it is never persisted by UFW. */
private[ssh] object NodeHttp01Probe {
  val Image = "certbot/certbot@sha256:bb72eb98a32ecc5dcef8eeeb8fb6a2a8c50d4d93e2a2c3542f8cb679840c990c"
  def args(mode: String, resource: UUID, domain: String, request: NodeTlsHttp01, deadline: Instant, fresh: Boolean): List[String] =
    List("-c",Program,mode,resource.toString,request.certificateId.toString,domain,request.email,
      deadline.getEpochSecond.toString, fresh.toString,Image)

  val Cleanup = """import json,subprocess,sys,shlex
resource,identity,image=sys.argv[1:]
marker='infradesk:acme:'+resource+':'+identity
name='infradesk-acme-'+identity
def call(args): return subprocess.run(args,capture_output=True,timeout=15)
def remove():
 r=call(['docker','inspect',name])
 if r.returncode==0:
  item=json.loads(r.stdout)[0]
  if item.get('Config',{}).get('Labels',{}).get('infradesk.acme.owner')!=marker or item['Config'].get('Image')!=image: raise RuntimeError()
  if call(['docker','rm','-f',name]).returncode: raise RuntimeError()
 else:
  # Docker 29 changed the missing-object message. Prove absence through a successful
  # exact-name listing; an inspect/daemon failure alone is never absence evidence.
  listed=call(['docker','container','ls','--all','--filter','name=^/'+name+'$','--format','{{.Names}}'])
  if listed.returncode or listed.stdout.strip(): raise RuntimeError()
 for exe,chain in [('/usr/sbin/iptables','ufw-before-input'),('/usr/sbin/ip6tables','ufw6-before-input')]:
  r=call([exe,'-S',chain])
  if r.returncode: raise RuntimeError()
  owned=[shlex.split(x) for x in r.stdout.decode().splitlines() if marker in x]
  expected=['-A',chain,'-p','tcp','-m','tcp','--dport','80','-m','comment','--comment',marker,'-j','ACCEPT']
  if len(owned)>1 or any(x!=expected for x in owned): raise RuntimeError()
  if owned and call([exe,'-D']+expected[1:]).returncode: raise RuntimeError()
remove()
"""

  val Program = """import os,sys,re,time,json,subprocess,stat,tempfile,shutil
mode,resource,identity,domain,email,deadline,fresh,image=sys.argv[1:]; deadline=int(deadline)
uuid=r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
if mode not in ['issue','cleanup'] or not re.fullmatch(uuid,resource) or not re.fullmatch(uuid,identity) or not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,251}[a-z0-9]',domain): sys.exit(42)
marker='infradesk:acme:'+resource+':'+identity; name='infradesk-acme-'+identity
unit=name+'-cleanup'; root='/var/lib/infradesk/remnawave/acme'; directory=root+'/'+identity
cleanup=""" + io.circe.Json.fromString(Cleanup).noSpaces + """
def call(args,timeout=15): return subprocess.run(args,capture_output=True,timeout=timeout)
def clean():
 if call(['/usr/bin/python3','-c',cleanup,resource,identity,image]).returncode: raise RuntimeError()
 r=call(['/usr/bin/systemctl','show',unit+'.timer','--property=Description','--value'])
 desc=r.stdout.decode().strip() if r.returncode==0 else ''
 if desc and desc!=marker: raise RuntimeError()
 if desc and call(['/usr/bin/systemctl','stop',unit+'.timer']).returncode: raise RuntimeError()
if mode=='cleanup':
 try: clean(); print('CLEANED'); sys.exit(0)
 except Exception: sys.exit(43)
def safe_dir(path,exact=False):
 s=os.lstat(path)
 if not stat.S_ISDIR(s.st_mode) or s.st_uid!=0 or s.st_gid!=0 or s.st_mode&0o022 or exact and stat.S_IMODE(s.st_mode)!=0o700: raise RuntimeError()
def owner():
 safe_dir(directory,True); path=directory+'/.owner'; s=os.lstat(path)
 if not stat.S_ISREG(s.st_mode) or s.st_uid!=0 or s.st_gid!=0 or s.st_nlink!=1 or stat.S_IMODE(s.st_mode)!=0o600: raise RuntimeError()
 if open(path).read()!=marker+'\n': raise RuntimeError()
def export():
 owner(); values=[]
 for filename,limit in [('fullchain.pem',32768),('privkey.pem',16384)]:
  path=os.path.realpath(directory+'/config/live/'+domain+'/'+filename)
  if not path.startswith(directory+'/config/archive/'+domain+'/'): raise RuntimeError()
  for parent in [directory+'/config',directory+'/config/archive',directory+'/config/archive/'+domain]: safe_dir(parent)
  s=os.lstat(path)
  if not stat.S_ISREG(s.st_mode) or s.st_uid!=0 or s.st_nlink!=1 or s.st_size>limit or s.st_mode&0o022: raise RuntimeError()
  if filename=='privkey.pem' and s.st_mode&0o077: raise RuntimeError()
  with open(path) as f: values.append(f.read(limit+1))
 print(json.dumps({'certificatePem':values[0],'privateKeyPem':values[1]}))
try:
 for exe in ['/usr/bin/python3','/usr/bin/systemd-run','/usr/bin/systemctl','/usr/sbin/iptables','/usr/sbin/ip6tables']:
  if not os.access(exe,os.X_OK): print('UNAVAILABLE'); sys.exit(44)
 if os.path.lexists(directory):
  owner()
  if os.path.exists(directory+'/config/live/'+domain+'/privkey.pem'):
   clean(); export(); sys.exit(0)
  # Durable evidence of an earlier attempt is never permission to issue a second order.
  print('UNKNOWN'); sys.exit(45)
 if fresh!='true' or not 0<deadline-int(time.time())<=210: print('UNKNOWN'); sys.exit(45)
 if call(['docker','pull',image],timeout=90).returncode: print('IMAGE_UNAVAILABLE'); sys.exit(44)
 status=call(['ufw','status'])
 if status.returncode or 'Status: active' not in status.stdout.decode(): print('UNAVAILABLE'); sys.exit(44)
 sockets=call(['ss','-H','-lnt','sport','=',':80'])
 if sockets.returncode or sockets.stdout.strip(): print('PORT_OCCUPIED'); sys.exit(44)
 for exe,chain in [('/usr/sbin/iptables','ufw-before-input'),('/usr/sbin/ip6tables','ufw6-before-input')]:
  if call([exe,'-S',chain]).returncode: print('UNAVAILABLE'); sys.exit(44)
 for parent in ['/var','/var/lib','/var/lib/infradesk','/var/lib/infradesk/remnawave',root]:
  if not os.path.lexists(parent): os.mkdir(parent,0o700)
  safe_dir(parent)
 staging=tempfile.mkdtemp(prefix='.issue-',dir=root)
 try:
  os.chmod(staging,0o700)
  with open(staging+'/.owner','x') as f: f.write(marker+'\n')
  os.chmod(staging+'/.owner',0o600)
  for child in ['config','work','logs']: os.mkdir(staging+'/'+child,0o700)
  if os.path.lexists(directory): raise RuntimeError()
  os.rename(staging,directory)
 except Exception:
  shutil.rmtree(staging); raise
 remaining=deadline-int(time.time())
 if remaining<=10: print('UNKNOWN'); sys.exit(45)
 r=call(['/usr/bin/systemd-run','--quiet','--collect','--unit='+unit,'--description='+marker,
  '--on-active='+str(remaining)+'s','--timer-property=Description='+marker,'--timer-property=AccuracySec=1s',
  '/usr/bin/python3','-c',cleanup,resource,identity,image])
 if r.returncode: raise RuntimeError()
 desc=call(['/usr/bin/systemctl','show',unit+'.timer','--property=Description','--value'])
 if desc.returncode or desc.stdout.decode().strip()!=marker: raise RuntimeError()
 try:
  for exe,chain in [('/usr/sbin/iptables','ufw-before-input'),('/usr/sbin/ip6tables','ufw6-before-input')]:
   if call([exe,'-I',chain,'1','-p','tcp','--dport','80','-m','comment','--comment',marker,'-j','ACCEPT']).returncode: raise RuntimeError()
  args=['docker','run','--rm','--name',name,'--label','infradesk.acme.owner='+marker,
   '--network','host','--cap-drop','ALL','--cap-add','NET_BIND_SERVICE','--security-opt','no-new-privileges',
   '--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=32m']
  for child,target in [('config','/etc/letsencrypt'),('work','/var/lib/letsencrypt'),('logs','/var/log/letsencrypt')]:
   args+=['--mount','type=bind,src='+directory+'/'+child+',dst='+target]
  args+=[image,'certonly','--standalone','--non-interactive','--agree-tos','--email',email,
   '--preferred-challenges','http','--key-type','ecdsa','--cert-name',domain,'-d',domain]
  result=call(args,timeout=max(1,deadline-int(time.time())-5))
  if result.returncode: print('ISSUANCE_FAILED'); sys.exit(46)
 finally: clean()
 export()
except Exception:
 try: clean()
 except Exception: pass
 print('UNKNOWN'); sys.exit(45)
"""
}
