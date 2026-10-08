package ru.bitec.app.ops
package integration.ssh

import application.port.PanelSynProbeSpec
import java.nio.charset.StandardCharsets

/** Fixed host instrumentation. It never changes UFW or issues a packet verdict. */
private[ssh] object PanelSynProbe {
  val WindowSeconds=PanelSynProbeSpec.WindowSeconds
  val MaxPackets=64
  def table(spec: PanelSynProbeSpec): String = "infradesk_syn_"+spec.runId.toString.replace("-", "")
  def marker(spec: PanelSynProbeSpec): String = "infradesk:panel-syn:"+ProfileManagedFiles.sha256(
    s"${spec.runId}:${spec.node.resourceId}:${spec.node.externalNodeId}:${spec.node.nodePort}:${spec.deadline.getEpochSecond}"
      .getBytes(StandardCharsets.UTF_8))
  def args(mode: String,spec: PanelSynProbeSpec): List[String] =
    List("-c",Program,mode,table(spec),marker(spec),spec.node.nodePort.toString,spec.deadline.getEpochSecond.toString)

  // The exact cleanup program is embedded in a transient timer BEFORE the atomic nft batch.
  // The host lease therefore survives SSH loss and a backend crash; reboot clears transient nft state.
  val Cleanup = """import json,subprocess,sys
table,marker,port=sys.argv[1:]; port=int(port)
r=subprocess.run(['/usr/sbin/nft','-j','list','table','inet',table],capture_output=True,timeout=5)
if r.returncode:
 r=subprocess.run(['/usr/sbin/nft','-j','list','tables'],capture_output=True,timeout=5)
 if r.returncode: sys.exit(1)
 sys.exit(1 if any(x.get('table',{}).get('name')==table for x in json.loads(r.stdout)['nftables']) else 0)
items=json.loads(r.stdout)['nftables']
tables=[x['table'] for x in items if 'table' in x]
if len(tables)!=1 or tables[0].get('comment')!=marker or tables[0].get('family')!='inet' or tables[0].get('name')!=table: sys.exit(1)
# A replaced or enlarged table is foreign, even if somebody copied its comment.
if any(set(x)-{'metainfo','table','chain','set','rule'} for x in items): sys.exit(1)
chains=[x['chain'] for x in items if 'chain' in x]
sets=[x['set'] for x in items if 'set' in x]
rules=[x['rule'] for x in items if 'rule' in x]
if len(chains)!=1 or chains[0].get('name')!='observe' or chains[0].get('hook')!='prerouting' or chains[0].get('prio')!=-301 or chains[0].get('policy')!='accept': sys.exit(1)
if len(sets)!=2 or {s.get('name') for s in sets}!={'sources4','sources6'} or any(s.get('size')!=2 for s in sets): sys.exit(1)
if any(s.get('type')!=('ipv4_addr' if s['name']=='sources4' else 'ipv6_addr') or set(s.get('flags',[]))!={'dynamic','timeout'} or s.get('timeout')!=90 for s in sets): sys.exit(1)
if any(x.get('family')!='inet' or x.get('table')!=table for x in chains+sets+rules): sys.exit(1)
if len(rules)!=2: sys.exit(1)
families=[]
for r in rules:
 if r.get('chain')!='observe' or r.get('comment')!=marker: sys.exit(1)
 e=r.get('expr',[])
 if len(e)!=6: sys.exit(1)
 family=e[0].get('match',{}).get('right'); families.append(family)
 if family not in ['ipv4','ipv6']: sys.exit(1)
 expected=[{'match':{'op':'==','left':{'meta':{'key':'nfproto'}},'right':family}},
  {'match':{'op':'!=','left':{'meta':{'key':'iifname'}},'right':'lo'}},
  {'match':{'op':'==','left':{'payload':{'protocol':'tcp','field':'dport'}},'right':port}},
  {'match':{'op':'==','left':{'&':[{'payload':{'protocol':'tcp','field':'flags'}},['fin','syn','rst','ack']]},'right':'syn'}}]
 if e[:4]!=expected or set(e[4])!={'counter'} or set(e[4]['counter'])!={'packets','bytes'}: sys.exit(1)
 if any(type(v) is not int or v<0 for v in e[4]['counter'].values()): sys.exit(1)
 if e[5]!={'set':{'op':'add','elem':{'payload':{'protocol':'ip' if family=='ipv4' else 'ip6','field':'saddr'}},'set':'@sources4' if family=='ipv4' else '@sources6'}}: sys.exit(1)
if set(families)!={'ipv4','ipv6'}: sys.exit(1)
sys.exit(subprocess.run(['/usr/sbin/nft','delete','table','inet',table],capture_output=True,timeout=5).returncode)
"""

  val Program: String = """import json,subprocess,sys,time,re
mode,table,marker,port,deadline=sys.argv[1:]
if mode not in ['observe','cleanup'] or not re.fullmatch(r'infradesk_syn_[0-9a-f]{32}',table) or not re.fullmatch(r'infradesk:panel-syn:[0-9a-f]{64}',marker): sys.exit(1)
port=int(port); deadline=int(deadline)
if not 0<port<65536: sys.exit(1)
unit=table+'-cleanup'
cleanup=""" + io.circe.Json.fromString(Cleanup).noSpaces + """
def call(args,**kw): return subprocess.run(args,capture_output=True,timeout=10,**kw)
def exists():
 r=call(['/usr/sbin/nft','-j','list','tables'])
 if r.returncode: raise RuntimeError()
 return any(x.get('table',{}).get('name')==table for x in json.loads(r.stdout)['nftables'])
def remove():
 r=call(['/usr/bin/python3','-c',cleanup,table,marker,str(port)])
 if r.returncode: raise RuntimeError()
def description():
 r=call(['/usr/bin/systemctl','show',unit+'.timer','--property=Description','--value'])
 return r.stdout.decode().strip() if not r.returncode else ''
def clean():
 remove()
 desc=description()
 if desc and desc!=marker: raise RuntimeError()
 if desc and call(['/usr/bin/systemctl','stop',unit+'.timer']).returncode: raise RuntimeError()
if mode=='cleanup':
 clean(); print('CLEANED'); sys.exit(0)
try:
 for exe in ['/usr/sbin/nft','/usr/bin/systemd-run','/usr/bin/systemctl','/usr/bin/python3']:
  import os
  if not os.access(exe,os.X_OK): print('UNAVAILABLE'); sys.exit(0)
 if call(['/usr/bin/systemctl','show','--property=SystemState','--value']).returncode: print('UNAVAILABLE'); sys.exit(0)
 if call(['/usr/sbin/nft','-j','list','tables']).returncode: print('UNAVAILABLE'); sys.exit(0)
 remaining=deadline-int(time.time())
 if remaining>90: raise RuntimeError()
 if remaining<=0 and not exists():
  clean(); print('NO_TRAFFIC'); sys.exit(0)
 if exists():
  # Read/cleanup validates the same ownership before using any packet evidence.
  desc=description()
  if desc!=marker: raise RuntimeError()
 else:
  desc=description()
  if desc and desc!=marker: raise RuntimeError()
  if not desc:
   r=call(['/usr/bin/systemd-run','--quiet','--collect','--unit='+unit,'--description='+marker,
    '--on-active='+str(remaining+15)+'s','--timer-property=Description='+marker,'--timer-property=AccuracySec=1s','--timer-property=RemainAfterElapse=no',
    '/usr/bin/python3','-c',cleanup,table,marker,str(port)])
   if r.returncode: print('UNAVAILABLE'); sys.exit(0)
  if description()!=marker: raise RuntimeError()
  rules=f'''add table inet {table} {{ comment "{marker}"; }}
add chain inet {table} observe {{ type filter hook prerouting priority -301; policy accept; }}
add set inet {table} sources4 {{ type ipv4_addr; flags dynamic,timeout; timeout 90s; size 2; }}
add set inet {table} sources6 {{ type ipv6_addr; flags dynamic,timeout; timeout 90s; size 2; }}
add rule inet {table} observe meta nfproto ipv4 iifname != "lo" tcp dport {port} tcp flags & (fin | syn | rst | ack) == syn counter add @sources4 {{ ip saddr }} comment "{marker}"
add rule inet {table} observe meta nfproto ipv6 iifname != "lo" tcp dport {port} tcp flags & (fin | syn | rst | ack) == syn counter add @sources6 {{ ip6 saddr }} comment "{marker}"
'''
  r=call(['/usr/sbin/nft','-f','-'],input=rules.encode())
  if r.returncode:
   clean(); print('UNAVAILABLE'); sys.exit(0)
 # Collect only closed, bounded IP values; raw nft metadata never reaches the application.
 while int(time.time())<deadline:
  time.sleep(1)
 r=call(['/usr/sbin/nft','-j','list','table','inet',table])
 if r.returncode:
  clean(); print('NO_TRAFFIC'); sys.exit(0)
 items=json.loads(r.stdout)['nftables']
 sources=[]; packets=0
 for item in items:
  for elem in item.get('set',{}).get('elem',[]):
   if isinstance(elem,str): sources.append(elem)
   elif isinstance(elem,dict): sources.append(elem.get('elem',{}).get('val'))
  for expr in item.get('rule',{}).get('expr',[]): packets+=expr.get('counter',{}).get('packets',0)
 clean()
 if len(sources)>1 or packets>64: print('AMBIGUOUS')
 elif len(sources)==1 and isinstance(sources[0],str):
  import ipaddress
  ip=ipaddress.ip_address(sources[0]); print('SOURCE:'+str(ip)+('/32' if ip.version==4 else '/128'))
 elif not sources and packets==0: print('NO_TRAFFIC')
 else: print('AMBIGUOUS')
except Exception:
 # An uncertain cleanup must block the workflow, never become a usable source.
 try: clean()
 except Exception: pass
 sys.exit(1)
"""
}
