package ru.bitec.app.ops
package integration.ssh

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.integration.{LocalInstallationState, LocalInstallationDiagnosis, NodeInstallationData}
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
    var claimed = false
    var failAfterPreparation = false
    var failAfterCommit = 0
    var commits = 0
    def supportsAtomicReplace = IO.pure(true)
    def read(path: String, maxBytes: Int) = IO {
      val file = remoteFiles.getOrElse(path, RemoteConfigurationFile.Missing)
      if (file.bytes.length > maxBytes) throw RemoteConfigurationFailure.FileTooLarge
      file
    }
    def create(path: String, bytes: Array[Byte], creation: RemoteFileCreation) = IO {
      uploads += ((path, bytes.clone(), creation)); if (createFailure) throw RemoteConfigurationFailure.Unavailable
    }
    def atomicReplace(from: String, to: String) = IO.unit
    def remove(path: String) = IO.unit
    def execute(executable: String, args: List[String], timeout: FiniteDuration) = executeCaptured(executable, args, timeout, 65536).map(_.exitCode)
    override def executeCaptured(executable: String, args: List[String], timeout: FiniteDuration, maxOutputBytes: Int) =
      IO { calls += executable -> args } *> IO.defer(respond(executable, args)).flatMap { result => IO {
        if (executable == "sh" && args.exists(_.contains("atomic ownership publication")) && result.stdout == "PREPARED") {
          claimed = true
          if (failAfterPreparation) { failAfterPreparation = false; throw RemoteConfigurationFailure.Unavailable }
        }
        if (executable == "sh" && args.exists(_.contains("stageInode=$(stat -c '%i'")) && result.stdout == "PUBLISHED") {
          val finalDir = SshRemnawaveNodeRemote.directory(spec)
          val stageDir = SshRemnawaveNodeRemote.stagingDirectory(spec)
          List(".env", "compose.yml", "managed.json").foreach { name =>
            remoteFiles.get(s"$stageDir/$name").foreach(file => remoteFiles.update(s"$finalDir/$name", file))
            remoteFiles.remove(s"$stageDir/$name")
          }
        }
        if (executable == "sh" && args.contains(ProfileManagedFiles.Commit) && result.exitCode == 0) {
          val bytes = uploads.reverseIterator.find(u => ProfileManagedFiles.sha256(u._2) == args.last).get._2
          val path = args(3)
          remoteFiles.update(path, RemoteConfigurationFile(true, bytes.clone(),
            Some(RemoteFileMetadata(if (path.endsWith("/.env")) 384 else 420, 0, 0))))
          commits += 1
          if (commits == failAfterCommit) throw RemoteConfigurationFailure.Unavailable
        }
        result
      }}
  }
  private def remote(s: Session) = new SshRemnawaveNodeRemote(new RemoteConfigurationTransport[IO] {
    def withSession[A](c: Connection)(use: RemoteConfigurationSession[IO] => IO[A]) = use(s)
  })
  private def idResponse(s: Session): Unit = s.respond = (ex, args) => IO.pure {
    if (ex == "id") ok.copy(stdout = "0")
    else if (ex == "sh" && args.exists(_.contains("stageInode=$(stat -c '%i'"))) ok.copy(stdout = "PUBLISHED")
    else if (ex == "sh" && args.exists(_.contains("atomic ownership publication"))) ok.copy(stdout = "PREPARED")
    else if (ex == "sh" && args.exists(_.contains("result CLEAN"))) ok.copy(stdout = "CLEAN")
    else if (ex == "sh" && args.exists(_.contains("printf PRESENT || printf FOREIGN"))) {
      val dir = SshRemnawaveNodeRemote.directory(spec)
      ok.copy(stdout = if (s.remoteFiles.keys.exists(_.startsWith(dir + "/"))) "PRESENT" else "ABSENT")
    }
    else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) {
      val dir = SshRemnawaveNodeRemote.directory(spec)
      val complete = List(".env", "compose.yml", "managed.json").forall(n => s.remoteFiles.contains(s"$dir/$n"))
      ok.copy(stdout = if (complete) "OWNED_COMPLETE" else if (s.claimed) "OWNED_PARTIAL" else "ABSENT")
    }
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

  test("local installation classifier returns typed state from its exact recovery probe") {
    val absent = new Session; idResponse(absent)
    assertEquals(remote(absent).localInstallationState(connection, spec).unsafeRunSync(), LocalInstallationState.Absent)
    assertEquals(absent.calls.count(_._1 == "id"), 1)
    assert(absent.calls.exists { case ("sh", args) => args.exists(_.contains("OWNED_DAMAGED")); case _ => false })

    val unknown = new Session
    unknown.respond = (ex, args) => IO.pure(if (ex == "id") ok.copy(stdout = "0") else ok.copy(stdout = "unexpected"))
    assertEquals(remote(unknown).localInstallationState(connection, spec).unsafeRunSync(), LocalInstallationState.Unknown)
  }

  test("diagnosis is read-only, closed and distinguishes local probe from firewall uncertainty") {
    LocalInstallationDiagnosis.all.foreach { diagnosis =>
      val s=new Session; idResponse(s)
      val original=s.respond
      s.respond=(ex,args) => if(args.exists(_.contains("markerOwned=0"))) IO.pure(ok.copy(stdout="UNKNOWN:"+diagnosis.code)) else original(ex,args)
      val result=remote(s).localInstallationObservation(connection,spec).unsafeRunSync()
      assertEquals(result.state,LocalInstallationState.Unknown)
      assertEquals(result.diagnosis,Some(diagnosis))
      assertEquals(s.uploads.size,0)
      assert(!s.calls.exists(_._1=="docker"))
    }
    val s=new Session; idResponse(s)
    assertEquals(remote(s).localInstallationObservation(connection,spec).unsafeRunSync().state,LocalInstallationState.Absent)
    val original=s.respond
    s.respond=(ex,args) => if(ex=="ufw") IO.pure(ok.copy(exitCode=1,stderr="secret stderr")) else original(ex,args)
    val unknown=remote(s).localInstallationObservation(connection,spec).unsafeRunSync()
    assertEquals(unknown.diagnosis,Some(LocalInstallationDiagnosis.FirewallStateUnknown))
    assertEquals(s.uploads.size,0)
  }

  test("staging paths are narrowly allowlisted and final node paths remain canonical") {
    val stage = SshRemnawaveNodeRemote.stagingDirectory(spec)
    assert(ProfileManagedFiles.allowedPath(s"$stage/.env"))
    assert(ProfileManagedFiles.allowedPath(s"$stage/compose.yml"))
    assert(ProfileManagedFiles.allowedPath(s"$stage/managed.json"))
    assert(!ProfileManagedFiles.allowedPath(s"$stage/secret.txt"))
    assert(!ProfileManagedFiles.allowedPath(stage + "-other/.env"))
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
    assertEquals(validations.size, 2)
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

  test("deterministic candidate rejection after owner and env is FAILED with fresh OWNED_PARTIAL proof") {
    val s = new Session; idResponse(s)
    val base = s.respond
    s.respond = (ex,args) => if(ex=="docker" && args.contains("config")) IO.pure(ok.copy(exitCode=1)) else base(ex,args)
    val result = remote(s).install(connection,spec,NodeInstallationData.fromSecretKey("dGVzdC1zZWNyZXQ=")).unsafeRunSync()
    assertEquals(result.failureCode,Some("PROVISIONING_NODE_COMPOSE_INVALID"))
    assert(!result.uncertain)
    assertEquals(result.facts,Map("localInstallationState" -> "OWNED_PARTIAL"))
    assert(s.claimed)
    assert(s.remoteFiles.contains(SshRemnawaveNodeRemote.stagingDirectory(spec)+"/.env"))
    assert(!s.remoteFiles.keys.exists(_.endsWith("/compose.yml")))
    assert(!s.calls.exists(_._2.contains("pull")))
    val validation = s.calls.find(c => c._1=="docker" && c._2.contains("config")).get._2
    assertEquals(validation(validation.indexOf("--project-directory")+1),SshRemnawaveNodeRemote.stagingDirectory(spec))
  }

  test("a deterministic pull rejection retains a proven published installation as a known failure") {
    val s = new Session; idResponse(s)
    val base = s.respond
    s.respond = (ex,args) => if(ex=="docker" && args.headOption.contains("pull")) IO.pure(ok.copy(exitCode=1)) else base(ex,args)
    val result = remote(s).install(connection,spec,NodeInstallationData.fromSecretKey("dGVzdA==")).unsafeRunSync()
    assertEquals(result.failureCode,Some("PROVISIONING_NODE_REMOTE_COMMAND_FAILED"))
    assert(!result.uncertain)
    assertEquals(result.facts,Map("localInstallationState" -> "OWNED_COMPLETE"))
    assert(s.remoteFiles.contains(SshRemnawaveNodeRemote.directory(spec)+"/managed.json"))
    assert(!s.calls.exists(_._2.contains("up")))
  }

  test("repair of reviewed image evolution preserves its controlled image line while replacing damaged credentials") {
    val s=new Session; idResponse(s)
    val dir=SshRemnawaveNodeRemote.directory(spec)
    val target=domain.integration.RemnawaveNodeReleaseCatalog.find("node-2.8.0").get.imageReference
    val compose=SshRemnawaveNodeRemote.renderCompose(spec).replace(s"    image: $image\n",s"    image: $target\n").getBytes(StandardCharsets.UTF_8)
    s.remoteFiles.update(s"$dir/compose.yml",RemoteConfigurationFile(true,compose,Some(RemoteFileMetadata(420,0,0))))
    val base=s.respond
    s.respond=(ex,args) => if(ex=="sh" && args.exists(_.contains("markerOwned=0")) &&
      !args.exists(a => a.contains("atomic ownership publication") || a.contains("result CLEAN")))
      IO.pure(ok.copy(stdout="OWNED_DAMAGED")) else base(ex,args)
    val result=remote(s).repair(connection,spec,NodeInstallationData.fromSecretKey("dGVzdA==")).unsafeRunSync()
    assertEquals(result.failureCode,None)
    assert(s.remoteFiles(s"$dir/compose.yml").bytes.sameElements(compose))
    assert(!s.uploads.exists(u => new String(u._2,StandardCharsets.UTF_8).contains(s"    image: $image\n")))
    assert(s.calls.exists { case ("docker",args) => args==List("pull",target); case _ => false })
    assert(!s.calls.exists { case ("docker",args) => args==List("pull",image); case _ => false })
  }

  test("candidate failure with unavailable reconciliation remains UNKNOWN; disconnect and truncation never downgrade") {
    List("unreadable","disconnect","truncated").foreach { boundary =>
      val s = new Session; idResponse(s)
      val base = s.respond
      var rejected = false
      s.respond = (ex,args) => if(ex=="docker" && args.contains("config")) {
        rejected=true
        if(boundary=="disconnect") IO.raiseError(RemoteConfigurationFailure.Unavailable)
        else IO.pure(ok.copy(exitCode=1,stdoutTruncated=boundary=="truncated"))
      } else if(rejected && ex=="sh" && args.exists(_.contains("markerOwned=0"))) IO.raiseError(RemoteConfigurationFailure.Unavailable)
      else base(ex,args)
      val result = remote(s).install(connection,spec,NodeInstallationData.fromSecretKey("dGVzdA==")).unsafeRunSync()
      assert(result.uncertain,boundary)
      assert(!s.calls.exists(_._2.contains("pull")))
    }
  }

  test("controlled image normalization rejects unreviewed, duplicate and unrelated edits") {
    val text = SshRemnawaveNodeRemote.renderCompose(spec)
    val target = domain.integration.RemnawaveNodeReleaseCatalog.find("node-3.4.1").get.imageReference
    val changed = text.replace(s"    image: $image\n",s"    image: $target\n")
    assertEquals(SshRemnawaveNodeRemote.controlledComposeReference(spec,changed.getBytes(StandardCharsets.UTF_8)),Some(target))
    List(changed.replace("network_mode: host","network_mode: bridge"),changed+s"    image: $target\n",
      changed.replace(target,"unreviewed/node:latest")).foreach { invalid =>
      assertEquals(SshRemnawaveNodeRemote.controlledComposeReference(spec,invalid.getBytes(StandardCharsets.UTF_8)),None)
    }
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
      else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) IO.pure(ok.copy(stdout = "OWNED_COMPLETE"))
      else if (ex == "sh" && args.exists(_.contains("docker image inspect"))) IO.pure(ok.copy(stdout = "1:1"))
      else IO.pure(ok)
    assert(remote(s).installationPresent(connection, spec).unsafeRunSync())
    val script = s.calls.find(_._2.exists(_.contains("docker image inspect"))).get._2(1)
    assert(script.contains("sha256sum \"$d/.env\""))
    assert(s.calls.exists(_._2.exists(_.contains("envSha256"))))
    assert(script.contains("'0:600:1'"))
    assert(!script.contains("docker inspect -f '{{.State.Running}}'"))
    s.respond = (ex, args) => if (ex == "id") IO.pure(ok.copy(stdout = "0"))
      else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) IO.pure(ok.copy(stdout = "OWNED_COMPLETE"))
      else if (ex == "sh" && args.exists(_.contains("docker image inspect"))) IO.pure(ok.copy(stdout = "0:1"))
      else IO.pure(ok)
    assert(!remote(s).installationPresent(connection, spec).unsafeRunSync())
  }

  test("partial installation at each durable file boundary repairs once without starting a duplicate container or exposing secrets") {
    val secrets = List("cGFydGlhbC1yZWNvdmVyeS1zZWNyZXQ=",
      Base64.getEncoder.encodeToString(Array.fill[Byte](49152)(1)))
    val dir = SshRemnawaveNodeRemote.directory(spec)
    for (boundary <- List(0, 1, 2); secret <- secrets) {
      val s = new Session
      var imagePresent = false
      s.respond = (ex, args) => IO {
        if (ex == "id") ok.copy(stdout = "0")
        else if (ex == "sh" && args.exists(_.contains("stageInode=$(stat -c '%i'"))) ok.copy(stdout = "PUBLISHED")
        else if (ex == "sh" && args.exists(_.contains("atomic ownership publication"))) ok.copy(stdout = "PREPARED")
        else if (ex == "sh" && args.exists(_.contains("result CLEAN"))) ok.copy(stdout = "CLEAN")
        else if (ex == "sh" && args.exists(_.contains("printf PRESENT || printf FOREIGN"))) ok.copy(stdout = if (s.remoteFiles.keys.exists(_.startsWith(dir + "/"))) "PRESENT" else "ABSENT")
        else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) {
          val complete = List(".env", "compose.yml", "managed.json").forall(n => s.remoteFiles.contains(s"$dir/$n"))
          ok.copy(stdout = if (complete) "OWNED_COMPLETE" else if (s.claimed) "OWNED_PARTIAL" else "ABSENT")
        }
        else if (ex == "sh" && args.exists(_.contains("listeners=$(ss"))) ok.copy(stdout = "CLEAR")
        else if (ex == "sh" && args.exists(_.contains("docker image inspect"))) ok.copy(stdout = "1:1")
        else if (ex == "docker" && args.headOption.contains("image"))
          ok.copy(exitCode = if (imagePresent) 0 else 1, stdout = if (imagePresent) "image-id" else "")
        else if (ex == "docker" && args.headOption.contains("pull")) { imagePresent = true; ok }
        else ok
      }
      s.failAfterPreparation = boundary == 0
      s.failAfterCommit = boundary
      val failed = remote(s).install(connection, spec, NodeInstallationData.fromSecretKey(secret)).unsafeRunSync()
      assert(failed.failureCode.nonEmpty && failed.uncertain, s"boundary $boundary")
      assertEquals(remote(s).recoveryPreflight(connection, spec).unsafeRunSync().facts,
        Map("installation" -> "owned_partial"))
      val repaired = remote(s).repair(connection, spec, NodeInstallationData.fromSecretKey(secret)).unsafeRunSync()
      assertEquals(repaired.failureCode, None)
      val uploadCount = s.uploads.size
      val commitCount = s.commits
      val pullCount = s.calls.count(_._2.headOption.contains("pull"))
      assertEquals(remote(s).repair(connection, spec, NodeInstallationData.fromSecretKey(secret)).unsafeRunSync().failureCode, None)
      assertEquals(s.uploads.size, uploadCount)
      assertEquals(s.commits, commitCount)
      assertEquals(s.calls.count(_._2.headOption.contains("pull")), pullCount)
      assertEquals(remote(s).recoveryPreflight(connection, spec).unsafeRunSync().facts,
        Map("installation" -> "owned_complete"))
      assert(remote(s).installationPresent(connection, spec).unsafeRunSync())
      assert(!s.calls.exists(_._2.exists(_.contains("up -d"))))
      assert(s.calls.forall { case (_, args) => !args.exists(_.contains(secret)) })
      assert(!failed.toString.contains(secret) && !repaired.toString.contains(secret))
      val firstPrepare = s.calls.indexWhere(_._2.exists(_.contains("atomic ownership publication")))
      val firstFilePrepare = s.calls.indexWhere(_._2.contains(ProfileManagedFiles.Prepare))
      assert(firstPrepare >= 0 && firstPrepare < firstFilePrepare)
      assert(s.calls(firstPrepare)._2.contains(SshRemnawaveNodeRemote.ownershipDirectory(spec)))
    }
  }

  test("owned partial state is not installationPresent even when the file/image proof would pass") {
    val s = new Session
    recoveryOwned(s)
    assert(!remote(s).installationPresent(connection, spec).unsafeRunSync())
    assert(!s.calls.exists(_._2.exists(_.contains("docker image inspect"))))
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
    else if (ex == "sh" && args.exists(_.contains("stageInode=$(stat -c '%i'"))) ok.copy(stdout = "PUBLISHED")
    else if (ex == "sh" && args.exists(_.contains("atomic ownership publication"))) ok.copy(stdout = "PREPARED")
    else if (ex == "sh" && args.exists(_.contains("result CLEAN"))) ok.copy(stdout = "CLEAN")
    else if (ex == "sh" && args.exists(_.contains("printf PRESENT || printf FOREIGN"))) {
      val dir = SshRemnawaveNodeRemote.directory(spec)
      ok.copy(stdout = if (s.remoteFiles.keys.exists(_.startsWith(dir + "/"))) "PRESENT" else "ABSENT")
    }
    else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) ok.copy(stdout = "OWNED_PARTIAL")
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
      Map("installation" -> "owned_partial"))
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
      else if (ex == "sh" && args.exists(_.contains("stageInode=$(stat -c '%i'"))) ok.copy(stdout = "PUBLISHED")
      else if (ex == "sh" && args.exists(_.contains("atomic ownership publication"))) ok.copy(stdout = "PREPARED")
      else if (ex == "sh" && args.exists(_.contains("result CLEAN"))) ok.copy(stdout = "CLEAN")
      else if (ex == "sh" && args.exists(_.contains("markerOwned=0"))) ok.copy(stdout = if (absent.claimed) "OWNED_PARTIAL" else "ABSENT")
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
    assertEquals(damaged.uploads.size, 2)
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
    assertEquals(absent.calls.find(_._1 == "sh").get._2.takeRight(4).head,spec.nodePort.toString)

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
    assert(script.contains("for file in \"$d/compose.yml\" \"$d/.env\" \"$d/managed.json\""))
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
        val source = args(4).stripSuffix("/32")
        rules = rules.filterNot(line => (line.contains(s"from $source ") || line.contains(s"from $source/32 ")) && line.endsWith(s"'${args(12)}'")); ok
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

  test("retire firewall removes only the exact approved own node rule and preserves foreign rules") {
    val foreign = "ufw allow 8080/tcp comment 'foreign-service'"
    val s = firewallSession(liveRules ++ List(foreign, liveNodeRule))
    val result = remote(s).retireFirewall(connection, liveSpec).unsafeRunSync()
    assertEquals(result.failureCode, None)
    assert(!result.uncertain)
    assertEquals(mutations(s), List(List("--force", "delete", "allow", "from", "2.27.26.18/32", "to", "any",
      "port", "2222", "proto", "tcp", "comment", s"infradesk:remnawave:$resource:$node:node")))
    assert(!s.calls.exists { case (ex, args) => ex == "ufw" && args.take(3) == List("--force", "delete", "allow") && args(4) == "77.40.38.175" })
  }

  test("retire firewall is idempotent and refuses mismatched own rules before mutation") {
    val absent = firewallSession(liveRules)
    assertEquals(remote(absent).retireFirewall(connection, liveSpec).unsafeRunSync().failureCode, None)
    assertEquals(mutations(absent), Nil)

    val conflict = firewallSession(liveRules :+ liveNodeRule.replace("2222", "2223"))
    val rejected = remote(conflict).retireFirewall(connection, liveSpec).unsafeRunSync()
    assertEquals(rejected.failureCode, Some("PROVISIONING_FIREWALL_OWNERSHIP_CONFLICT"))
    assert(!rejected.uncertain)
    assertEquals(mutations(conflict), Nil)

    val equivalentForeign = liveNodeRule.replace("infradesk:remnawave:", "foreign:")
    val collision = firewallSession(liveRules ++ List(liveNodeRule, equivalentForeign))
    assertEquals(remote(collision).retireFirewall(connection, liveSpec).unsafeRunSync().failureCode,
      Some("PROVISIONING_FIREWALL_OWNERSHIP_CONFLICT"))
    assertEquals(mutations(collision), Nil)
  }

  test("retire firewall reports uncertainty when the pre-mutation SSH canary fails") {
    val s = firewallSession(liveRules :+ liveNodeRule)
    var ids = 0
    val base = s.respond
    s.respond = (ex, args) => if (ex == "id") IO {
      ids += 1
      if (ids >= 2) ok.copy(exitCode = 1) else ok.copy(stdout = "0")
    } else base(ex, args)
    val result = remote(s).retireFirewall(connection, liveSpec).unsafeRunSync()
    assertEquals(result.failureCode, Some("PROVISIONING_FIREWALL_CANARY_FAILED"))
    assert(result.uncertain)
    assertEquals(mutations(s), Nil)
  }

  test("retire firewall protects the live SSH path and requires an independent remaining allow") {
    val sshPortSpec = liveSpec.copy(nodePort = 22, panelCidrs = List("172.28.0.0/24"))
    val onlyNodeAllow = s"ufw allow from 172.28.0.0/24 to any port 22 proto tcp comment 'infradesk:remnawave:$resource:$node:node'"
    val blocked = firewallSession(List(onlyNodeAllow))
    val unsafe = remote(blocked).retireFirewall(connection, sshPortSpec).unsafeRunSync()
    assertEquals(unsafe.failureCode, Some("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
    assertEquals(mutations(blocked), Nil)

    val alternate = "ufw allow from 172.28.0.5 to any port 22 proto tcp comment 'foreign-admin-ssh'"
    val safe = firewallSession(List(alternate, onlyNodeAllow))
    assertEquals(remote(safe).retireFirewall(connection, sshPortSpec).unsafeRunSync().failureCode, None)
    assertEquals(mutations(safe).size, 1)
  }
}
