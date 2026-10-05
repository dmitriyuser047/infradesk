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
    val remoteFiles = scala.collection.mutable.Map.empty[String, RemoteConfigurationFile]
    var respond: (String, List[String]) => IO[RemoteCommandOutput] = (_, _) => IO.pure(ok)
    var createFailure = false
    def supportsAtomicReplace = IO.pure(true)
    def read(path: String, maxBytes: Int) = IO.pure(remoteFiles.getOrElse(path, RemoteConfigurationFile.Missing))
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

  private def ownFiles(s: Session, env: String, compose: String = SshRemnawaveNodeRemote.renderCompose(spec)): Unit = {
    val envBytes = env.getBytes(StandardCharsets.UTF_8)
    val managed = SshRemnawaveNodeRemote.managedPrefix(spec) +
      ProfileManagedFiles.sha256(envBytes) + "\"}\n"
    def file(bytes: Array[Byte], mode: Int) = RemoteConfigurationFile(true, bytes,
      Some(RemoteFileMetadata(mode, 0, 0)))
    val dir = SshRemnawaveNodeRemote.directory(spec)
    s.remoteFiles.update(s"$dir/.env", file(envBytes, 384))
    s.remoteFiles.update(s"$dir/compose.yml", file(compose.getBytes(StandardCharsets.UTF_8), 420))
    s.remoteFiles.update(s"$dir/managed.json", file(managed.getBytes(StandardCharsets.UTF_8), 420))
  }

  private def recoveryOwned(s: Session): Unit = s.respond = (ex, args) => IO.pure {
    if (ex == "id") ok.copy(stdout = "0")
    else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) ok.copy(stdout = "OWNED")
    else if (ex == "sh" && args.exists(_.contains("listeners=$(ss"))) ok.copy(stdout = "CLEAR")
    else if (ex == "docker" && args.headOption.contains("image")) ok.copy(stdout = "node-image-id")
    else ok
  }

  test("recovery preflight accepts only absent or proven owned local installations") {
    val absent = new Session
    absent.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) ok.copy(stdout = "ABSENT")
      else ok
    }
    assertEquals(remote(absent).recoveryPreflight(connection, spec).unsafeRunSync().failureCode, None)
    val probeArgs = absent.calls.find(_._1 == "sh").get._2
    val probe = probeArgs.mkString(" ")
    assert(probe.contains(s"run=${spec.onboardingId}"))
    assert(probe.contains(s"node=${spec.externalNodeId}"))
    assert(probe.contains("com.docker.compose.project.config_files"))
    assert(!probe.contains("rm -rf") && !probe.contains("docker compose down"))

    val owned = new Session
    recoveryOwned(owned)
    assertEquals(remote(owned).recoveryPreflight(connection, spec).unsafeRunSync().facts,
      Map("installation" -> "owned"))
    val foreign = new Session
    foreign.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) ok.copy(stdout = "FOREIGN")
      else ok
    }
    assertEquals(remote(foreign).recoveryPreflight(connection, spec).unsafeRunSync().failureCode,
      Some("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
  }

  test("repair installs absent state, reuses exact state, and CAS-repairs damaged owned files") {
    val secret = Base64.getEncoder.encodeToString("new-secret".getBytes(StandardCharsets.UTF_8))
    val absent = new Session
    absent.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) ok.copy(stdout = "ABSENT")
      else if (ex == "sh" && args.exists(_.contains("listeners=$(ss"))) ok.copy(stdout = "CLEAR")
      else ok
    }
    assertEquals(remote(absent).repair(connection, spec, NodeInstallationData.fromSecretKey(secret)).unsafeRunSync().failureCode, None)
    assertEquals(absent.uploads.size, 3)
    assert(!absent.calls.exists(_._2.exists(_.contains("up -d"))))

    val exact = new Session
    recoveryOwned(exact)
    val expectedEnv = s"SECRET_KEY=$secret\nNODE_PORT=${spec.nodePort}\n"
    ownFiles(exact, expectedEnv)
    assertEquals(remote(exact).repair(connection, spec, NodeInstallationData.fromSecretKey(secret)).unsafeRunSync().failureCode, None)
    assert(exact.uploads.isEmpty)
    assert(!exact.calls.exists(_._2.headOption.contains("pull")))

    val damaged = new Session
    recoveryOwned(damaged)
    val oldEnv = s"SECRET_KEY=$secret\nNODE_PORT=30223\n"
    ownFiles(damaged, oldEnv, SshRemnawaveNodeRemote.renderCompose(spec).replace("30222", "30223"))
    assertEquals(remote(damaged).repair(connection, spec, NodeInstallationData.fromSecretKey(secret)).unsafeRunSync().failureCode, None)
    assertEquals(damaged.uploads.size, 3)
    val envHash = ProfileManagedFiles.sha256(oldEnv.getBytes(StandardCharsets.UTF_8))
    val commits = damaged.calls.filter { case (ex, args) => ex == "sh" && args.exists(_ == ProfileManagedFiles.Commit) }
    assert(commits.exists(_._2.contains(envHash)), "repair must pass the observed file hash as the CAS expectation")
    assert(damaged.calls.exists { case (ex, args) =>
      ex=="docker" && args.headOption.contains("compose") && args.takeRight(2)==List("config","-q")
    })
  }

  test("repair rejects foreign or unsafe owned files before upload") {
    val foreign = new Session
    foreign.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) ok.copy(stdout = "FOREIGN")
      else ok
    }
    assertEquals(remote(foreign).repair(connection, spec, NodeInstallationData.fromSecretKey("c2VjcmV0"))
      .unsafeRunSync().failureCode, Some("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
    assert(foreign.uploads.isEmpty)

    val unsafe = new Session
    recoveryOwned(unsafe)
    ownFiles(unsafe, "SECRET_KEY=c2VjcmV0\nNODE_PORT=30222\n")
    val path = s"${SshRemnawaveNodeRemote.directory(spec)}/.env"
    unsafe.remoteFiles.update(path, unsafe.remoteFiles(path).copy(metadata = Some(RemoteFileMetadata(420, 501, 0))))
    assertEquals(remote(unsafe).repair(connection, spec, NodeInstallationData.fromSecretKey("c2VjcmV0"))
      .unsafeRunSync().failureCode, Some("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
    assert(unsafe.uploads.isEmpty)
  }

  test("retirement is idempotent for absence and removes only exact owned paths after proof") {
    val absent = new Session
    absent.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("recovery_probe()"))) ok.copy(stdout = "ABSENT")
      else ok
    }
    assertEquals(remote(absent).retireInstallation(connection, spec).unsafeRunSync().failureCode, None)
    assertEquals(absent.calls.find(_._1 == "sh").get._2.last,spec.nodePort.toString)

    val owned = new Session
    owned.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("recovery_probe()"))) ok.copy(stdout = "RETIRED")
      else ok
    }
    val retired = remote(owned).retireInstallation(connection, spec).unsafeRunSync()
    assertEquals(retired.failureCode, None)
    val script = owned.calls.find(_._1 == "sh").get._2(1)
    assert(script.contains("docker stop --time 15 \"$name\""))
    assert(script.contains("docker rm \"$name\""))
    assert(script.contains("rm -f -- \"$d/compose.yml\" \"$d/.env\" \"$d/managed.json\""))
    assert(script.contains("rmdir -- \"$d\""))
    assert(script.contains("for f in \"$d\"/* \"$d\"/.[!.]* \"$d\"/..?*"))
    assert(!script.contains("rm -rf") && !script.contains("$d/*"))
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
    var added = List(stage25b)
    s.respond = (ex, args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("SSH_CONNECTION"))) ok.copy(stdout = "192.0.2.5 45000 198.51.100.1 22")
      else if (ex == "ufw" && args == List("status")) ok.copy(stdout = "Status: active")
      else if (ex == "ufw" && args == List("show", "added")) ok.copy(stdout = ("Added user rules (see 'ufw status' for running firewall):" :: added).mkString("\n"))
      else if (ex == "ufw" && args.headOption.contains("allow")) {
        added :+= s"ufw allow from ${args(2)} to any port ${args(6)} proto ${args(8)} comment '${args(10)}'"; ok
      }
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

  private val header = "Added user rules (see 'ufw status' for running firewall):"
  private val liveSpec = spec.copy(nodePort = 2222, panelCidrs = List("2.27.26.18/32"))
  private val liveRules = List(
    s"ufw allow from 172.28.0.0/24 to any port 22 proto tcp comment 'infradesk:$resource:infradesk-ssh'",
    s"ufw allow from 77.40.38.175 to any port 22 proto tcp comment 'infradesk:$resource:admin-ssh'",
    s"ufw allow from 77.40.38.175 to any port 8080 proto tcp comment 'infradesk:$resource:infradesk-web-admin'")
  private val liveNodeRule = s"ufw allow from 2.27.26.18 to any port 2222 proto tcp comment 'infradesk:remnawave:$resource:$node:node'"
  private def firewallSession(initial: List[String], persist: Boolean = true): Session = {
    val s = new Session
    var rules = initial
    s.respond = (ex,args) => IO.pure {
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("SSH_CONNECTION"))) ok.copy(stdout = "172.28.0.5 45000 198.51.100.1 22")
      else if (ex == "ufw" && args == List("status")) ok.copy(stdout = "Status: active\nDefault: deny incoming")
      else if (ex == "ufw" && args == List("show", "added")) ok.copy(stdout = (header :: rules).mkString("\n"))
      else if (ex == "ufw" && args.headOption.contains("allow")) { if (persist) rules :+= liveNodeRule; ok }
      else if (ex == "ufw" && args.take(3) == List("--force","delete","allow")) {
        rules = rules.filterNot(line => line.contains(s"from ${args(4)} ") && line.endsWith(s"'${args(12)}'")); ok
      }
      else ok
    }
    s
  }
  private def mutations(s: Session) = s.calls.collect {
    case ("ufw",args) if args != List("status") && args != List("show","added") => args
  }.toList

  test("live UFW 0.36.2 preserves all three Server Profile rules, verifies node access, and converges without duplicates") {
    val s = firewallSession(liveRules)
    val first = remote(s).configureOnboardingFirewall(connection,liveSpec).unsafeRunSync()
    assertEquals(first.failureCode,None)
    assert(!first.uncertain)
    assertEquals(mutations(s),List(List("allow","from","2.27.26.18/32","to","any","port","2222","proto","tcp",
      "comment",s"infradesk:remnawave:$resource:$node:node")))
    assertEquals(remote(s).configureOnboardingFirewall(connection,liveSpec).unsafeRunSync().failureCode,None)
    assertEquals(mutations(s).size,1)
    assert(remote(s).firewallPresent(connection,liveSpec).unsafeRunSync())
    val parsed = SshRemnawaveNodeRemote.parseNodeRules((header :: (liveRules :+ liveNodeRule)).mkString("\n"),resource,node).toOption.get
    assertEquals(parsed.count(_.owned),1)
    assertEquals(ProfileFirewall.parse((header :: (liveRules :+ liveNodeRule)).mkString("\n"),resource).toOption.get.count(_.owned),3)
  }

  test("existing exact node rule is idempotent and canonical bare IPv4 equals /32") {
    val s = firewallSession(liveRules :+ liveNodeRule)
    assertEquals(remote(s).configureOnboardingFirewall(connection,liveSpec).unsafeRunSync().failureCode,None)
    assertEquals(mutations(s),Nil)
    val bare = SshRemnawaveNodeRemote.parseNodeRules(s"$header\n$liveNodeRule",resource,node)
    val cidr = SshRemnawaveNodeRemote.parseNodeRules(s"$header\n${liveNodeRule.replace("2.27.26.18", "2.27.26.18/32")}",resource,node)
    assertEquals(bare,cidr)
  }

  test("conflicting own port, source, protocol, action or suffix fails before any mutation") {
    List(liveNodeRule.replace("2222","2223"),liveNodeRule.replace("2.27.26.18","2.27.26.19"),
      liveNodeRule.replace("proto tcp","proto udp"),liveNodeRule.replace("ufw allow","ufw deny"),
      liveNodeRule.replace(":node'",":other'")).foreach { rule =>
      val s = firewallSession(liveRules :+ rule)
      val result = remote(s).configureOnboardingFirewall(connection,liveSpec).unsafeRunSync()
      assertEquals(result.failureCode,Some("PROVISIONING_FIREWALL_OWNERSHIP_CONFLICT"))
      assert(!result.uncertain)
      assertEquals(mutations(s),Nil)
    }
  }

  test("unknown UFW syntax and mixed None output remain fail closed") {
    List("ufw limit 22/tcp","unexpected data","(None)\n" + liveNodeRule).foreach { line =>
      val s = firewallSession(liveRules :+ line)
      assertEquals(remote(s).configureOnboardingFirewall(connection,liveSpec).unsafeRunSync().failureCode,Some("FIREWALL_RULE_UNSUPPORTED"))
      assertEquals(mutations(s),Nil)
    }
    assertEquals(SshRemnawaveNodeRemote.parseNodeRules(s"$header\n(None)",resource,node),Right(Nil))
    assertEquals(SshRemnawaveNodeRemote.parseNodeRules(header.stripSuffix(":"),resource,node),Right(Nil))
  }

  test("successful UFW reply without the exact re-observed rule cannot report success") {
    val s = firewallSession(liveRules,persist=false)
    val result = remote(s).configureOnboardingFirewall(connection,liveSpec).unsafeRunSync()
    assertEquals(result.failureCode,Some("PROVISIONING_FIREWALL_VERIFICATION_FAILED"))
    assert(result.uncertain)
    assertEquals(mutations(s).size,1)
  }

  test("the existing fleet reconciliation still replaces approved node sources and preserves Server Profile rules") {
    val s = firewallSession(liveRules :+ liveNodeRule.replace("2.27.26.18","2.27.26.19/32"))
    val result = remote(s).configureFirewall(connection,liveSpec).unsafeRunSync()
    assertEquals(result.failureCode,None)
    assert(remote(s).firewallPresent(connection,liveSpec).unsafeRunSync())
    assertEquals(mutations(s).size,2)
    assertEquals(mutations(s).last,List("--force","delete","allow","from","2.27.26.19/32","to","any","port","2222","proto","tcp",
      "comment",s"infradesk:remnawave:$resource:$node:node"))
  }
}
