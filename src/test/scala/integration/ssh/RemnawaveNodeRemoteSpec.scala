package ru.bitec.app.ops
package integration.ssh

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.integration.NodeInstallationData
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import munit.FunSuite

final class RemnawaveNodeRemoteSpec extends FunSuite {
  private val ok = RemoteCommandOutput(0, "", "")
  private val resource = UUID.randomUUID()
  private val node = UUID.randomUUID()
  private val run = UUID.randomUUID()
  private val image = "remnawave/node:2.8.0"
  private val spec = RemnawaveNodeRemoteSpec(run, resource, node, 30222, image, List("192.0.2.0/24"))
  private val connection = Connection(UUID.randomUUID(), UUID.randomUUID(), ConnectionScope.Organization, "SSH", "server", "ssh",
    ConnectionConfig(Map.empty), None, true, Instant.now(), Instant.now())

  private class Session extends RemoteConfigurationSession[IO] {
    val calls = ListBuffer.empty[(String, List[String])]
    val uploads = ListBuffer.empty[(String, Array[Byte], RemoteFileCreation)]
    var respond: (String, List[String]) => IO[RemoteCommandOutput] = (_, _) => IO.pure(ok)
    var createFailure = false
    def supportsAtomicReplace = IO.pure(true)
    def read(path: String, maxBytes: Int) = IO.pure(RemoteConfigurationFile.Missing)
    def create(path: String, bytes: Array[Byte], creation: RemoteFileCreation) = IO {
      uploads += ((path, bytes.clone(), creation)); if (createFailure) throw RemoteConfigurationFailure.Unavailable
    }
    def atomicReplace(from: String, to: String) = IO.unit
    def remove(path: String) = IO.unit
    def execute(executable: String, args: List[String], timeout: FiniteDuration) = executeCaptured(executable, args, timeout, 65536).map(_.exitCode)
    override def executeCaptured(executable: String, args: List[String], timeout: FiniteDuration, maxOutputBytes: Int) =
      IO { calls += executable -> args } *> IO.defer(respond(executable, args))
  }
  private def remote(s: Session) = new SshRemnawaveNodeRemote(new RemoteConfigurationTransport[IO] {
    def withSession[A](c: Connection)(use: RemoteConfigurationSession[IO] => IO[A]) = use(s)
  })
  private def idResponse(s: Session): Unit = s.respond = (ex, args) => IO.pure {
    if (ex == "id") ok.copy(stdout = "0")
    else if (ex == "sh" && args.exists(_.contains("sport = :"))) ok.copy(stdout = "CLEAR")
    else if (ex == "sh" && args.exists(_.contains("SSH_CONNECTION"))) ok.copy(stdout = "192.0.2.5 45000 198.51.100.1 22")
    else if (ex == "ufw" && args == List("status")) ok.copy(stdout = "Status: active")
    else if (ex == "ufw" && args == List("show", "added")) ok.copy(stdout = "Added user rules (see 'ufw status' for running firewall)")
    else ok
  }

  test("rejects floating images and broad CIDRs before remote mutation") {
    val s = new Session; idResponse(s)
    val floating = remote(s).install(connection, spec.copy(imageReference = "remnawave/node:latest"), NodeInstallationData.fromSecretKey("secret")).attempt.unsafeRunSync()
    assertEquals(floating.toOption.flatMap(_.failureCode), Some("PROVISIONING_NODE_IMAGE_UNPINNED"))
    val broad = remote(s).configureFirewall(connection, spec.copy(panelCidrs = List("0.0.0.0/0"))).attempt.unsafeRunSync()
    assertEquals(broad.toOption.flatMap(_.failureCode), Some("PROVISIONING_NODE_INVALID_CIDR"))
    assertEquals(s.calls.map(_._1).toList, List("id", "id"))
  }

  test("post-baseline prerequisites require Docker, Compose and active firewall before create") {
    val s = new Session; idResponse(s)
    val unavailable = remote(s).installationPrerequisites(connection).unsafeRunSync()
    assertEquals(unavailable.failureCode, Some("PROVISIONING_NODE_DOCKER_UNAVAILABLE"))
    s.respond = (ex,args) => IO.pure(if(ex=="id") ok.copy(stdout="0")
      else if(ex=="docker") ok.copy(stdout="2.8.0")
      else if(ex=="ufw") ok.copy(stdout="Status: active") else ok)
    assertEquals(remote(s).installationPrerequisites(connection).unsafeRunSync().failureCode,None)
  }

  test("install sends credential only as private SFTP bytes, validates compose before exact pull, and never starts") {
    val s = new Session; idResponse(s)
    val secret = Base64.getEncoder.encodeToString(Array.tabulate[Byte](49152)(i => if (i % 3 == 1) 0xff.toByte else 0xfb.toByte))
    assertEquals(secret.length, 65536)
    assert(secret.contains("+") && secret.contains("/"))
    val installed = remote(s).install(connection, spec, NodeInstallationData.fromSecretKey(secret)).unsafeRunSync()
    assertEquals(installed.failureCode, None)
    assert(installed.facts.values.forall(v => !v.contains(secret)))
    val env = s.uploads.find(_._1.endsWith("/input")).get._2
    assert(new String(env, StandardCharsets.UTF_8).contains(secret))
    assert(!new String(env, StandardCharsets.UTF_8).contains("infradesk-managed"))
    val managed = new String(s.uploads.last._2, StandardCharsets.UTF_8)
    assert(managed.contains("\"envSha256\":\""))
    assert(!managed.contains(secret))
    assert(!s.calls.exists(_._2.contains(secret)))
    assert(!s.calls.exists { case (ex, args) => ex == "docker" && args.contains("up") })
    val validations = s.calls.filter { case (ex, args) => ex == "docker" && args.contains("config") }
    val pulls = s.calls.filter { case (ex, args) => ex == "docker" && args.headOption.contains("pull") }
    assertEquals(validations.size, 1)
    assertEquals(validations.head._2.head, "compose")
    assertEquals(pulls.toList.map(_._2), List(List("pull", image)))
    assert(s.calls.indexWhere(_._2.contains("config")) < s.calls.indexWhere(_._2.headOption.contains("pull")))
    assert(s.calls.forall { case (_, args) => !args.exists(_.contains(secret)) })
    assert(s.uploads.exists(_._3.permissions == 384))
  }

  test("start is a single up request and a lost reply is reported as uncertain") {
    val s = new Session; idResponse(s)
    s.respond = (ex, args) => if (ex == "id") IO.pure(ok.copy(stdout = "0"))
      else if (ex == "sh" && args.exists(a => a.contains("compose") && a.contains("up -d"))) IO.raiseError(RemoteConfigurationFailure.Unavailable)
      else IO.pure(ok)
    val result = remote(s).start(connection, spec).unsafeRunSync()
    assertEquals(result.failureCode, Some("PROVISIONING_REMOTE_UNAVAILABLE"))
    assert(result.uncertain)
    assert(s.calls.exists { case (ex, args) => ex == "sh" && args.exists(script => script.contains("docker compose") && script.contains("up -d")) })
    assert(!s.calls.exists(_._2.contains("down")))
  }

  test("known compose start failure is returned as a known failure") {
    val s = new Session; idResponse(s)
    s.respond = (ex, args) => if (ex == "id") IO.pure(ok.copy(stdout = "0"))
      else if (ex == "sh" && args.exists(script => script.contains("docker compose") && script.contains("up -d"))) IO.pure(ok.copy(stdout = "START_FAILED"))
      else IO.pure(ok)
    val result = remote(s).start(connection, spec).unsafeRunSync()
    assertEquals(result.failureCode, Some("PROVISIONING_NODE_START_FAILED"))
    assert(!result.uncertain)
    assert(result.facts.isEmpty)
  }

  test("start rechecks managed hashes and compose config immediately before up") {
    val s = new Session; idResponse(s)
    s.respond = (ex, args) => if (ex == "id") IO.pure(ok.copy(stdout = "0"))
      else if (ex == "sh" && args.exists(script => script.contains("managed_files") && script.contains("up -d"))) IO.pure(ok.copy(stdout = "STARTED"))
      else IO.pure(ok)
    val result = remote(s).start(connection, spec).unsafeRunSync()
    assertEquals(result.failureCode, None)
    val script = s.calls.find(_._1 == "sh").get._2(1)
    assert(script.indexOf("managed_files") < script.indexOf("config -q"))
    assert(script.indexOf("config -q") < script.indexOf("up -d"))
    assert(script.contains("sha256sum \"$d/.env\""))
    assert(script.contains("[A-Za-z0-9+/]"))
    assert(!s.calls.exists(_._2.contains("down")))
  }

  test("installation recovery proves exact managed files and local image, rejects env drift, and needs no container") {
    val s = new Session; idResponse(s)
    s.respond = (ex, args) => if (ex == "id") IO.pure(ok.copy(stdout = "0"))
      else if (ex == "sh" && args.exists(_.contains("docker image inspect"))) IO.pure(ok.copy(stdout = "1:1"))
      else IO.pure(ok)
    assert(remote(s).installationPresent(connection, spec).unsafeRunSync())
    val script = s.calls.find(_._1 == "sh").get._2(1)
    assert(script.contains("sha256sum \"$d/.env\""))
    assert(s.calls.exists(_._2.exists(_.contains("envSha256"))))
    assert(script.contains("'0:600:1'"))
    assert(!script.contains("docker inspect -f '{{.State.Running}}'"))
    s.respond = (ex, args) => if (ex == "id") IO.pure(ok.copy(stdout = "0"))
      else if (ex == "sh" && args.exists(_.contains("docker image inspect"))) IO.pure(ok.copy(stdout = "0:1"))
      else IO.pure(ok)
    assert(!remote(s).installationPresent(connection, spec).unsafeRunSync())
  }

  test("preflight returns a known blocker for unknown read-only probes and install requires Docker") {
    val s = new Session
    s.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("listeners=$(ss"))) ok.copy(stdout = "UNKNOWN")
      else ok
    }
    val unknown = remote(s).preflight(connection, resource, spec.nodePort).unsafeRunSync()
    assertEquals(unknown.failureCode, Some("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE"))
    assert(!unknown.uncertain)
    val script = s.calls.find(_._1 == "sh").get._2(1)
    assert(script.contains("command -v ss"))
    assert(script.contains("docker ps -a"))
    assert(script.contains("listeners=$(ss -H -ltn") && script.contains("|| echo_result UNKNOWN"))
    assert(script.contains("containers=$(docker ps -a --format") && script.contains("|| echo_result UNKNOWN"))
    assert(script.contains("/opt/remnawave /opt/remnawave-node"))
    assert(script.contains("echo_result FOREIGN"))
    assert(script.contains("for p in \"$root\"/*"))
    assert(!script.contains("[ \"$p\" = \"$root/$expectedNode\" ]"))
    assertEquals(s.calls.find(_._1 == "sh").get._2(4), "1")

    val install = new Session
    install.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("listeners=$(ss"))) ok.copy(stdout = "DOCKER")
      else ok
    }
    val rejected = remote(install).install(connection, spec, NodeInstallationData.fromSecretKey("secret")).unsafeRunSync()
    assertEquals(rejected.failureCode, Some("PROVISIONING_NODE_DOCKER_UNAVAILABLE"))
    assert(!rejected.uncertain)
    assert(install.uploads.isEmpty)
    assertEquals(install.calls.find(_._1 == "sh").get._2(4), "0")
  }

  test("preflight refuses simultaneous legacy and InfraDesk Remnawave roots") {
    val s = new Session
    s.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("listeners=$(ss"))) ok.copy(stdout = "FOREIGN")
      else ok
    }
    val result = remote(s).preflight(connection, resource, spec.nodePort).unsafeRunSync()
    assertEquals(result.failureCode, Some("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
    assert(!result.uncertain)
    val script = s.calls.find(_._1 == "sh").get._2(1)
    val legacyCheck = script.indexOf("/opt/remnawave /opt/remnawave-node")
    val managedRootCheck = script.indexOf("root=/opt/infradesk/remnawave")
    assert(legacyCheck >= 0 && managedRootCheck >= 0)
    assert(!script.contains("[ -d /opt/infradesk/remnawave ] ||"))
  }

  test("node firewall parser owns its namespace and leaves Stage25B and foreign rules foreign") {
    val legacy = s"ufw allow 443/tcp comment 'infradesk:$resource:web'"
    val own = s"ufw allow from 192.0.2.0/24 to any port 30222 proto tcp comment 'infradesk:remnawave:$resource:$node:node'"
    val parsed = SshRemnawaveNodeRemote.parseNodeRules(s"$legacy\n$own", resource, node).toOption.get
    assertEquals(parsed.size, 2)
    assert(!parsed.head.owned)
    assert(parsed.last.owned)
    assert(ProfileFirewall.parse(s"$legacy\n$own", resource).toOption.get.exists(_.owned))
  }

  test("configuring node access adds only its namespace rule and preserves Stage25B and foreign rules") {
    val s = new Session
    val stage25b = s"ufw allow 443/tcp comment 'infradesk:$resource:web'"
    s.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("SSH_CONNECTION"))) ok.copy(stdout = "192.0.2.5 45000 198.51.100.1 22")
      else if (ex == "ufw" && args == List("status")) ok.copy(stdout = "Status: active")
      else if (ex == "ufw" && args == List("show", "added")) ok.copy(stdout = s"$stage25b\nAdded user rules (see 'ufw status' for running firewall)")
      else ok
    }
    remote(s).configureFirewall(connection, spec).unsafeRunSync()
    val changes = s.calls.collect { case ("ufw", args) if args.headOption.exists(x => x == "allow" || x == "--force") => args }.toList
    assertEquals(changes, List(List("allow", "from", "192.0.2.0/24", "to", "any", "port", "30222", "proto", "tcp",
      "comment", s"infradesk:remnawave:$resource:$node:node")))
    val parsedBy25b = ProfileFirewall.parse(s"$stage25b\nufw allow from 192.0.2.0/24 to any port 30222 proto tcp comment 'infradesk:remnawave:$resource:$node:node'", resource).toOption.get
    assertEquals(parsedBy25b.count(_.owned), 1)
  }

  test("broad foreign management allow blocks node firewall changes") {
    val s = new Session
    s.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("SSH_CONNECTION"))) ok.copy(stdout = "192.0.2.5 45000 198.51.100.1 22")
      else if (ex == "ufw" && args == List("status")) ok.copy(stdout = "Status: active")
      else if (ex == "ufw" && args == List("show", "added")) ok.copy(stdout = "ufw allow 30222/tcp comment 'foreign'")
      else ok
    }
    val result = remote(s).configureFirewall(connection, spec).unsafeRunSync()
    assertEquals(result.failureCode, Some("PROVISIONING_FIREWALL_BROAD_RULE_PRESENT"))
    assert(!s.calls.exists { case ("ufw", args) => args.headOption.contains("allow"); case _ => false })
  }
}
