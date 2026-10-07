package ru.bitec.app.ops
package integration.ssh

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection,ConnectionConfig,ConnectionScope}
import domain.provisioning.{PackageModule,ToggleModule}
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import munit.FunSuite
import support.ServerProfileFixtures

final class SshProfileObserverSpec extends FunSuite {
  private val connection=Connection(UUID.randomUUID(),UUID.randomUUID(),ConnectionScope.Organization,"SSH","server","ssh",
    ConnectionConfig(Map.empty),None,true,Instant.now(),Instant.now())
  private val ok=RemoteCommandOutput(0,"","")

  // Execute the actual collector shell, replacing only dpkg-query's package responses.
  private def observe(status: String,exit: Int=0,docker: Boolean=false): ServerProfileRemoteObservation = {
    val session=new RemoteConfigurationSession[IO] {
      def supportsAtomicReplace=IO.pure(true)
      def read(path:String,maxBytes:Int)=IO.pure(RemoteConfigurationFile.Missing)
      def create(path:String,bytes:Array[Byte],creation:RemoteFileCreation)=IO.unit
      def atomicReplace(from:String,to:String)=IO.unit
      def remove(path:String)=IO.unit
      def execute(executable:String,args:List[String],timeout:scala.concurrent.duration.FiniteDuration)=
        executeCaptured(executable,args,timeout,65536).map(_.exitCode)
      override def executeCaptured(executable:String,args:List[String],timeout:scala.concurrent.duration.FiniteDuration,maxOutputBytes:Int)=IO {
        if(args.exists(_.contains("for package in"))) {
          val fixture=s"""fixture_dpkg_query() {
            case "$$3" in
              docker-ce|docker-ce-cli|containerd.io|docker-compose-plugin) printf '%s' 'ii '; return 0;;
              docker.io|curl) printf '%s' '$status'; return $exit;;
              *) return 1;;
            esac
          }
          if command -v shopt >/dev/null 2>&1; then shopt -s expand_aliases; fi
          alias dpkg-query=fixture_dpkg_query
          """
          val shell=if(System.getProperty("os.name").startsWith("Windows"))
            Paths.get(sys.env.getOrElse("ProgramFiles","C:\\Program Files"),"Git","bin","bash.exe").toString else "sh"
          val process=new ProcessBuilder((List(shell,"-s","--") ++ args.drop(3)): _*)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
          process.getOutputStream.write((fixture+args(1)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
          process.getOutputStream.close()
          if(!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Package probe timed out") }
          val output=scala.util.Using.resource(process.getInputStream)(stream =>
            new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))
          RemoteCommandOutput(process.exitValue(),output,"")
        } else if(executable=="id") ok.copy(stdout="0")
        else if(args.exists(_.contains("SSH_CONNECTION"))) ok.copy(stdout="192.0.2.3 49152 198.51.100.1 22")
        else if(args.exists(_.contains("load=$(systemctl"))) ok.copy(stdout="enabled\nactive")
        else if(args.exists(_.contains("command -v")) || args.exists(_.contains("docker compose version"))) ok.copy(stdout="YES")
        else ok
      }
    }
    val transport=new RemoteConfigurationTransport[IO] {
      def withSession[A](c:Connection)(use:RemoteConfigurationSession[IO] => IO[A])=use(session)
    }
    val desired=ServerProfileFixtures.disabled.copy(packages=PackageModule(!docker,List("curl")),docker=ToggleModule(docker))
    new SshProfileObserver(transport).observe(connection,UUID.randomUUID(),Some(desired)).unsafeRunSync()
  }

  test("docker.io un is absent with installed docker-ce and docker-compose-plugin") {
    val result=observe("un ",docker=true)
    assertEquals(result.failureCode,None)
    assertEquals(result.content.hcursor.downField("packages").get[List[String]]("installed"),
      Right(List("containerd.io","docker-ce","docker-ce-cli","docker-compose-plugin")))
    assertEquals(result.content.hcursor.downField("docker").get[Boolean]("installed"),Right(true))
    assertEquals(result.content.hcursor.downField("docker").get[Boolean]("composeAvailable"),Right(true))
  }
  test("un and an unmatched dpkg-query exit 1 both report package absence") {
    List("un " -> 0,"" -> 1).foreach { case (status,exit) =>
      val result=observe(status,exit)
      assertEquals(result.failureCode,None)
      assertEquals(result.content.hcursor.downField("packages").get[List[String]]("installed"),Right(Nil))
    }
  }
  test("intermediate, broken, error-flagged and malformed package states fail closed") {
    List("iU ","iF ","iH ","iW ","it ","iiR","unR","in ","ii","un","","unexpected").foreach { status =>
      val result=observe(status)
      assert(result.blockingProblems.contains("PROVISIONING_PACKAGE_PROBE_FAILED"),s"status=$status")
      val findings=domain.provisioning.PackageProbeFinding.fromObservation(result.content)
      assertEquals(findings.map(_.name),List("curl"))
      assertEquals(findings.head.observedState,Option.when(status.matches("[uihrp][ncHUFWti][ R]"))(status))
      assertEquals(findings.head.classification,if(Set("iU ","iF ","iH ","iW ","it ","iiR","unR")(status)) "BROKEN" else "UNKNOWN")
      assert(!result.content.noSpaces.contains("unexpected"))
    }
  }
  test("database errors and nonzero queries with status output fail closed") {
    List("" -> 2,"ii " -> 2,"un " -> 2,"iF " -> 1).foreach { case (status,exit) =>
      assert(observe(status,exit).blockingProblems.contains("PROVISIONING_PACKAGE_PROBE_FAILED"))
    }
  }
}
