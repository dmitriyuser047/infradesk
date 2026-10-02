package ru.bitec.app.ops
package integration.ssh

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection,ConnectionConfig,ConnectionScope}
import domain.provisioning._
import munit.FunSuite
import support.ServerProfileFixtures
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

final class ProfileRemoteSafetySpec extends FunSuite {
  private val ok=RemoteCommandOutput(0,"","")
  private val id=UUID.randomUUID()
  private val connection=Connection(UUID.randomUUID(),UUID.randomUUID(),ConnectionScope.Organization,"SSH","server","ssh",
    ConnectionConfig(Map.empty),None,true,Instant.now(),Instant.now())
  private class Session extends RemoteConfigurationSession[IO] {
    val calls=ListBuffer.empty[(String,List[String])]
    val creations=ListBuffer.empty[(String,RemoteFileCreation)]
    var respond: (String,List[String]) => IO[RemoteCommandOutput]=(_,_) => IO.pure(ok)
    def supportsAtomicReplace=IO.pure(true)
    def read(path:String,maxBytes:Int)=IO.pure(RemoteConfigurationFile.Missing)
    def create(path:String,bytes:Array[Byte],creation:RemoteFileCreation)=IO { creations += path -> creation; calls += "CREATE" -> List(path); () }
    def atomicReplace(from:String,to:String)=IO.unit
    def remove(path:String)=IO.unit
    def execute(executable:String,args:List[String],timeout:FiniteDuration)=executeCaptured(executable,args,timeout,65536).map(_.exitCode)
    override def executeCaptured(executable:String,args:List[String],timeout:FiniteDuration,maxOutputBytes:Int)=
      IO {calls += executable -> args} *> IO.defer(respond(executable,args))
    def scripts: List[String]=calls.toList.collect {case ("sh",List("-c",script,_*)) => script}
  }
  private def files(s:Session,root:Boolean=true)=new ProfileManagedFiles(s,new ProfileCommands(s,root),if(root) 0 else 1000,id)
  private val marker="# managed"
  private val body=(marker+"\nnew\n").getBytes(StandardCharsets.UTF_8)
  private val previous="1"*64
  private val path="/etc/caddy/Caddyfile"
  private def fail(action:IO[Unit]): ProfileRemoteFailure=intercept[ProfileRemoteFailure](action.unsafeRunSync())

  test("candidate validation failure never commits or activates, with private exclusive SFTP staging") {
    val s=new Session
    s.respond=(ex,args) => IO.pure(if(ex=="caddy") ok.copy(exitCode=1) else ok)
    val e=fail(files(s).replace(path,marker,body,Some(previous),validate=Some(List("caddy","validate","--config")),activate=Some(List("reload"))))
    assertEquals(e.code,"PROVISIONING_CADDY_VALIDATION_FAILED")
    assert(!s.scripts.contains(ProfileManagedFiles.Commit))
    assert(!s.calls.exists(_._1=="reload"))
    assertEquals(s.creations.size,1)
    assertEquals(s.creations.head._2,RemoteFileCreation(384,None))
    assert(s.creations.head._1.startsWith(s"/tmp/infradesk-$id-"))
    assert(s.scripts.exists(_.contains("rmdir")))
  }
  test("known reload failure rolls back only the guarded predecessor and reactivates it") {
    val s=new Session; var activations=0
    s.respond=(ex,args) => IO { if(ex=="reload") {activations+=1; ok.copy(exitCode=if(activations==1) 1 else 0)} else ok }
    val e=fail(files(s).replace(path,marker,body,Some(previous),activate=Some(List("reload"))))
    assertEquals(e.code,"PROVISIONING_CADDY_RELOAD_FAILED")
    assert(!e.uncertain)
    assertEquals(activations,2)
    val scripts=s.scripts
    assert(scripts.indexOf(ProfileManagedFiles.Commit)<scripts.indexOf(ProfileManagedFiles.Rollback))
  }
  test("disconnect or truncated activation is uncertain and never triggers blind rollback") {
    List(false,true).foreach { truncate =>
      val s=new Session
      s.respond=(ex,args) => if(ex=="reload") {
        if(truncate) IO.pure(ok.copy(stderrTruncated=true)) else IO.raiseError(RemoteConfigurationFailure.Unavailable)
      } else IO.pure(ok)
      val result=files(s).replace(path,marker,body,Some(previous),activate=Some(List("reload"))).attempt.unsafeRunSync()
      assert(result.isLeft)
      assert(!s.scripts.contains(ProfileManagedFiles.Rollback))
      if(truncate) assert(result.swap.toOption.get.asInstanceOf[ProfileRemoteFailure].uncertain)
    }
  }
  test("unsafe target refuses replacement; nonroot helpers use literal sudo -n arguments") {
    val s=new Session
    s.respond=(ex,args) => IO.pure(if(args.contains(ProfileManagedFiles.Prepare)) ok.copy(exitCode=42) else ok)
    val e=fail(files(s,root=false).replace(path,marker,body,Some(previous)))
    assertEquals(e.code,"PROVISIONING_MANAGED_FILE_UNSAFE")
    assert(!s.calls.exists(_._2.contains(ProfileManagedFiles.Commit)))
    assert(s.calls.exists {case (ex,args) => ex=="sudo" && args.take(3)==List("-n","sh","-c")})
    intercept[IllegalArgumentException](files(s).replace("/etc/passwd",marker,body,None))
  }
  test("packages already present skip apt; unsafe simulation cannot reach installation") {
    val s=new Session
    val c=ServerProfileFixtures.disabled.copy(packages=PackageModule(true,List("curl")))
    val facts=ServerProfileFixtures.observed(c)
    val context=ProfileExecutionContext(id,Nil,facts)
    val installer=new ProfilePackageInstaller(s,new ProfileCommands(s,true),files(s))
    val skipped=installer.install(c,context).unsafeRunSync()
    assert(skipped.skipped && s.calls.isEmpty)
    val empty=facts.deepMerge(io.circe.Json.obj("packages" -> io.circe.Json.obj("installed" -> io.circe.Json.arr())))
    s.respond=(ex,args) => IO.pure(if(args.contains(ProfilePackageInstaller.Simulate)) ok.copy(exitCode=54) else ok)
    val result=installer.install(c,context.copy(reviewedObservation=empty)).attempt.unsafeRunSync()
    assert(result.isLeft)
    assert(!s.scripts.exists(_.contains("DEBIAN_FRONTEND=noninteractive apt-get install")))
  }

  private def firewall(s:Session,canaryOk:Boolean=true): ProfileFirewall = {
    val transport=new RemoteConfigurationTransport[IO] {
      def withSession[A](c:Connection)(use:RemoteConfigurationSession[IO] => IO[A]): IO[A] =
        if(canaryOk) {val fresh=new Session; fresh.respond=(_,_) => IO.pure(ok.copy(stdout="1000")); use(fresh)}
        else IO.raiseError(RemoteConfigurationFailure.Unavailable)
    }
    new ProfileFirewall(transport,connection,new ProfileCommands(s,true),id)
  }
  test("fixed signed repository refuses unsafe existing key metadata before installing Caddy") {
    val s=new Session
    val c=ServerProfileFixtures.disabled.copy(caddy=ServerProfileFixtures.content.caddy)
    val observed=ServerProfileFixtures.observed(c).deepMerge(io.circe.Json.obj(
      "packages" -> io.circe.Json.obj("installed" -> io.circe.Json.arr()),
      "caddy" -> io.circe.Json.obj("installed" -> io.circe.Json.False)))
    s.respond=(ex,args) => IO.pure {
      if(ex=="sh" && args.contains("apt-cache policy caddy")) ok.copy(stdout="Candidate: (none)")
      else if(args.contains(SshProfileObserver.FileProbe)) ok.copy(stdout="UNSAFE")
      else if(args.exists(_.contains("ca-certificates 2>/dev/null"))) ok.copy(stdout="ii ")
      else ok
    }
    val result=new ProfilePackageInstaller(s,new ProfileCommands(s,true),files(s))
      .install(c,ProfileExecutionContext(id,Nil,observed)).attempt.unsafeRunSync()
    assertEquals(result.swap.toOption.get.asInstanceOf[ProfileRemoteFailure].code,"PROVISIONING_CADDY_REPOSITORY_UNSAFE")
    assert(s.creations.isEmpty)
    assert(!s.scripts.exists(_.contains("DEBIAN_FRONTEND=noninteractive apt-get install")))
  }
  private def ufwSession(raw:String): Session = {
    val s=new Session
    s.respond=(ex,args) => IO.pure(if(ex=="sh" && args.exists(_.contains("SSH_CONNECTION"))) ok.copy(stdout="192.0.2.3 49152 198.51.100.1 2222")
      else if(ex=="ufw" && args==List("show","added")) ok.copy(stdout=raw)
      else if(ex=="ufw" && args==List("status")) ok.copy(stdout="Status: active") else ok)
    s
  }
  private val sshRule=FirewallRule("ssh","tcp",2222,List("ANY"))
  test("foreign equivalent allow is reused and old owned deletion has the exact nonempty comment") {
    val s=ufwSession(s"ufw allow 2222/tcp comment 'foreign'\nufw allow 443/tcp comment 'infradesk:$id:old'")
    firewall(s).apply(FirewallModule(true,List(sshRule))).unsafeRunSync()
    val mutations=s.calls.filter {case(ex,args) => ex=="ufw" && args!=List("show","added") && args!=List("status")}
    assertEquals(mutations.size,1)
    assertEquals(mutations.head._2,List("--force","delete","allow","from","any","to","any","port","443","proto","tcp","comment",s"infradesk:$id:old"))
  }
  test("new management allow precedes canary; failed canary prevents old-rule deletion") {
    val s=ufwSession(s"ufw allow 443/tcp comment 'infradesk:$id:old'")
    assert(firewall(s,canaryOk=false).apply(FirewallModule(true,List(sshRule))).attempt.unsafeRunSync().isLeft)
    assert(s.calls.exists {case(ex,args) => ex=="ufw" && args.headOption.contains("allow") && args.contains("2222")})
    assert(!s.calls.exists(_._2.contains("delete")))
  }
  test("unproven SSH access, unknown output and ownership collision fail before a firewall mutation") {
    val examples=List("unexpected output",s"ufw allow 2222/tcp comment 'infradesk:$id:different'")
    examples.foreach { raw =>
      val s=ufwSession(raw)
      assert(firewall(s).apply(FirewallModule(true,List(sshRule))).attempt.unsafeRunSync().isLeft)
      assert(!s.calls.exists {case(ex,args) => ex=="ufw" && (args.contains("delete") || args.headOption.contains("allow"))})
    }
    val s=ufwSession("Added user rules (see 'ufw status' for running firewall)")
    assert(firewall(s).apply(FirewallModule(true,List(sshRule.copy(port=22)))).attempt.unsafeRunSync().isLeft)
    assert(!s.calls.exists(_._1=="ufw"))
  }
  test("owned topology changed since preview is rejected before reconciling unreviewed rules") {
    val s=ufwSession(s"ufw allow 443/tcp comment 'infradesk:$id:new'")
    val reviewed=io.circe.Json.obj("managedRules" -> io.circe.Json.arr())
    val outcome=firewall(s).apply(FirewallModule(true,List(sshRule)),Some(reviewed)).attempt.unsafeRunSync()
    assertEquals(outcome.swap.toOption.get.asInstanceOf[ProfileRemoteFailure].code,"PROVISIONING_PROFILE_PLAN_CHANGED")
    assert(!s.calls.exists {case(ex,args) => ex=="ufw" && (args.contains("delete") || args.headOption.contains("allow"))})
  }
  test("Caddy observation uses its real version subcommand and actual listener ownership; blockers fail VERIFY") {
    val c=ServerProfileFixtures.disabled.copy(caddy=ServerProfileFixtures.content.caddy)
    val s=new Session; var foreignListener=false
    s.respond=(ex,args) => IO.pure {
      if(ex=="id") ok.copy(stdout="0")
      else if(ex=="sh" && args.exists(_.contains("SSH_CONNECTION"))) ok.copy(stdout="192.0.2.3 49152 198.51.100.1 22")
      else if(args.exists(_.contains("for package in"))) ok.copy(stdout="caddy\nca-certificates\n")
      else if(args.exists(_.contains("command -v \"$1\"")))
        if(args.takeRight(2)==List("caddy","version")) ok.copy(stdout="YES") else ok.copy(exitCode=1)
      else if(args.exists(_.contains("load=$(systemctl"))) ok.copy(stdout="enabled\nactive")
      else if(args.contains(SshProfileObserver.FileProbe)) ok.copy(stdout="MANAGED\n"+ServerProfileDiff.renderedCaddyHash(c.caddy))
      else if(args.exists(_.contains("awk '$1==\"https_port\""))) ok.copy(stdout="8080")
      else if(ex=="systemctl") ok.copy(stdout="123")
      else if(ex=="ss") ok.copy(stdout="State Recv-Q Send-Q Local Peer Process\nLISTEN 0 128 0.0.0.0:8080 0.0.0.0:* users:((\"caddy\",pid=123,fd=5))\n"+
        (if(foreignListener) "LISTEN 0 128 0.0.0.0:443 0.0.0.0:* users:((\"foreign\",pid=999,fd=5))\n" else ""))
      else if(ex=="getent") ok.copy(stdout="198.51.100.1 STREAM")
      else ok
    }
    val transport=new RemoteConfigurationTransport[IO] {
      def withSession[A](connection:Connection)(use:RemoteConfigurationSession[IO] => IO[A])=use(s)
    }
    val remote=new SshServerProfileRemote(transport)
    val observed=remote.observe(connection,id,Some(c)).unsafeRunSync()
    assertEquals(observed.failureCode,None)
    assertEquals(observed.blockingProblems,Nil)
    assertEquals(ServerProfileObservationCodec.validate(observed.content),Right(observed.content))
    assert(ServerProfileDiff.assess(c,observed.content).compliant)
    foreignListener=true
    val snapshot=ServerProfileApplySnapshot(id,1,id,id,1,c.hash,c,id,observed.contentHash,"1"*64)
    val verified=remote.applyModule(connection,id,snapshot,ProvisioningStepKind.Verify,
      ProfileExecutionContext(id,Nil,observed.content)).unsafeRunSync()
    assertEquals(verified.verificationResult,Some(false))
    assertEquals(verified.failureCode,Some("PROVISIONING_VERIFICATION_FAILED"))
    assert(verified.profileObservation.exists(_.blockingProblems.contains("CADDY_PORT_IN_USE")))
  }
}
