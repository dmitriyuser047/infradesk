package ru.bitec.app.ops
package application.integration

import application.port.FleetMemberRow
import domain.integration._
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import scala.concurrent.duration._

final class NodeUpgradeAdmissionSpec extends FunSuite {
  private def uid = UUID.randomUUID()
  private val now = Instant.now()
  private val revision = uid
  private val m = RemnawaveFleetMembership(uid, uid, uid, uid, uid, uid, 1L, uid, now)
  private val a = RemnawaveFleetNodeAssessment(uid, m.organizationId, m.fleetId, revision, m.id, m.version,
    m.inventoryNodeId, m.resourceId, FleetCompliance.Compliant, FleetHealth.Healthy, Nil, Nil, Nil,
    Some(now.minusSeconds(1)), Some(now.minusSeconds(1)), Some(now.minusSeconds(1)), now.minusSeconds(1))
  private val row = FleetMemberRow(m, "node", "192.0.2.1", None, true, false, "node", true, None, None, None,
    None, None, None, true, Some(a))
  private def gate(v: FleetMemberRow) = NodeUpgradeAdmission.assessment(v, revision, now, 15.minutes)
  test("current membership revision and healthy compliant evidence admit") { assertEquals(gate(row), None) }
  test("fresh assessment cannot hide any stale required observation") {
    List(a.copy(computedAt = now.minusSeconds(901)), a.copy(inventoryObservedAt = Some(now.minusSeconds(901))),
      a.copy(serverObservedAt = None), a.copy(localObservedAt = Some(now.plusSeconds(1)))).foreach { stale =>
      assertEquals(gate(row.copy(assessment = Some(stale))), Some(NodeUpgradeAdmission.RefreshRequired))
    }
  }
  test("evidence for another membership version, revision, node or resource requires refresh") {
    List(a.copy(fleetRevisionId = uid), a.copy(membershipVersion = 2), a.copy(inventoryNodeId = uid), a.copy(resourceId = uid)).foreach { wrong =>
      assertEquals(gate(row.copy(assessment = Some(wrong))), Some(NodeUpgradeAdmission.RefreshRequired))
    }
  }
  test("UNKNOWN BLOCKED DRIFTED compliance, unhealthy node and rollout blockers reject mutation") {
    List(a.copy(compliance = FleetCompliance.Unknown), a.copy(compliance = FleetCompliance.Blocked), a.copy(compliance = FleetCompliance.Drifted),
      a.copy(health = FleetHealth.Unknown), a.copy(health = FleetHealth.Degraded), a.copy(rolloutBlockers = List(FleetRolloutBlocker.UnknownRemoteState))).foreach { bad =>
      assertEquals(gate(row.copy(assessment = Some(bad))), Some(NodeUpgradeAdmission.HealthGate))
    }
  }
  test("current disconnected disabled inactive or unmanaged flags override a healthy cached assessment") {
    List(row.copy(connected = false), row.copy(disabled = true), row.copy(resourceActive = false), row.copy(localManaged = false)).foreach { bad =>
      assertEquals(gate(bad), Some(NodeUpgradeAdmission.HealthGate))
    }
  }
}
