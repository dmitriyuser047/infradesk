package ru.bitec.app.ops
package integration.ssh

import domain.integration.NodeTlsHttp01
import java.time.Instant
import java.util.UUID
import java.nio.charset.StandardCharsets
import scala.sys.process._
import scala.concurrent.duration._
import munit.FunSuite

/** Real Ubuntu/netfilter verifies issuance, private exports, no repeated order and independent lease cleanup. */
final class NodeHttp01ProbeShellSpec extends FunSuite {
  override val munitTimeout: Duration=240.seconds
  private val enabled=sys.env.get("INFRADESK_RUN_SHELL_INTEGRATION_TESTS").contains("true")
  test(if(enabled) munit.TestOptions("production HTTP-01 exports the owned certificate, preserves management isolation and cleans after issuer crash")
    else munit.TestOptions("Ubuntu HTTP-01 integration").ignore) {
    assertEquals(Seq("docker","build","-q","-t","infradesk-syn-test:ubuntu24","src/test/resources/remnawave-syn").!,0)
    val image="infradesk-http01-test:ubuntu24"
    assertEquals(Seq("docker","build","-q","-t",image,"src/test/resources/remnawave-http01").!,0)
    val host=Seq("docker","run","--rm","-d","--cap-add","NET_ADMIN",image).!!.trim
    def command(args: List[String])=Process(Seq("docker","exec","-i",host,"sh")) #< new java.io.ByteArrayInputStream(
      PosixArgv.encode(args.map(_.replace("\r\n","\n"))).getBytes(StandardCharsets.UTF_8))
    def exec(args: String*)=command(args.toList).!!
    def issue(resource: UUID, request: NodeTlsHttp01, fresh: Boolean, window: Long = 30)=command(List("/usr/bin/python3") ++
      NodeHttp01Probe.args("issue",resource,"example.org",request,Instant.now().plusSeconds(window),fresh))
    val resource=UUID.randomUUID(); val request=NodeTlsHttp01(UUID.randomUUID(),"operator@example.org")
    try {
      exec("sh","-c","iptables -N ufw-before-input; ip6tables -N ufw6-before-input; printf '#!/bin/sh\\nprintf \"Status: active\\n\"\\n' >/usr/local/bin/ufw; chmod 755 /usr/local/bin/ufw")
      assertNotEquals(command(List("/usr/sbin/ip6tables","-S","ufw-before-input")).!(ProcessLogger(_=>(),_=>())),0)
      exec("/usr/sbin/ip6tables","-A","ufw6-before-input","-p","tcp","--dport","2222","-m","comment","--comment","foreign-management","-j","DROP")
      val result=issue(resource,request,true).!!
      val material=io.circe.parser.parse(result).toOption.get
      assert(material.hcursor.get[String]("privateKeyPem").toOption.get.startsWith("-----BEGIN PRIVATE KEY-----"))
      assert(material.hcursor.get[String]("certificatePem").toOption.get.startsWith("-----BEGIN CERTIFICATE-----"))
      val tls=new domain.integration.NodeTlsMaterial(material.hcursor.get[String]("certificatePem").toOption.get,
        material.hcursor.get[String]("privateKeyPem").toOption.get)
      assertEquals(integration.secret.NodeTlsValidation.validate("other.example.org",tls,Instant.now()),Left("REMNAWAVE_TLS_SAN_MISMATCH"))
      assertEquals(integration.secret.NodeTlsValidation.validate("example.org",tls,Instant.now().plusSeconds(87*86400)),Left("REMNAWAVE_TLS_EXPIRY_TOO_CLOSE"))
      val wrongKey=exec("openssl","genpkey","-algorithm","EC","-pkeyopt","ec_paramgen_curve:P-256")
      assertEquals(integration.secret.NodeTlsValidation.validate("example.org",new domain.integration.NodeTlsMaterial(tls.certificatePem,wrongKey),Instant.now()),Left("REMNAWAVE_TLS_KEY_MISMATCH"))
      // A matching key passes cryptographic proof but this fixture CA must still be rejected.
      assertEquals(integration.secret.NodeTlsValidation.validate("example.org",tls,Instant.now()),Left("REMNAWAVE_TLS_CHAIN_UNTRUSTED"))
      val certificates=java.security.cert.CertificateFactory.getInstance("X.509")
      val ca=certificates.generateCertificate(new java.io.ByteArrayInputStream(exec("cat","/fixture/ca.pem").getBytes(StandardCharsets.US_ASCII)))
      val store=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType)
      store.load(null,null); store.setCertificateEntry("fixture-ca",ca)
      val managers=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
      managers.init(store)
      val trusted=managers.getTrustManagers.collectFirst { case m: javax.net.ssl.X509TrustManager => m }.get
      val validated=integration.secret.NodeTlsValidation.validateWithTrustManager("example.org",tls,Instant.now(),() => trusted)
      assert(validated.isRight,validated.left.toOption.getOrElse(""))
      assert(!tls.toString.contains("PRIVATE KEY"))
      assert(!exec("/usr/sbin/iptables","-S","ufw-before-input").contains("infradesk:acme:"))
      val ipv6AfterIssue=exec("/usr/sbin/ip6tables","-S","ufw6-before-input")
      assert(!ipv6AfterIssue.contains("infradesk:acme:")); assert(ipv6AfterIssue.contains("foreign-management"))
      val reused=issue(resource,request,false).!!
      assertEquals(io.circe.parser.parse(reused).toOption.get,material)
      val timer="infradesk-acme-"+request.certificateId+"-cleanup.timer"
      val absentTimer=exec("/usr/bin/systemctl","show",timer,"--property=LoadState","--property=ActiveState","--property=Description")
      assert(absentTimer.contains("LoadState=not-found")); assert(absentTimer.contains("Description="+timer))
      for (fault<-List("foreign","unavailable")) {
        exec("touch","/fixture/timers/"+fault)
        val failed=new StringBuilder
        assertEquals(issue(resource,request,false).!(ProcessLogger(line=>failed.append(line),_=>())),45)
        assertEquals(failed.toString,"UNKNOWN")
        assertEquals(exec("cat","/fixture/timers/"+fault),"")
        exec("rm","/fixture/timers/"+fault)
      }
      assertEquals(io.circe.parser.parse(issue(resource,request,false).!!).toOption.get,material)
      val marker="infradesk:acme:"+resource+":"+request.certificateId
      for ((exe,chain)<-List(("/usr/sbin/iptables","ufw-before-input"),("/usr/sbin/ip6tables","ufw6-before-input")))
        exec(exe,"-A",chain,"-p","tcp","--dport","80","-m","comment","--comment",marker,"-j","ACCEPT")
      exec("touch","/fixture/daemon-unavailable")
      val unavailable=new StringBuilder
      assertEquals(issue(resource,request,false).!(ProcessLogger(line=>unavailable.append(line),_=>())),45)
      assertEquals(unavailable.toString,"UNKNOWN")
      assert(exec("/usr/sbin/iptables","-S","ufw-before-input").contains(marker))
      assert(exec("/usr/sbin/ip6tables","-S","ufw6-before-input").contains(marker))
      exec("rm","/fixture/daemon-unavailable")
      assertEquals(io.circe.parser.parse(issue(resource,request,false).!!).toOption.get,material)
      assert(!exec("/usr/sbin/iptables","-S","ufw-before-input").contains(marker))
      assert(!exec("/usr/sbin/ip6tables","-S","ufw6-before-input").contains(marker))
      exec("touch","/fixture/stall")
      val crashed=NodeTlsHttp01(UUID.randomUUID(),"operator@example.org")
      val process=issue(resource,crashed,true,15).run(ProcessLogger(_=>(),_=>()))
      val timeout=System.nanoTime()+5.seconds.toNanos
      while(!exec("/usr/sbin/iptables","-S","ufw-before-input").contains(crashed.certificateId.toString) && System.nanoTime()<timeout) Thread.sleep(50)
      val attached=exec("/usr/sbin/iptables","-S","ufw-before-input")
      assert(attached.contains("--dport 80")); assert(!attached.contains("2222"))
      val attached6=exec("/usr/sbin/ip6tables","-S","ufw6-before-input")
      assert(attached6.contains(crashed.certificateId.toString)); assert(attached6.contains("--dport 80"))
      // Kill the remote issuer, independently from its detached host cleanup timer.
      exec("/usr/bin/python3","-c", "import os,sys,signal; from pathlib import Path\nfor p in Path('/proc').iterdir():\n if p.name.isdigit():\n  try:\n   a=(p/'cmdline').read_bytes().split(b'\\0')\n   if len(a)>2 and a[1]==b'-c' and a[2]==sys.argv[1].encode(): os.kill(int(p.name),signal.SIGKILL)\n  except (FileNotFoundError,ProcessLookupError): pass",NodeHttp01Probe.Program)
      process.exitValue()
      val expires=System.nanoTime()+20.seconds.toNanos
      while(exec("/usr/sbin/iptables","-S","ufw-before-input").contains(crashed.certificateId.toString) && System.nanoTime()<expires) Thread.sleep(100)
      assert(!exec("/usr/sbin/iptables","-S","ufw-before-input").contains(crashed.certificateId.toString))
      val ipv6AfterLease=exec("/usr/sbin/ip6tables","-S","ufw6-before-input")
      assert(!ipv6AfterLease.contains(crashed.certificateId.toString)); assert(ipv6AfterLease.contains("foreign-management"))
      val output=new StringBuilder
      val code=issue(resource,crashed,false).!(ProcessLogger(line=>output.append(line),_=>()))
      assertEquals(code,45); assertEquals(output.toString,"UNKNOWN")
    } finally { Seq("docker","stop",host).!; () }
  }
}
