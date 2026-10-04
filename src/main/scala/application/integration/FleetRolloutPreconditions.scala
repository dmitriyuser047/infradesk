package ru.bitec.app.ops
package application.integration

import application.port.FleetMemberRow
import domain.integration._
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** Admission is based on current observations, never the age of the approved plan. */
object FleetRolloutPreconditions {
  val RefreshRequired = "REMNAWAVE_FLEET_ROLLOUT_REFRESH_REQUIRED"
  val PlanChanged = "REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED"

  def members(snapshot: FleetRolloutSnapshot, rows: List[FleetMemberRow],
    completed: Set[UUID], lastSync: Option[Instant], now: Instant, staleAfter: FiniteDuration): Option[String] = {
    val limit = now.minusMillis(staleAfter.toMillis)
    def fresh(at: Option[Instant]) = at.exists(t => t.isAfter(limit) && !t.isAfter(now))
    val byId = rows.map(r => r.membership.id -> r).toMap
    val evidence = snapshot.members.map(p => p -> byId.get(p.membershipId))
    val identitiesChanged = evidence.exists { case (p, row) => !row.exists(r => r.membership.active &&
      r.membership.version == p.membershipVersion && r.membership.inventoryNodeId == p.inventoryNodeId &&
      r.membership.resourceId == p.resourceId) }
    val stale = !fresh(lastSync) || evidence.exists { case (p, row) =>
      !row.exists(r => r.membership.active && r.membership.version == p.membershipVersion &&
        r.assessment.exists(a => a.fleetRevisionId == snapshot.revisionId &&
          a.membershipVersion == p.membershipVersion && fresh(Some(a.computedAt)) && fresh(a.oldestEvidenceAt) &&
          fresh(a.inventoryObservedAt) && (!r.localManaged || fresh(a.localObservedAt)) &&
          (!p.actions.exists(k => k == FleetActionKind.ServerProfileAssign || k == FleetActionKind.ServerProfileApply) ||
            fresh(a.serverObservedAt))))
    }
    val changed = evidence.exists { case (p, row) => row.exists { r => r.assessment.exists { a =>
      val needsSsh = p.actions.exists(k => k == FleetActionKind.ServerProfileApply || k == FleetActionKind.NetworkFirewall)
      val blockers = a.rolloutBlockers.exists {
        case FleetRolloutBlocker.NoTrustedSsh => needsSsh
        case FleetRolloutBlocker.NoManagedLocalInstallation => p.actions.contains(FleetActionKind.NetworkFirewall)
        case _ => true
      }
      val mustBeCompliant = completed(p.membershipId) || p.skipReason.contains("SKIPPED_ALREADY_COMPLIANT")
      val observedHealthy = r.disabled == (snapshot.content.desiredNodeState == IntegrationDesiredNodeState.Disabled) &&
        (r.disabled || r.connected)
      val healthAllowed = (a.health == FleetHealth.Healthy && observedHealthy) || (!completed(p.membershipId) &&
        p.baseline.health == FleetHealth.Degraded.code && a.health == FleetHealth.Degraded)
      !r.resourceActive || blockers || !healthAllowed || a.compliance == FleetCompliance.Unknown ||
        a.compliance == FleetCompliance.Blocked || (mustBeCompliant && a.compliance != FleetCompliance.Compliant) ||
        RemnawaveFleetRolloutPlanner.unsupported(a.driftReasons.toSet).nonEmpty ||
        !RemnawaveFleetRolloutPlanner.actionsFor(a.driftReasons.toSet).forall(p.actions.contains) ||
        (a.driftReasons.contains(FleetDriftReason.ConfigRevisionDrift) && !snapshot.shared.required)
    } } }
    if (identitiesChanged) Some(PlanChanged) else if (stale) Some(RefreshRequired) else Option.when(changed)(PlanChanged)
  }

  def consumers(snapshot: FleetRolloutSnapshot, current: List[FleetRolloutConfigConsumer],
    now: Instant, staleAfter: FiniteDuration): Option[String] = {
    val approved = snapshot.shared.consumers.map(n => n.inventoryNodeId -> n.externalNodeId).toSet
    val identities = current.map(n => n.inventoryNodeId -> n.externalNodeId).toSet
    val fleetNodes = snapshot.members.map(_.inventoryNodeId).toSet
    if (identities != approved) Some(PlanChanged)
    else if (current.exists(n => !n.observedAt.isAfter(now.minusMillis(staleAfter.toMillis)) || n.observedAt.isAfter(now)))
      Some(RefreshRequired)
    else Option.when(current.exists(n => !fleetNodes(n.inventoryNodeId) && !n.disabled && !n.connected))(PlanChanged)
  }

  def impact(snapshot: FleetRolloutSnapshot, current: List[FleetRolloutConfigConsumer],
    impact: Option[IntegrationConfigRolloutPreview], observedHash: Option[String]): Option[String] = {
    val shared = snapshot.shared
    val unhealthy = current.count(n => !n.disabled && !n.connected)
    val matches = observedHash == shared.baselineHash && observedHash.nonEmpty && impact.exists(p =>
      shared.baselineRevisionNumber.contains(p.baselineRevisionNumber) && shared.baselineHash.contains(p.baselineSha256) &&
        p.targetRevisionNumber == shared.revisionNumber && p.targetSha256 == shared.revisionHash &&
        p.affectedNodes == current.size && p.preexistingUnhealthyNodes == unhealthy &&
        p.healthyNodes == current.size - unhealthy)
    Option.unless(matches)(PlanChanged)
  }
}
