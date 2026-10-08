package ru.bitec.app.ops
package integration.remnawave

import cats.effect.unsafe.implicits.global
import domain.integration._
import munit.FunSuite
import org.xbill.DNS._
import org.xbill.DNS.lookup.LookupSession
import java.net.{DatagramPacket,DatagramSocket,InetAddress}
import java.time.Duration
import java.util.concurrent.Executors
import scala.concurrent.duration._

final class RemnawavePanelDnsResolverSpec extends FunSuite {
  private val endpoint=IntegrationBaseUrl.parse("https://panel.example.test").toOption.get
  private def fixture(a: => List[String],aaaa: List[String],privateAllowed: Boolean = false)(f: RemnawavePanelDnsResolver => Unit,
    nodeDns: RemnawaveNodeAddressDnsResolver => Unit = _ => ()): Unit = {
    val socket=new DatagramSocket(0,InetAddress.getLoopbackAddress)
    val executor=Executors.newSingleThreadExecutor()
    executor.submit(new Runnable { def run(): Unit = try {
      while(!socket.isClosed) {
        val packet=new DatagramPacket(new Array[Byte](8192),8192); socket.receive(packet)
        val request=new Message(java.util.Arrays.copyOfRange(packet.getData,0,packet.getLength))
        val reply=new Message(request.getHeader.getID); reply.getHeader.setFlag(Flags.QR)
        reply.getHeader.setFlag(Flags.AA); reply.addRecord(request.getQuestion,Section.QUESTION)
        val question=request.getQuestion
        val values=if(question.getType==Type.A) a else aaaa
        values.foreach { ip => reply.addRecord(if(question.getType==Type.A)
          new ARecord(question.getName,DClass.IN,3600,InetAddress.getByName(ip)) else
          new AAAARecord(question.getName,DClass.IN,1,InetAddress.getByName(ip)),Section.ANSWER) }
        if(values.isEmpty) reply.getHeader.setRcode(Rcode.NXDOMAIN)
        val bytes=reply.toWire; socket.send(new DatagramPacket(bytes,bytes.length,packet.getSocketAddress))
      }
    } catch { case _: java.net.SocketException => () } })
    try {
      val resolver=new SimpleResolver("127.0.0.1"); resolver.setPort(socket.getLocalPort); resolver.setTimeout(Duration.ofMillis(250))
      f(new RemnawavePanelDnsResolver(RemnawavePanelDnsResolver.freshLookup(resolver),privateAllowed))
      nodeDns(new RemnawaveNodeAddressDnsResolver(RemnawavePanelDnsResolver.freshLookup(resolver)))
    } finally { socket.close(); executor.shutdownNow() }
  }
  test("explicit Node domain must resolve exclusively to the authenticated server; NXDOMAIN and unrelated addresses fail closed") {
    fixture(List("185.10.20.20"),Nil)(_ => (),dns => {
      val evidence=dns.verifyDomain("node.example.test",List("185.10.20.20")).unsafeRunSync()
      assertEquals(evidence.mode,NodeAddressMode.Domain)
      assertEquals(evidence.address,"node.example.test")
      assert(dns.verifyDomain("node.example.test",List("185.10.20.21")).attempt.unsafeRunSync().isLeft)
      assert(dns.verifyDomain("185.10.20.20",List("185.10.20.20")).attempt.unsafeRunSync().isLeft)
    })
    fixture(Nil,Nil)(_ => (),dns => assert(dns.verifyDomain("missing.example.test",List("185.10.20.20")).attempt.unsafeRunSync().isLeft))
    fixture(List("185.10.20.20","185.10.20.21"),Nil)(_ => (),dns =>
      assert(dns.verifyDomain("node.example.test",List("185.10.20.20")).attempt.unsafeRunSync().isLeft))
  }
  test("production AUTO resolves A and AAAA as exact canonical candidates") {
    fixture(List("185.10.20.30"),List("2001:4860:4860::8888")) { resolver =>
      val evidence=resolver.resolve(endpoint,PanelSourceMode.Auto,Nil,None).unsafeRunSync()
      assertEquals(evidence.confidence,"AUTO_CANDIDATE")
      assert(evidence.sources.contains("185.10.20.30/32"))
      assert(evidence.sources.exists(_.endsWith("/128")))
      assertEquals(OnboardingInput.canonicalCidrs(evidence.sources),Right(evidence.sources))
      assert(!evidence.sources.contains("2.27.26.18/32"))
      assertEquals(PanelSourceEvidence.decode(PanelSourceEvidence.encode(evidence)),evidence)
    }
  }
  test("AUTO cannot use operator manual inputs, and a managed Panel resource has priority") {
    fixture(List("185.10.20.30"),Nil) { resolver =>
      val evidence=resolver.resolve(endpoint,PanelSourceMode.Auto,List("2.27.26.18/32"),Some("185.10.20.31")).unsafeRunSync()
      assertEquals(evidence.sources,List("185.10.20.31/32"))
      assertEquals(evidence.method,"MANAGED_PANEL_RESOURCE")
    }
  }
  test("start revalidation observes DNS changes even with a long record TTL") {
    val values=new java.util.concurrent.atomic.AtomicReference(List("185.10.20.30"))
    fixture(values.get(),Nil) { resolver =>
      val preview=resolver.resolve(endpoint,PanelSourceMode.Auto,Nil,None).unsafeRunSync()
      values.set(List("185.10.20.31"))
      val start=resolver.resolve(endpoint,PanelSourceMode.Auto,Nil,None).unsafeRunSync()
      assertEquals(preview.sources,List("185.10.20.30/32"))
      assertEquals(start.sources,List("185.10.20.31/32"))
      assertNotEquals(start,preview)
      assertEquals(resolver.resolve(endpoint,PanelSourceMode.Auto,Nil,Some("operator@host")).unsafeRunSync().confidence,"UNRESOLVED")
    }
  }
  test("unresolved DNS and excessive candidates stay unresolved without broadening") {
    for(values <- List(Nil,(1 to 33).map(n => s"185.10.20.$n").toList)) fixture(values,Nil) { resolver =>
      val result=resolver.resolve(endpoint,PanelSourceMode.Auto,Nil,None).unsafeRunSync()
      assertEquals(result.confidence,"UNRESOLVED"); assertEquals(result.sources,Nil)
    }
  }
  test("reserved sources are refused, private addresses need explicit deployment policy") {
    for(ip <- List("0.0.0.0","127.0.0.1","169.254.169.254","224.0.0.1","240.0.0.1","10.0.0.1","100.64.0.1","192.0.2.1","198.18.0.1"))
      fixture(List(ip),Nil) { resolver => assertEquals(resolver.resolve(endpoint,PanelSourceMode.Auto,Nil,None).unsafeRunSync().confidence,"UNRESOLVED") }
    fixture(List("10.0.0.1"),Nil,privateAllowed=true) { resolver =>
      assertEquals(resolver.resolve(endpoint,PanelSourceMode.Auto,Nil,None).unsafeRunSync().sources,List("10.0.0.1/32"))
    }
  }
  test("MANUAL strict sources are accepted; /0 and duplicates are rejected") {
    fixture(Nil,Nil) { resolver =>
      assertEquals(resolver.resolve(endpoint,PanelSourceMode.Manual,List("185.10.20.30/32"),None).unsafeRunSync().confidence,"MANUAL")
      for(values <- List(List("0.0.0.0/0"),List("::/0"),List("185.10.20.30/32","185.10.20.30/32")))
        assert(resolver.resolve(endpoint,PanelSourceMode.Manual,values,None).attempt.unsafeRunSync().isLeft)
    }
  }
  test("blackhole DNS completes inside its bounded deadline") {
    val socket=new DatagramSocket(0,InetAddress.getLoopbackAddress)
    try {
      val resolver=new SimpleResolver("127.0.0.1"); resolver.setPort(socket.getLocalPort); resolver.setTimeout(Duration.ofMillis(200))
      val source=new RemnawavePanelDnsResolver(LookupSession.builder().resolver(resolver).clearSearchPath().build(),false)
      val at=System.nanoTime
      assertEquals(source.resolve(endpoint,PanelSourceMode.Auto,Nil,None).unsafeRunSync().confidence,"UNRESOLVED")
      assert((System.nanoTime-at).nanos<5.seconds)
    } finally socket.close()
  }
}
