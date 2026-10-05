package ru.bitec.app.ops
package integration.ssh

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.integration._
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import support.NodeUpgradeFixtures

final class ManagedNodeImagesSpec extends FunSuite {
  private val previous = NodeUpgradeFixtures.previous
  private val target = NodeUpgradeFixtures.target
  private val platform = target.forPlatform("linux/amd64").get
  private val reference = s"${target.imageRepository}@${platform.manifestDigest}"
  private val spec = RemnawaveNodeRemoteSpec(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 30222, previous.imageReference, List("192.0.2.0/24"))
  private val connection = Connection(UUID.randomUUID(), UUID.randomUUID(), ConnectionScope.Organization, "SSH", "server", "ssh",
    ConnectionConfig(Map.empty), None, true, Instant.now(), Instant.now())
  private val ok = RemoteCommandOutput(0, "", "")
  private class Session extends RemoteConfigurationSession[IO] {
    val calls = ListBuffer.empty[(String, List[String])]
    val uploads = ListBuffer.empty[Array[Byte]]
    var observation = NodeUpgradeFixtures.image(previous)
    var stored = true
    var validationFails = false
    var interruptedPull = false
    var commits = 0
    def supportsAtomicReplace = IO.pure(true)
    def read(path: String, maxBytes: Int) = IO.pure(RemoteConfigurationFile.Missing)
    def create(path: String, bytes: Array[Byte], creation: RemoteFileCreation) = IO { uploads += bytes.clone(); () }
    def atomicReplace(from: String, to: String) = IO.unit
    def remove(path: String) = IO.unit
    def execute(ex: String, args: List[String], timeout: FiniteDuration) = executeCaptured(ex, args, timeout, 65536).map(_.exitCode)
    override def executeCaptured(ex: String, args: List[String], timeout: FiniteDuration, maxOutputBytes: Int): IO[RemoteCommandOutput] = IO {
      calls += ex -> args
      if (ex == "id") ok.copy(stdout = "0")
      else if (ex == "sh" && args.exists(_.contains("imageInfo="))) {
        val o = observation
        if (!o.managedFiles) ok.copy(stdout = "UNMANAGED\n") else ok.copy(stdout = List(o.configuredImage.get, o.actualImageId.get,
          o.repoDigests.mkString(","), s"${o.actualImageId.get}|${o.platform.get}|${o.imageBytes}",
          s"${o.containerId.get}|${o.containerCreatedAt.get}|${o.containerStartedAt.get}|${o.restartCount}", o.containerRunning.toString,
          if (o.portListening) "1" else "0", if (o.stable) "1" else "0", o.composeHash.get, o.markerHash.get, o.freeBytes.toString).mkString("\n") + "\n")
      } else if (ex == "docker" && args.take(2) == List("image", "inspect"))
        if (stored) ok.copy(stdout = s"${platform.configDigest}|linux/amd64|500000000") else ok.copy(exitCode = 1)
      else if (ex == "docker" && args.headOption.contains("pull")) { stored = true; if (interruptedPull) throw RemoteConfigurationFailure.Unavailable; ok }
      else if (ex == "docker" && args.contains("version")) ok.copy(stdout = "2.39.1")
      else if (ex == "docker" && args.contains("config")) if (validationFails) ok.copy(exitCode = 1, stderr = "fake-installation-secret") else ok
      else if (ex == "sh" && args.exists(_.contains("mv -fT"))) {
        commits += 1
        observation = observation.copy(configuredImage = Some(reference), composeHash = Some(ProfileManagedFiles.sha256(uploads.last)))
        ok
      } else ok
    }
    val remote = new SshManagedNodeImages(new RemoteConfigurationTransport[IO] {
      def withSession[A](c: Connection)(use: RemoteConfigurationSession[IO] => IO[A]) = use(Session.this)
    })
    def prefetch(authorize: IO[Unit] = IO.unit) = remote.prefetchImage(connection, spec, target, platform, observation, authorize)
    def switch(authorize: IO[Unit] = IO.unit) = remote.switchImage(connection, spec, reference, platform.configDigest, "a" * 64, "b" * 64, authorize)
    def upCount = calls.count { case (ex, args) => ex == "docker" && args.contains("up") }
  }
  test("prefetch pulls only exact platform digest and proves old container remains unchanged") {
    val s = new Session; s.stored = false
    s.prefetch().unsafeRunSync()
    assertEquals(s.calls.filter(_._2.headOption.contains("pull")).map(_._2).toList, List(List("pull", "--platform", "linux/amd64", reference)))
    assertEquals(s.upCount, 0); assertEquals(s.commits, 0); assert(s.uploads.isEmpty)
  }
  test("interrupted pull is reconciled by image store without replay") {
    val s = new Session; s.stored = false; s.interruptedPull = true
    s.prefetch().unsafeRunSync()
    assertEquals(s.calls.count(_._2.headOption.contains("pull")), 1)
    assertEquals(s.upCount, 0)
  }
  test("insufficient Docker filesystem space blocks pull and all mutations") {
    val s = new Session; s.stored = false; s.observation = s.observation.copy(freeBytes = 1)
    val result = s.prefetch().attempt.unsafeRunSync()
    assert(result.left.toOption.exists { case e: NodeImageRemoteFailure => e.code == "NODE_UPGRADE_DISK_INSUFFICIENT"; case _ => false })
    assert(!s.calls.exists(_._2.headOption.contains("pull"))); assertEquals(s.upCount, 0)
  }
  test("switch changes only controlled compose image, validates before commit and issues one up without pull or down") {
    val s = new Session
    s.switch().unsafeRunSync()
    val expected = SshRemnawaveNodeRemote.renderCompose(spec).replace(s"    image: ${spec.imageReference}\n", s"    image: $reference\n")
    assertEquals(s.uploads.size, 1)
    assertEquals(new String(s.uploads.head, StandardCharsets.UTF_8), expected)
    assertEquals(s.commits, 1); assertEquals(s.upCount, 1)
    assert(s.calls.indexWhere(_._2.contains("config")) < s.calls.indexWhere(_._2.exists(_.contains("mv -fT"))))
    val up = s.calls.find(_._2.contains("up")).get._2
    assert(up.contains("--no-deps") && up.contains("never") && up.last == "node")
    assert(!s.calls.exists(_._2.contains("down"))); assert(!s.calls.exists(_._2.contains("prune")))
  }
  test("invalid compose returns a constant code without diagnostics and performs zero commit/up") {
    val s = new Session; s.validationFails = true
    val result = s.switch().attempt.unsafeRunSync()
    assert(result.left.toOption.exists { case e: NodeImageRemoteFailure => e.code == "NODE_UPGRADE_COMPOSE_INVALID" && !e.getMessage.contains("fake-installation-secret"); case _ => false })
    assertEquals(s.commits, 0); assertEquals(s.upCount, 0)
  }
  test("lost admission before atomic compose commit preserves authorization failure and stops up") {
    val s = new Session
    var count = 0
    val denied = new NodeImageAuthorizationFailure("denied") {}
    val result = s.switch(IO { count += 1; if (count == 2) throw denied }).attempt.unsafeRunSync()
    assertEquals(result.left.toOption, Some(denied)); assertEquals(s.commits, 0); assertEquals(s.upCount, 0)
  }
  test("unmanaged files and compose drift block switch without staging") {
    List(false, true).foreach { drift =>
      val s = new Session
      s.observation = if (drift) s.observation.copy(composeHash = Some("f" * 64)) else s.observation.copy(managedFiles = false)
      assert(s.switch().attempt.unsafeRunSync().isLeft)
      assert(s.uploads.isEmpty); assertEquals(s.upCount, 0)
    }
  }
}
