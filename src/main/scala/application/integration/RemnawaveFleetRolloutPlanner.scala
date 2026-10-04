package ru.bitec.app.ops
package application.integration

import application.port.FleetMemberRow
import domain.integration._
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

final case class RolloutIssue(code: String, node: Option[String] = None)

final case class RolloutPreviewInput(revisionId: UUID, canaryMembershipIds: List[UUID], waveSize: Int,
  automaticRollback: Boolean, pauseAfterCanary: Boolean)

/** Facts read before planning. Remote reads are done by the caller; the planner only decides. */
final case class RolloutMemberFacts(row: FleetMemberRow, externalNodeId: String,
  assignment: Option[(UUID, Int)], desired: Option[IntegrationDesiredNodeState],
  source: Option[(UUID, Instant)] = None)

sealed trait RolloutPlanOutcome
object RolloutPlanOutcome {
  final case class Ready(snapshot: FleetRolloutSnapshot, warnings: List[RolloutIssue]) extends RolloutPlanOutcome
  final case class RefreshRequired(issues: List[RolloutIssue]) extends RolloutPlanOutcome
  final case class Blocked(issues: List[RolloutIssue], warnings: List[RolloutIssue]) extends RolloutPlanOutcome
}

/** Pure: turns fresh evidence and a choice into an immutable plan, or into the reasons there is none. */
object RemnawaveFleetRolloutPlanner {
  import RolloutPlanOutcome._

  def estimatedMutations(snapshot: FleetRolloutSnapshot): Int =
    snapshot.members.filter(_.mutable).map(_.actions.size).sum + (if (snapshot.shared.required) 1 else 0)

  private def actionsFor(reasons: Set[FleetDriftReason]): List[FleetActionKind] = {
    import FleetDriftReason._
    List(
      Option.when(reasons(ServerProfileAssignmentMismatch))(FleetActionKind.ServerProfileAssign),
      Option.when(reasons(ServerProfileAssignmentMismatch) || reasons(ServerProfileContentDrift))(
        FleetActionKind.ServerProfileApply),
      Option.when(reasons(PanelCidrDrift))(FleetActionKind.NetworkFirewall),
      Option.when(reasons(DesiredNodeStateDrift) || reasons(ActualNodeStateDrift))(FleetActionKind.DesiredState)
    ).flatten
  }

  private def unsupported(reasons: Set[FleetDriftReason]): List[String] = {
    import FleetDriftReason._
    val handled: Set[FleetDriftReason] = Set(ServerProfileAssignmentMismatch, ServerProfileContentDrift,
      PanelCidrDrift, DesiredNodeStateDrift, ActualNodeStateDrift, ConfigRevisionDrift)
    reasons.toList.sortBy(_.code).filterNot(handled).map {
      case NodePortDrift => "NODE_PORT_CHANGE_UNSUPPORTED"
      case LocalInstallationDrift => "LOCAL_INSTALLATION_CHANGE_UNSUPPORTED"
      case ConfigProfileMismatch | ActiveInboundsDrift => "NODE_CONFIG_BINDING_CHANGE_UNSUPPORTED"
      case _ => "MEMBER_EVIDENCE_INCOMPLETE"
    }.distinct
  }

  def fresh(row: FleetMemberRow, revisionId: UUID, now: Instant, staleAfter: FiniteDuration): Boolean = {
    val limit = now.minusMillis(staleAfter.toMillis)
    row.assessment.exists(a => a.fleetRevisionId == revisionId && a.membershipVersion == row.membership.version &&
      a.computedAt.isAfter(limit) && a.oldestEvidenceAt.exists(_.isAfter(limit)))
  }

  def plan(fleet: RemnawaveFleet, revision: RemnawaveFleetRevision, integration: Integration,
    facts: List[RolloutMemberFacts], lastSync: Option[Instant], shared: Option[IntegrationConfigRolloutPreview],
    sharedError: Option[String], input: RolloutPreviewInput, now: Instant, staleAfter: FiniteDuration,
    maxWaveSize: Int): RolloutPlanOutcome = {
    val structural = List(
      Option.when(fleet.archived)(RolloutIssue("REMNAWAVE_FLEET_ARCHIVED")),
      Option.when(!fleet.desiredRevisionId.contains(revision.id))(
        RolloutIssue("REMNAWAVE_FLEET_ROLLOUT_REVISION_NOT_DESIRED")),
      Option.when(!integration.enabled)(RolloutIssue("REMNAWAVE_FLEET_ROLLOUT_INTEGRATION_DISABLED")),
      Option.when(facts.isEmpty)(RolloutIssue("REMNAWAVE_FLEET_ROLLOUT_NO_MEMBERS")),
      Option.when(input.waveSize < 1 || input.waveSize > maxWaveSize)(
        RolloutIssue("REMNAWAVE_FLEET_ROLLOUT_WAVE_SIZE_INVALID"))
    ).flatten
    val limit = now.minusMillis(staleAfter.toMillis)
    val stale = facts.filterNot(f => fresh(f.row, revision.id, now, staleAfter)).map(f =>
      RolloutIssue("REFRESH_REQUIRED", Some(f.row.nodeName)))
    val syncStale = if (lastSync.exists(_.isAfter(limit))) Nil else List(RolloutIssue("REFRESH_REQUIRED"))
    if (structural.nonEmpty) Blocked(structural, Nil)
    else if (stale.nonEmpty || syncStale.nonEmpty) RefreshRequired(syncStale ++ stale)
    else build(revision, integration, facts, shared, sharedError, input)
  }

  private final case class Classified(facts: RolloutMemberFacts, skip: Option[String], actions: List[FleetActionKind])

  private def build(revision: RemnawaveFleetRevision, integration: Integration, facts: List[RolloutMemberFacts],
    shared: Option[IntegrationConfigRolloutPreview], sharedError: Option[String],
    input: RolloutPreviewInput): RolloutPlanOutcome = {
    val content = revision.content
    val issues = List.newBuilder[RolloutIssue]
    val warnings = List.newBuilder[RolloutIssue]
    val sharedRequired = shared.nonEmpty
    sharedError.foreach(code => issues += RolloutIssue(code))

    val classified = facts.sortBy(f => (f.row.nodeName, f.row.membership.id.toString)).map { f =>
      val a = f.row.assessment.get
      val name = Some(f.row.nodeName)
      val reasons = a.driftReasons.toSet
      if (!f.row.resourceActive) issues += RolloutIssue("NODE_RESOURCE_UNAVAILABLE", name)
      if (a.health == FleetHealth.Degraded) warnings += RolloutIssue("MEMBER_DEGRADED", name)
      a.compliance match {
        case FleetCompliance.Unknown =>
          issues += RolloutIssue("MEMBER_COMPLIANCE_UNKNOWN", name)
          Classified(f, None, Nil)
        case FleetCompliance.Blocked =>
          issues += RolloutIssue("MEMBER_BLOCKED", name)
          Classified(f, None, Nil)
        case FleetCompliance.Compliant => Classified(f, Some("SKIPPED_ALREADY_COMPLIANT"), Nil)
        case FleetCompliance.Drifted =>
          unsupported(reasons).foreach(code => issues += RolloutIssue(code, name))
          val actions = actionsFor(reasons)
          actions.foreach { kind =>
            val needsSsh = kind == FleetActionKind.ServerProfileApply || kind == FleetActionKind.NetworkFirewall
            if (needsSsh && a.rolloutBlockers.contains(FleetRolloutBlocker.NoTrustedSsh))
              issues += RolloutIssue("NO_TRUSTED_SSH", name)
            if (kind == FleetActionKind.NetworkFirewall &&
              a.rolloutBlockers.contains(FleetRolloutBlocker.NoManagedLocalInstallation))
              issues += RolloutIssue("NO_MANAGED_LOCAL_INSTALLATION", name)
            if (kind == FleetActionKind.DesiredState &&
              integration.managementMode != IntegrationManagementMode.ManagedSelected)
              issues += RolloutIssue("REMNAWAVE_FLEET_ROLLOUT_MANAGEMENT_MODE_REQUIRED", name)
          }
          if (actions.nonEmpty) {
            a.rolloutBlockers.filterNot(b => b == FleetRolloutBlocker.NoTrustedSsh ||
              b == FleetRolloutBlocker.NoManagedLocalInstallation).foreach(b => issues += RolloutIssue(b.code, name))
            Classified(f, None, actions)
          } else Classified(f, Some("COVERED_BY_SHARED_CONFIG"), Nil)
      }
    }

    val mutable = classified.filter(_.skip.isEmpty)
    if (mutable.isEmpty && !sharedRequired) issues += RolloutIssue("REMNAWAVE_FLEET_ROLLOUT_NOTHING_TO_DO")

    val onProfile = facts.filter(_.row.actualConfigProfileExternalId.contains(content.externalConfigProfileId.toString))
    val fleetUnhealthy = onProfile.count(_.row.assessment.exists(_.health != FleetHealth.Healthy))
    val external = shared.fold(0)(s => math.max(0, s.affectedNodes - onProfile.size))
    val externalUnhealthy = shared.fold(0)(s => math.max(0, s.preexistingUnhealthyNodes - fleetUnhealthy))
    if (external > 0 && externalUnhealthy > 0)
      issues += RolloutIssue("REMNAWAVE_FLEET_SHARED_CONFIG_EXTERNAL_DEPENDENCY")
    else if (external > 0) warnings += RolloutIssue("SHARED_CONFIG_AFFECTS_EXTERNAL_NODES")

    val mutableIds = mutable.map(_.facts.row.membership.id)
    val limit = math.max(1, input.waveSize)
    val canaryIds: List[UUID] =
      if (mutable.size <= 1) mutableIds
      else if (input.canaryMembershipIds.isEmpty) mutableIds.take(1)
      else input.canaryMembershipIds
    if (mutable.size > 1 && (canaryIds.distinct != canaryIds || !canaryIds.forall(mutableIds.contains) ||
      canaryIds.size > limit)) issues += RolloutIssue("REMNAWAVE_FLEET_ROLLOUT_CANARY_INVALID")

    val canary = canaryIds.flatMap(id => mutable.find(_.facts.row.membership.id == id))
    val rest = mutable.filterNot(c => canaryIds.contains(c.facts.row.membership.id))
    val waves: List[List[Classified]] = (if (canary.nonEmpty) List(canary) else Nil) ++ rest.grouped(limit).toList
    val skipped = classified.filter(_.skip.nonEmpty)

    def planOf(c: Classified, wave: Int, position: Int) = {
      val a = c.facts.row.assessment.get
      FleetRolloutMemberPlan(c.facts.row.membership.id, c.facts.row.membership.version,
        c.facts.row.membership.inventoryNodeId, c.facts.row.membership.resourceId, c.facts.externalNodeId,
        c.facts.row.nodeName, wave, position, c.skip, c.actions,
        FleetMemberBaseline(c.facts.assignment.map(_._1), c.facts.assignment.map(_._2), c.facts.desired,
          a.compliance.code, a.health.code, c.facts.row.disabled,
          c.facts.source.map(_._1), c.facts.source.map(_._2)))
    }
    val mutablePlans = waves.zipWithIndex.flatMap { case (wave, index) => wave.map(c => (c, index)) }
      .zipWithIndex.map { case ((c, wave), position) => planOf(c, wave, position) }
    val skippedPlans = skipped.zipWithIndex.map { case (c, i) => planOf(c, 0, mutablePlans.size + i) }

    val found = issues.result().distinct
    if (found.nonEmpty) Blocked(found, warnings.result().distinct)
    else {
      val config = FleetRolloutSharedConfig(sharedRequired, content.inventoryConfigProfileId,
        content.configRevisionNumber, content.configRevisionHash, shared.map(_.baselineRevisionNumber),
        shared.map(_.baselineSha256), external, externalUnhealthy)
      val policy = FleetRolloutPolicy(limit, if (mutable.size > 1) canaryIds else Nil, input.pauseAfterCanary,
        input.automaticRollback, FleetRollbackScope.CurrentWave)
      Ready(FleetRolloutSnapshot(revision.fleetId, integration.id, integration.updatedAt, revision.id,
        revision.number, revision.contentHash, content, policy, config, mutablePlans ++ skippedPlans),
        warnings.result().distinct)
    }
  }
}
