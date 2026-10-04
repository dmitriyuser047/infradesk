package ru.bitec.app.ops
package application.integration

import application.port.FleetMemberRow
import domain.integration._
import java.time.Instant
import java.util.UUID

sealed trait GateResult
object GateResult {
  /** Fresh evidence has not arrived yet; look again later. */
  case object Waiting extends GateResult
  case object Passed extends GateResult
  /** Compliant, but not healthy: an operator decides. */
  final case class Degraded(nodes: List[UUID]) extends GateResult
  /** Evidence is fresh and shows the member is not in the wanted state. */
  final case class Failed(nodes: List[UUID], code: String) extends GateResult
}

/** Pure judgement of fresh evidence after a mutation. Evidence that predates the refresh request or
  * the end of the mutation is never accepted, so a stale "healthy" cannot pass a gate.
  */
object FleetRolloutGate {
  def evaluate(rows: List[FleetMemberRow], scope: List[(UUID, Long)], revisionId: UUID, refreshedAt: Instant,
    mutationFinishedAt: Instant, requireHealthy: Boolean, sharedOnly: Boolean = false,
    localMembers: Set[UUID] = Set.empty, serverMembers: Set[UUID] = Set.empty,
    serverAfter: Option[Instant] = None): GateResult = {
    val byMembership = rows.map(r => r.membership.id -> r).toMap
    val judged = scope.map { case (membershipId, version) =>
      byMembership.get(membershipId).filter(_.membership.version == version).flatMap(_.assessment)
        .filter(a => a.fleetRevisionId == revisionId && a.membershipVersion == version &&
          a.computedAt.isAfter(refreshedAt) && a.inventoryObservedAt.exists(_.isAfter(mutationFinishedAt)) &&
          (!localMembers(membershipId) || a.localObservedAt.exists(_.isAfter(mutationFinishedAt))) &&
          (!serverMembers(membershipId) || serverAfter.exists(after => a.serverObservedAt.exists(_.isAfter(after)))))
        .toRight(membershipId)
    }
    val missing = judged.collect { case Left(id) => id }
    val found = judged.collect { case Right(a) => a }
    if (missing.nonEmpty) GateResult.Waiting
    else {
      val unknown = found.filter(a => a.compliance == FleetCompliance.Unknown)
      val failed = found.filter(a => a.compliance == FleetCompliance.Blocked ||
        (if (sharedOnly) a.driftReasons.exists(Set[FleetDriftReason](FleetDriftReason.ConfigRevisionDrift,
          FleetDriftReason.ConfigProfileMismatch, FleetDriftReason.ActiveInboundsDrift))
        else a.compliance == FleetCompliance.Drifted))
      val degraded = found.filter(a => (sharedOnly || a.compliance == FleetCompliance.Compliant) && requireHealthy &&
        a.health != FleetHealth.Healthy)
      if (failed.nonEmpty) GateResult.Failed(failed.map(_.membershipId), "REMNAWAVE_FLEET_ROLLOUT_MEMBER_NOT_COMPLIANT")
      else if (unknown.nonEmpty) GateResult.Waiting
      else if (degraded.nonEmpty) GateResult.Degraded(degraded.map(_.membershipId))
      else GateResult.Passed
    }
  }
}
