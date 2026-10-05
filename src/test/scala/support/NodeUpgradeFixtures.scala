package ru.bitec.app.ops
package support

import domain.integration._
import java.time.Instant
import java.util.UUID

object NodeUpgradeFixtures {
  def uid: UUID = UUID.randomUUID()
  val panel: NodeApiCompatibility = NodeApiCompatibility(Some("3.4.0"), Some("PROFILE_SECRET_KEY"),
    Some("dd0eab160fd91a1c09345df459e146712581736f"), Set(NodeProvisioningCapability.Inventory,
      NodeProvisioningCapability.Status, NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData,
      NodeProvisioningCapability.ConfigProfile, NodeProvisioningCapability.CreateReconciliation), None)
  def previous: NodeRelease = RemnawaveNodeReleaseCatalog.find("node-3.4.0").get
  def target: NodeRelease = RemnawaveNodeReleaseCatalog.find("node-3.4.1").get
  def image(release: NodeRelease, marker: String = "b" * 64): NodeImageObservation = {
    val p = release.forPlatform("linux/amd64").get
    NodeImageObservation(true, Some(s"${release.imageRepository}@${p.manifestDigest}"), Some(p.configDigest),
      List(s"${release.imageRepository}@${p.manifestDigest}"), Some(p.platform), Some("c" * 64),
      Some(Instant.parse("2026-10-01T00:00:00Z")), Some(Instant.parse("2026-10-01T00:01:00Z")),
      true, 0, true, true, Some(if (release.releaseId == "node-3.4.0") "a" * 64 else "d" * 64), Some(marker),
      500000000L, 20L * 1024 * 1024 * 1024)
  }
  def run(count: Int = 3, auto: Boolean = false, pause: Boolean = false): RemnawaveFleetUpgradeRun = {
    val now = Instant.now()
    val org = uid; val integration = uid; val fleet = uid; val releaseRevision = uid
    val configuration = FleetDesiredContent(uid, uid, 1, ServerProfileFixtures.content.hash, uid, uid, uid,
      uid, 1, "e" * 64, List(uid), 30222, List("192.0.2.0/24"), IntegrationDesiredNodeState.Enabled).normalized.toOption.get
    val members = (0 until count).toList.map(n => NodeUpgradeMemberPlan(uid, 1L, uid, uid, uid, s"node-$n", uid,
      target.imageReference, uid, now, n, n, false, image(previous), Some(previous.releaseId),
      Some(s"${previous.imageRepository}@${previous.forPlatform("linux/amd64").get.manifestDigest}"), Some(previous.nodeVersion)))
    val snapshot = NodeUpgradeSnapshot(1, org, integration, fleet, uid, "f" * 64, now, configuration,
      releaseRevision, "e" * 64, target, panel, members.take(1).map(_.membershipId), 1, pause, auto, members)
    RemnawaveFleetUpgradeRun(uid, org, integration, fleet, releaseRevision, Some(uid), FleetRolloutState.Queued,
      NodeUpgradePhase.Validate, snapshot, snapshot.hash, 0, snapshot.waveCount, uid, now, now.plusSeconds(86400),
      None, None, None, None, None, None, None, FleetRollbackScope.CurrentWave, false, now, now, None, 1L, now)
  }
  def members(r: RemnawaveFleetUpgradeRun): List[RemnawaveFleetUpgradeMember] = r.snapshot.members.map(m =>
    RemnawaveFleetUpgradeMember(uid, r.organizationId, r.id, m.membershipId, m.resourceId, m.wave, m.position,
      FleetRolloutMemberState.Pending, None, None, None, None, None, None))
}
