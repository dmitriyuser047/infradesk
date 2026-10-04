package ru.bitec.app.ops
package application.integration

import application.port.FleetMemberRow
import domain.integration._
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import scala.concurrent.duration._
import support.ServerProfileFixtures

/** The rollout plan and the post-mutation gate are pure; both are decided here without any IO. */
final class FleetRolloutPlannerSpec extends FunSuite {
  private val now = Instant.parse("2026-10-04T12:00:00Z")
  private val stale = 900.seconds
  private def uid = UUID.randomUUID()
  private val org = uid
  private val integrationId = uid
  private val fleetId = uid
  private val revisionId = uid
  private val configProfile = uid

  private val content = FleetDesiredContent(uid, uid, 5, ServerProfileFixtures.content.hash, uid, configProfile,
    uid, uid, 9, "b" * 64, List(uid), 2222, List("198.51.100.0/24"), IntegrationDesiredNodeState.Enabled)
  private val fleet = RemnawaveFleet(fleetId, org, integrationId, "prod", "Prod", None, Some(revisionId), 1L,
    archived = false, uid, now, now)
  private val revision = RemnawaveFleetRevision(revisionId, org, fleetId, 3, 1, "h" * 64, content, uid, now)
  private def integration(mode: IntegrationManagementMode = IntegrationManagementMode.ManagedSelected) =
    Integration(integrationId, org, "panel", IntegrationProviderType.Remnawave,
      IntegrationBaseUrl.parse("https://panel.example.test").toOption.get, true, uid, false, now, now, mode)

  private def row(name: String, compliance: FleetCompliance, reasons: List[FleetDriftReason] = Nil,
    health: FleetHealth = FleetHealth.Healthy, blockers: List[FleetRolloutBlocker] = Nil,
    computedAt: Instant = now.minusSeconds(30)): FleetMemberRow = {
    val membership = RemnawaveFleetMembership(uid, org, fleetId, integrationId, uid, uid, 1L, uid, now)
    val assessment = RemnawaveFleetNodeAssessment(uid, org, fleetId, revisionId, membership.id, 1L,
      membership.inventoryNodeId, membership.resourceId, compliance, health, reasons, Nil, blockers,
      Some(computedAt), Some(computedAt), Some(computedAt), computedAt)
    FleetMemberRow(membership, name, "192.0.2.1", None, true, false, name, true, None, None, None,
      Some(configProfile.toString), None, None, true, Some(assessment))
  }
  private def facts(rows: FleetMemberRow*) = rows.toList.map(r => RolloutMemberFacts(r, r.nodeName, None, None))
  private val drift = List(FleetDriftReason.ServerProfileAssignmentMismatch)
  private def input(canary: List[UUID] = Nil, wave: Int = 2) = RolloutPreviewInput(revisionId, canary, wave, true, true)
  private def plan(rows: List[RolloutMemberFacts], in: RolloutPreviewInput = input(),
    shared: Option[IntegrationConfigRolloutPreview] = None, sync: Option[Instant] = Some(now.minusSeconds(20)),
    mode: IntegrationManagementMode = IntegrationManagementMode.ManagedSelected) =
    RemnawaveFleetRolloutPlanner.plan(fleet, revision, integration(mode), rows, sync, shared, None, in, now, stale, 25)

  test("a single mutable node needs no canary and compliant members are skipped but kept") {
    val a = row("a", FleetCompliance.Drifted, drift); val ok = row("ok", FleetCompliance.Compliant)
    plan(facts(a, ok)) match {
      case RolloutPlanOutcome.Ready(snapshot, _) =>
        assertEquals(snapshot.members.size, 2)
        assertEquals(snapshot.members.find(_.nodeName == "ok").flatMap(_.skipReason), Some("SKIPPED_ALREADY_COMPLIANT"))
        assertEquals(snapshot.policy.canaryMembershipIds, Nil)
        assertEquals(snapshot.waveCount, 1)
      case other => fail(s"unexpected $other")
    }
  }

  test("with several mutable nodes the first node is the pinned default canary and waves are deterministic") {
    val rows = List("d", "b", "a", "c").map(n => row(n, FleetCompliance.Drifted, drift))
    def snapshot = plan(facts(rows: _*), input(wave = 2)) match {
      case RolloutPlanOutcome.Ready(s, _) => s
      case other => fail(s"unexpected $other")
    }
    val first = snapshot
    assertEquals(first.policy.canaryMembershipIds.size, 1)
    assertEquals(first.members.filter(_.wave == 0).map(_.nodeName), List("a"))
    assertEquals(first.members.filter(_.wave == 1).map(_.nodeName), List("b", "c"))
    assertEquals(first.members.filter(_.wave == 2).map(_.nodeName), List("d"))
    assertEquals(snapshot.hash, first.hash)
  }

  test("a canary outside the mutable set is rejected") {
    val rows = List(row("a", FleetCompliance.Drifted, drift), row("b", FleetCompliance.Drifted, drift))
    plan(facts(rows: _*), input(canary = List(uid))) match {
      case RolloutPlanOutcome.Blocked(issues, _) =>
        assert(issues.exists(_.code == "REMNAWAVE_FLEET_ROLLOUT_CANARY_INVALID"))
      case other => fail(s"unexpected $other")
    }
  }

  test("stale evidence asks for a refresh and never plans") {
    val old = row("a", FleetCompliance.Drifted, drift, computedAt = now.minusSeconds(4000))
    assert(plan(facts(old)).isInstanceOf[RolloutPlanOutcome.RefreshRequired])
    assert(plan(facts(row("a", FleetCompliance.Drifted, drift)), sync = None).isInstanceOf[RolloutPlanOutcome.RefreshRequired])
  }

  test("unknown and blocked members, and unsupported port drift, block the preview") {
    assert(plan(facts(row("u", FleetCompliance.Unknown))).isInstanceOf[RolloutPlanOutcome.Blocked])
    assert(plan(facts(row("b", FleetCompliance.Blocked))).isInstanceOf[RolloutPlanOutcome.Blocked])
    plan(facts(row("p", FleetCompliance.Drifted, List(FleetDriftReason.NodePortDrift)))) match {
      case RolloutPlanOutcome.Blocked(issues, _) => assert(issues.exists(_.code == "NODE_PORT_CHANGE_UNSUPPORTED"))
      case other => fail(s"unexpected $other")
    }
  }

  test("a degraded member is a warning and a fully compliant fleet has nothing to do") {
    plan(facts(row("a", FleetCompliance.Drifted, drift, health = FleetHealth.Degraded))) match {
      case RolloutPlanOutcome.Ready(_, warnings) => assert(warnings.exists(_.code == "MEMBER_DEGRADED"))
      case other => fail(s"unexpected $other")
    }
    plan(facts(row("ok", FleetCompliance.Compliant))) match {
      case RolloutPlanOutcome.Blocked(issues, _) =>
        assert(issues.exists(_.code == "REMNAWAVE_FLEET_ROLLOUT_NOTHING_TO_DO"))
      case other => fail(s"unexpected $other")
    }
  }

  test("a shared config used by unhealthy outside nodes blocks the rollout") {
    val shared = IntegrationConfigRolloutPreview(8, 9, "x", "y", affectedNodes = 3, healthyNodes = 1,
      preexistingUnhealthyNodes = 2)
    plan(facts(row("a", FleetCompliance.Drifted, List(FleetDriftReason.ConfigRevisionDrift))), shared = Some(shared)) match {
      case RolloutPlanOutcome.Blocked(issues, _) =>
        assert(issues.exists(_.code == "REMNAWAVE_FLEET_SHARED_CONFIG_EXTERNAL_DEPENDENCY"))
      case other => fail(s"unexpected $other")
    }
  }

  test("changing the desired node state needs managed mode") {
    val r = row("a", FleetCompliance.Drifted, List(FleetDriftReason.DesiredNodeStateDrift))
    assert(plan(facts(r), mode = IntegrationManagementMode.Observe).isInstanceOf[RolloutPlanOutcome.Blocked])
    assert(plan(facts(r)).isInstanceOf[RolloutPlanOutcome.Ready])
  }

  test("the snapshot survives its own codec and carries no secret-shaped field") {
    val snapshot = plan(facts(row("a", FleetCompliance.Drifted, drift), row("b", FleetCompliance.Drifted, drift))) match {
      case RolloutPlanOutcome.Ready(s, _) => s
      case other => fail(s"unexpected $other")
    }
    assertEquals(FleetRolloutSnapshotCodec.decode(snapshot.json), Right(snapshot))
    val text = snapshot.json.noSpaces.toLowerCase
    List("password", "secret", "token", "privatekey").foreach(word => assert(!text.contains(word), word))
  }

  private def gateRow(r: FleetMemberRow, compliance: FleetCompliance, health: FleetHealth, computed: Instant,
    observed: Instant): FleetMemberRow = r.copy(assessment = r.assessment.map(a =>
    a.copy(compliance = compliance, health = health, computedAt = computed, inventoryObservedAt = Some(observed))))

  test("the gate refuses evidence older than the mutation and the refresh") {
    val base = row("a", FleetCompliance.Drifted, drift)
    val scope = List(base.membership.id -> 1L)
    val finished = now; val refreshed = now.plusSeconds(1)
    def verdict(r: FleetMemberRow) = FleetRolloutGate.evaluate(List(r), scope, revisionId, refreshed, finished, true)
    assertEquals(verdict(gateRow(base, FleetCompliance.Compliant, FleetHealth.Healthy, now, now.minusSeconds(5))), GateResult.Waiting)
    assertEquals(verdict(gateRow(base, FleetCompliance.Compliant, FleetHealth.Healthy, now.plusSeconds(5), now)), GateResult.Waiting)
    assertEquals(verdict(gateRow(base, FleetCompliance.Compliant, FleetHealth.Healthy, now.plusSeconds(5), now.plusSeconds(3))), GateResult.Passed)
    assertEquals(verdict(gateRow(base, FleetCompliance.Compliant, FleetHealth.Degraded, now.plusSeconds(5), now.plusSeconds(3))),
      GateResult.Degraded(List(base.membership.id)))
    assert(verdict(gateRow(base, FleetCompliance.Drifted, FleetHealth.Healthy, now.plusSeconds(5), now.plusSeconds(3)))
      .isInstanceOf[GateResult.Failed])
    assertEquals(verdict(gateRow(base, FleetCompliance.Unknown, FleetHealth.Unknown, now.plusSeconds(5), now.plusSeconds(3))),
      GateResult.Waiting)
  }

  test("fresh inventory cannot approve a gate with stale managed-local health evidence") {
    val base = row("a", FleetCompliance.Compliant)
    val fresh = gateRow(base, FleetCompliance.Compliant, FleetHealth.Healthy, now.plusSeconds(5), now.plusSeconds(3))
    val scope = List(base.membership.id -> 1L)
    def verdict(r: FleetMemberRow) = FleetRolloutGate.evaluate(List(r), scope, revisionId, now, now, true,
      localMembers = Set(base.membership.id))
    assertEquals(verdict(fresh), GateResult.Waiting)
    assertEquals(verdict(fresh.copy(assessment = fresh.assessment.map(_.copy(localObservedAt = Some(now.plusSeconds(4)))))), GateResult.Passed)
  }
}
