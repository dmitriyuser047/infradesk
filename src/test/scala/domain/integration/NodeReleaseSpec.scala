package ru.bitec.app.ops
package domain.integration

import application.integration.{NodeUpgradeAdmission, NodeUpgradeJson, NodeUpgradeDetail}
import io.circe.Json
import java.time.Instant
import munit.FunSuite
import scala.concurrent.duration._
import support.NodeUpgradeFixtures

final class NodeReleaseSpec extends FunSuite {
  import NodeReleaseJson._
  test("catalog owns exact releases, official repository, index/platform/config identities, and both architectures") {
    assertEquals(RemnawaveNodeReleaseCatalog.releases.map(_.releaseId).toSet, Set("node-2.8.0", "node-3.4.0", "node-3.4.1"))
    RemnawaveNodeReleaseCatalog.releases.foreach { r =>
      assert(r.valid && NodeRelease.immutableReference(r.imageReference))
      assertEquals(r.platforms.map(_.platform).toSet, Set("linux/amd64", "linux/arm64"))
      r.platforms.foreach(p => assert(p.manifestDigest != r.manifestDigest && p.configDigest != p.manifestDigest))
    }
    assertEquals(RemnawaveNodeReleaseCatalog.find("latest"), None)
    assertEquals(RemnawaveNodeReleaseCatalog.find("ghcr.io/evil/node:3.4.1"), None)
  }
  test("reviewed Panel version, commit, generation and platform are all required") {
    val r = NodeUpgradeFixtures.target; val api = NodeUpgradeFixtures.panel
    assert(RemnawaveNodeReleaseCatalog.compatibility(r, api, Some("linux/arm64")).compatible)
    List(api.copy(sourceCommit = Some("0" * 40)), api.copy(serverVersion = None), api.copy(apiGeneration = Some("PROFILE_PUBKEY")),
      api.copy(serverVersion = Some("3.0.0")), api.copy(blocker = Some("unknown"))).foreach(a =>
      assert(!RemnawaveNodeReleaseCatalog.compatibility(r, a).compatible))
    assert(!RemnawaveNodeReleaseCatalog.compatibility(r, api, Some("linux/arm/v7")).compatible)
    assert(!RemnawaveNodeReleaseCatalog.compatibility(r.copy(status = "BLOCKED"), api).compatible)
  }
  test("running config Image ID proves artifact; index digest, tag and reported version alone do not") {
    val r = NodeUpgradeFixtures.target; val observed = NodeUpgradeFixtures.image(r)
    assert(observed.matches(r))
    assert(!observed.copy(actualImageId = Some(r.manifestDigest)).matches(r))
    assert(!observed.copy(platform = Some("linux/arm64")).matches(r))
    assert(!observed.copy(configuredImage = Some("remnawave/node:latest")).matches(r))
    assert(!NodeUpgradeAdmission.versionConsistent(r, Some("3.4.0")))
    assert(NodeUpgradeAdmission.versionConsistent(r, Some("v3.4.1")))
  }
  test("immutable snapshot round trips and refuses unrecognized secret-bearing fields at every boundary") {
    val snapshot = NodeUpgradeFixtures.run().snapshot
    val json = snapshotEncoder(snapshot)
    assertEquals(json.as[NodeUpgradeSnapshot], Right(snapshot))
    val secret = "fake-SECRET_KEY-do-not-persist"
    assert(json.mapObject(_.add("SECRET_KEY", Json.fromString(secret))).as[NodeUpgradeSnapshot].isLeft)
    assert(releaseEncoder(snapshot.target).mapObject(_.add("image", Json.fromString("evil:latest"))).as[NodeRelease].isLeft)
    assert(observationEncoder(snapshot.members.head.baseline).mapObject(_.add("environment", Json.fromString(secret))).as[NodeImageObservation].isLeft)
    val output = NodeUpgradeJson.detail(NodeUpgradeDetail(NodeUpgradeFixtures.run(), Nil, Nil)).noSpaces
    assert(!output.contains(secret) && !output.contains("\"SECRET_KEY\":") && !output.contains("\"privateKey\":"))
  }
  test("freshness rejects stale, absent and future evidence even while a plan remains unexpired") {
    val now = Instant.now()
    assert(NodeUpgradeAdmission.fresh(Some(now.minusSeconds(1)), now, 15.minutes))
    List(None, Some(now.minusSeconds(901)), Some(now.plusSeconds(1))).foreach(t => assert(!NodeUpgradeAdmission.fresh(t, now, 15.minutes)))
  }
  test("upgrade snapshots retain the owned TLS mount while legacy members keep their original shape") {
    val legacy = NodeUpgradeFixtures.run().snapshot
    val id = java.util.UUID.randomUUID()
    val member = legacy.members.head.copy(tlsCertificateId = Some(id))
    val upgraded = legacy.copy(members = member :: legacy.members.tail)
    assertEquals(snapshotEncoder(upgraded).as[NodeUpgradeSnapshot], Right(upgraded))
    assertEquals(memberPlanEncoder(member).hcursor.get[String]("tlsCertificateId"), Right(id.toString))
    assert(!memberPlanEncoder(legacy.members.head).hcursor.downField("tlsCertificateId").succeeded)
    assertEquals(snapshotEncoder(legacy).as[NodeUpgradeSnapshot], Right(legacy))
    assert(memberPlanEncoder(member).mapObject(_.add("privateKey", Json.fromString("secret"))).as[NodeUpgradeMemberPlan].isLeft)
  }
  test("release content hash is stable across PostgreSQL timestamp precision and changes with digest") {
    val run = NodeUpgradeFixtures.run()
    val revision = FleetNodeReleaseRevision(run.releaseRevisionId, run.organizationId, run.integrationId, run.fleetId,
      1, run.snapshot.target, run.snapshot.panel, run.createdBy, run.createdAt)
    assertEquals(revision.hash, revision.copy(createdAt = revision.createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS)).hash)
    assert(revision.hash != revision.copy(release = revision.release.copy(manifestDigest = "sha256:" + "0" * 64)).hash)
  }
}
