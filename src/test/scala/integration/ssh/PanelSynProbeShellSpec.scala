package ru.bitec.app.ops
package integration.ssh

import application.port.{PanelSynProbeSpec,RemnawaveNodeRemoteSpec}
import java.time.Instant
import java.util.UUID
import java.nio.charset.StandardCharsets
import scala.sys.process._
import scala.concurrent.duration._
import munit.FunSuite

/** Executes the compiled production Python against real Ubuntu nftables and real inbound SYN.
  * Only the systemd DBus boundary is replaced; its timer runs the exact production Cleanup.
  */
final class PanelSynProbeShellSpec extends FunSuite {
  override val munitTimeout: Duration=360.seconds // includes the cold Ubuntu package/image build
  private val enabled=sys.env.get("INFRADESK_RUN_SHELL_INTEGRATION_TESTS").contains("true")
  test(if(enabled) munit.TestOptions("production passive probe observes exact SYN without accepting it, rejects ambiguity and cleans after process loss")
    else munit.TestOptions("Ubuntu nftables packet integration").ignore) {
    val image="infradesk-syn-test:ubuntu24"
    assertEquals(Seq("docker","build","-q","-t",image,"src/test/resources/remnawave-syn").!,0)
    val network="infradesk-syn-"+UUID.randomUUID().toString
    val subnet="fdab:"+network.takeRight(8).take(4)+":"+network.takeRight(4)+"::/64"
    Seq("docker","network","create","--ipv6","--subnet",subnet,network).!!
    val host=Seq("docker","run","--rm","-d","--cap-add","NET_ADMIN","--network",network,image).!!.trim
    val peer=Seq("docker","run","--rm","-d","--network",network,image).!!.trim
    def command(container: String,args: Seq[String]): scala.sys.process.ProcessBuilder =
      Process(Seq("docker","exec","-i",container,"sh")) #< new java.io.ByteArrayInputStream(
        PosixArgv.encode(args.toList.map(_.replace("\r\n","\n"))).getBytes(StandardCharsets.UTF_8))
    def exec(container: String,args: String*): String=command(container,args).!!
    def python(container: String,program: String,args: String*): String=exec(container,(Seq("/usr/bin/python3","-c",program)++args): _*)
    def nft(program: String): String=python(host,"import subprocess,sys; r=subprocess.run(['/usr/sbin/nft','-f','-'],input=sys.argv[1].encode(),capture_output=True); print(r.stderr.decode()); sys.exit(r.returncode)",program)
    val node=RemnawaveNodeRemoteSpec(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2222,"remnawave/node:2.8.0",List("185.10.20.30/32"))
    def probe=PanelSynProbeSpec(UUID.randomUUID(),node,Instant.now().plusSeconds(5))
    def tableExists(p: PanelSynProbeSpec): Boolean=exec(host,"/usr/sbin/nft","-j","list","tables").contains(PanelSynProbe.table(p))
    def waitAttached(p: PanelSynProbeSpec): Unit = {
      val deadline=System.nanoTime()+3.seconds.toNanos
      while(!tableExists(p) && System.nanoTime()<deadline) Thread.sleep(50)
      assert(tableExists(p),"Production probe did not attach owned instrumentation")
    }
    val destination=Seq("docker","inspect","--format","{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",host).!!.trim
    val send="""import socket,struct,sys
src,dst,port,count=sys.argv[1:]; port=int(port)
def checksum(b):
 if len(b)%2: b+=b'\0'
 s=sum(struct.unpack('!%dH'%(len(b)//2),b)); s=(s>>16)+(s&65535); s+=(s>>16); return (~s)&65535
sa=socket.inet_aton(src); da=socket.inet_aton(dst)
t=struct.pack('!HHLLBBHHH',40222,port,1,0,80,2,8192,0,0)
c=checksum(sa+da+struct.pack('!BBH',0,6,len(t))+t)
t=struct.pack('!HHLLBBHHH',40222,port,1,0,80,2,8192,c,0)
i=struct.pack('!BBHHHBBH4s4s',69,0,40,1,0,64,6,0,sa,da)
i=struct.pack('!BBHHHBBH4s4s',69,0,40,1,0,64,6,checksum(i),sa,da)
s=socket.socket(socket.AF_INET,socket.SOCK_RAW,socket.IPPROTO_RAW)
for _ in range(int(count)): s.sendto(i+t,(dst,0))
"""
    def packets(ip: String,count: Int=1,port: Int=2222): Unit = { python(peer,send,ip,destination,port.toString,count.toString); () }
    def observe(p: PanelSynProbeSpec): (scala.sys.process.Process,StringBuilder) = {
      val output=new StringBuilder
      val process=command(host,Seq("/usr/bin/python3")++PanelSynProbe.args("observe",p))
        .run(ProcessLogger(line => output.append(line).append('\n'),line => output.append(line).append('\n')))
      (process,output)
    }
    try {
      exec(host,"chmod","755","/fixture/systemd-fixture.py")
      nft("add table inet fixture_guard\nadd chain inet fixture_guard input { type filter hook input priority 0; policy accept; }\nadd rule inet fixture_guard input tcp dport 2222 counter drop\n")
      val exact=probe; val (process,output)=observe(exact); waitAttached(exact)
      packets("185.10.20.41",port=2223); packets("185.10.20.42")
      assertEquals(process.exitValue(),0); assertEquals(output.toString.trim,"SOURCE:185.10.20.42/32")
      assert(!tableExists(exact))
      val guard=io.circe.parser.parse(exec(host,"/usr/sbin/nft","-j","list","table","inet","fixture_guard")).toOption.get
      assert(guard.noSpaces.contains("\"packets\":1"),"Original DROP still receives the SYN; probe cannot bypass it")
      exec(host,"ip","-6","address","add","2001:4860:4860::8844/64","dev","eth0","nodad")
      val mac=exec(host,"cat","/sys/class/net/eth0/address").trim
      val ipv6=probe; val (vp,vo)=observe(ipv6); waitAttached(ipv6)
      val send6="""import socket,struct,sys
dstmac=sys.argv[1]
sa=socket.inet_pton(socket.AF_INET6,'2001:4860:4860::8845'); da=socket.inet_pton(socket.AF_INET6,'2001:4860:4860::8844')
t=struct.pack('!HHLLBBHHH',40222,2222,1,0,80,2,8192,0,0)
b=sa+da+struct.pack('!I3xB',len(t),6)+t
c=sum(struct.unpack('!%dH'%(len(b)//2),b)); c=(c>>16)+(c&65535); c+=(c>>16); c=(~c)&65535
t=struct.pack('!HHLLBBHHH',40222,2222,1,0,80,2,8192,c,0)
i=struct.pack('!IHBB16s16s',6<<28,len(t),6,64,sa,da)
mac=lambda s:bytes.fromhex(s.replace(':',''))
eth=mac(dstmac)+mac(open('/sys/class/net/eth0/address').read().strip())+struct.pack('!H',0x86dd)
s=socket.socket(socket.AF_PACKET,socket.SOCK_RAW); s.bind(('eth0',0)); s.send(eth+i+t)
"""
      python(peer,send6,mac)
      assertEquals(vp.exitValue(),0); assertEquals(vo.toString.trim,"SOURCE:2001:4860:4860::8845/128"); assert(!tableExists(ipv6))
      val ambiguous=probe; val (ap,ao)=observe(ambiguous); waitAttached(ambiguous)
      packets("185.10.20.41"); packets("185.10.20.42")
      assertEquals(ap.exitValue(),0); assertEquals(ao.toString.trim,"AMBIGUOUS"); assert(!tableExists(ambiguous))
      val exceeded=probe; val (ep,eo)=observe(exceeded); waitAttached(exceeded); packets("185.10.20.41",65)
      assertEquals(ep.exitValue(),0); assertEquals(eo.toString.trim,"AMBIGUOUS"); assert(!tableExists(exceeded))
      val silent=probe; val (sp,so)=observe(silent)
      assertEquals(sp.exitValue(),0); assertEquals(so.toString.trim,"NO_TRAFFIC"); assert(!tableExists(silent))
      val foreign=probe
      nft(s"add table inet ${PanelSynProbe.table(foreign)} { comment \"foreign\"; }\n")
      val (fp,fo)=observe(foreign); assertEquals(fp.exitValue(),1); assert(tableExists(foreign))
      nft(s"delete table inet ${PanelSynProbe.table(foreign)}\n")
      // Kill the actual probe interpreter; detached timer invokes production cleanup, without a worker.
      val crashed=probe
      val wrapper="import os,sys; open('/fixture/probe.pid','w').write(str(os.getpid())); program=sys.argv.pop(1); exec(program)"
      val args=PanelSynProbe.args("observe",crashed).drop(2)
      val cp=command(host,Seq("/usr/bin/python3","-c",wrapper,PanelSynProbe.Program)++args).run(ProcessLogger(_=>(),_=>()))
      waitAttached(crashed)
      python(host,"import os,signal; os.kill(int(open('/fixture/probe.pid').read()),signal.SIGKILL)")
      assert(cp.exitValue()!=0)
      val until=System.nanoTime()+22.seconds.toNanos
      while(tableExists(crashed) && System.nanoTime()<until) Thread.sleep(100)
      assert(!tableExists(crashed),"Host cleanup lease must survive death of the probe")
      assert(exec(host,"/usr/sbin/nft","-j","list","tables").contains("fixture_guard"))
    } finally { Seq("docker","stop",host,peer).!; Seq("docker","network","rm",network).!; () }
  }
}
