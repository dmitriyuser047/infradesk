package ru.bitec.app.ops
package integration.ssh

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection,ConnectionConfig,ConnectionScope}
import domain.integration.{LocalInstallationState,LocalInstallationDiagnosis}
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.sys.process._
import munit.FunSuite

/** Real Ubuntu shell/coreutils and the compiled production probe, through the SSH argv boundary. */
final class RecoveryProbeShellSpec extends FunSuite {
  override val munitTimeout: Duration = 120.seconds
  private val enabled=sys.env.get("INFRADESK_RUN_SHELL_INTEGRATION_TESTS").contains("true")
  test(if(enabled) munit.TestOptions("Ubuntu 24 owned env-only stage survives managed UFW observation; probe failures retain closed diagnoses") else munit.TestOptions("Ubuntu 24 shell integration").ignore) {
    val cid=Seq("docker","run","--rm","-d","--network","none","ubuntu:24.04","sleep","180").!!.trim
    def shell(script:String):String=(Process(Seq("docker","exec","-i","-e","SSH_CONNECTION=192.0.2.2 45000 192.0.2.1 22",cid,"sh")) #< new java.io.ByteArrayInputStream(script.replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))).!!
    val spec=RemnawaveNodeRemoteSpec(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2222,
      "remnawave/node:2.8.0",List("192.0.2.1/32"))
    val conn=Connection(UUID.randomUUID(),UUID.randomUUID(),ConnectionScope.Organization,"SSH","server","ssh",
      ConnectionConfig(Map.empty),None,true,Instant.now(),Instant.now())
    val owner=SshRemnawaveNodeRemote.ownershipDirectory(spec)
    val stage=SshRemnawaveNodeRemote.stagingDirectory(spec)
    var failExecution=false
    val session=new RemoteConfigurationSession[IO] {
      def supportsAtomicReplace=IO.pure(true)
      def read(p:String,n:Int)=IO.raiseError[RemoteConfigurationFile](new AssertionError("Read-only shell observation must not use SFTP"))
      def create(p:String,b:Array[Byte],c:RemoteFileCreation)=IO.raiseError[Unit](new AssertionError("No mutation"))
      def atomicReplace(a:String,b:String)=IO.raiseError[Unit](new AssertionError("No mutation"))
      def remove(p:String)=IO.raiseError[Unit](new AssertionError("No mutation"))
      def execute(e:String,a:List[String],t:FiniteDuration)=executeCaptured(e,a,t,65536).map(_.exitCode)
      override def executeCaptured(e:String,a:List[String],t:FiniteDuration,n:Int)=IO.blocking {
        val output=new StringBuilder; val errors=new StringBuilder
        val prefix=if(failExecution && a.contains(SshRemnawaveNodeRemote.RecoveryProbe.replace("\r\n","\n"))) "exit 23; " else ""
        val input=(prefix+PosixArgv.encode(e::a)).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val exit=(Process(Seq("docker","exec","-i","-e","SSH_CONNECTION=192.0.2.2 45000 192.0.2.1 22",cid,"sh")) #< new java.io.ByteArrayInputStream(input)).!(ProcessLogger(
          line=>output.append(line).append("\n"),line=>errors.append(line).append("\n")))
        RemoteCommandOutput(exit,output.toString,errors.toString)
      }
    }
    val remote=new SshRemnawaveNodeRemote(new RemoteConfigurationTransport[IO] {
      def withSession[A](c:Connection)(use:RemoteConfigurationSession[IO]=>IO[A])=use(session)
    })
    try {
      shell(s"""set -eu
        mkdir -p /opt/infradesk/remnawave /mocks
        chmod 755 /opt /opt/infradesk /opt/infradesk/remnawave
        mkdir -m 700 '$owner'
        mkdir -m 755 '$stage'
        printf 'SECRET_KEY=Zml4dHVyZQ==\nNODE_PORT=2222\n' >'$stage/.env'
        chmod 600 '$stage/.env'
        printf '#!/bin/sh\nexit 0\n' >/mocks/ss
        printf '#!/bin/sh\n[ "$$1" = ps ] || exit 1\n' >/mocks/docker
        cat >/mocks/ufw <<'MOCK'
#!/bin/sh
printf "Added user rules (see 'ufw status' for running firewall)\nufw allow from 192.0.2.1 to any port 2222 proto tcp comment 'infradesk:remnawave:${spec.resourceId}:${spec.externalNodeId}:node'\n"
MOCK
        chmod 755 /mocks/*
        ln -s /mocks/ss /usr/local/bin/ss
        ln -s /mocks/docker /usr/local/bin/docker
        ln -s /mocks/ufw /usr/local/bin/ufw
      """)
      assertEquals(remote.localInstallationState(conn,spec).unsafeRunSync(),LocalInstallationState.OwnedPartial)
      val layered=remote.localInstallationObservation(conn,spec).unsafeRunSync()
      assertEquals(layered.state,LocalInstallationState.OwnedPartial)
      assertEquals(layered.diagnosis,None)
      val probeArgs=List(SshRemnawaveNodeRemote.directory(spec),SshRemnawaveNodeRemote.markerLine(spec),
        SshRemnawaveNodeRemote.managedPrefix(spec),SshRemnawaveNodeRemote.containerName(spec),spec.imageReference,
        spec.nodePort.toString,owner,ProfileManagedFiles.sha256(SshRemnawaveNodeRemote.renderCompose(spec).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"0")
      val crlf=new ProfileCommands(session,root=true).capture("sh",List("-c",
        SshRemnawaveNodeRemote.RecoveryProbe.replace("\r\n","\n").replace("\n","\r\n"),"infradesk") ++ probeArgs).unsafeRunSync()
      assertEquals(crlf.exitCode,0)
      assertEquals(crlf.stdout.trim,"OWNED_PARTIAL")
      assertEquals(shell(s"stat -c '%a' '$owner' '$stage' '$stage/.env'").linesIterator.toList,List("700","755","600"))
      assertEquals(shell(s"find '$stage' -mindepth 1 -maxdepth 1 -printf '%f\n'").trim,".env")
      shell(s"""cat >/usr/local/bin/stat <<'MOCK'
#!/bin/sh
case "$$3" in '$stage/.env') exit 2 ;; esac
exec /usr/bin/stat "$$@"
MOCK
chmod 755 /usr/local/bin/stat
""")
      assertEquals(remote.localInstallationObservation(conn,spec).unsafeRunSync().diagnosis,Some(LocalInstallationDiagnosis.StagingMetadataUnavailable))
      shell("rm /usr/local/bin/stat")
      shell("printf '#!/bin/sh\nexit 2\n' >/usr/local/bin/sha256sum; chmod 755 /usr/local/bin/sha256sum")
      assertEquals(remote.localInstallationObservation(conn,spec).unsafeRunSync().diagnosis,Some(LocalInstallationDiagnosis.HashProbeFailed))
      shell("rm /usr/local/bin/sha256sum; printf '#!/bin/sh\nexit 2\n' >/mocks/ss")
      assertEquals(remote.localInstallationObservation(conn,spec).unsafeRunSync().diagnosis,Some(LocalInstallationDiagnosis.PortStateUnknown))
      failExecution=true
      assertEquals(remote.localInstallationObservation(conn,spec).unsafeRunSync().diagnosis,Some(LocalInstallationDiagnosis.ProbeExecutionFailed))
      failExecution=false
      shell("printf '#!/bin/sh\nexit 0\n' >/mocks/ss")
      val retired=remote.retireInstallation(conn,spec).unsafeRunSync()
      assertEquals(retired.failureCode,None)
      assertEquals(retired.facts.get("retired"),Some("true"))
      assertEquals(remote.localInstallationState(conn,spec).unsafeRunSync(),LocalInstallationState.Absent)
      val replay=remote.retireInstallation(conn,spec).unsafeRunSync()
      assertEquals(replay.failureCode,None)
      assertEquals(replay.facts.get("retired"),Some("false"))
    } finally { Seq("docker","stop",cid).!; () }
  }
}
