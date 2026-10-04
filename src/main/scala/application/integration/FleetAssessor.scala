package ru.bitec.app.ops
package application.integration

import application.port.RemnawaveNodeLocalEvidence
import domain.integration._
import domain.provisioning.{ServerProfileAssignment, ServerProfileContent, ServerProfileDiff, ServerProfileObservation}
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** Everything one assessment is computed from. Gathering it performs reads; judging it does not. */
final case class FleetMemberEvidence(
  desired: FleetDesiredContent,
  resourceId: UUID,
  /** The content of the desired server profile revision, for the content comparison. */
  desiredServerContent: Option[ServerProfileContent],
  bindingPresent: Boolean,
  bindingResourceId: Option[UUID],
  resourceActive: Boolean,
  serverProfileAvailable: Boolean,
  configProfileAvailable: Boolean,
  node: Option[RemnawaveNodeSummary],
  inventoryActive: Boolean,
  inventoryObservedAt: Option[Instant],
  remoteConfigSha256: Option[String],
  assignment: Option[ServerProfileAssignment],
  serverObservation: Option[ServerProfileObservation],
  desiredStateRecord: Option[IntegrationDesiredState],
  /** Stage25C proved this installation is the one InfraDesk manages. */
  localManaged: Boolean,
  localEvidence: Option[RemnawaveNodeLocalEvidence],
  localObservedAt: Option[Instant],
  localObservationFailed: Boolean,
  trustedSsh: Boolean,
  apiContractConfirmed: Boolean,
  busy: Boolean)

final case class FleetVerdict(compliance: FleetCompliance, health: FleetHealth,
  driftReasons: List[FleetDriftReason], healthReasons: List[FleetHealthReason],
  rolloutBlockers: List[FleetRolloutBlocker])

/** Pure desired-versus-actual judgement. It reads nothing and mutates nothing.
  *
  * Each dimension answers separately and the member's compliance is the worst answer, with one
  * deliberate ordering: a proven difference outranks missing evidence, because it is actionable,
  * while a structural problem outranks both because the comparison itself is not yet meaningful.
  * Evidence that is too old never produces a difference - it produces UNKNOWN, so nothing is ever
  * reported as compliant or as drifted on the strength of a stale observation.
  */
object FleetAssessor {
  private sealed trait Answer
  private case object Met extends Answer
  private final case class Differs(reason: FleetDriftReason) extends Answer
  private final case class Unproven(reason: FleetDriftReason) extends Answer
  private final case class Structural(reason: FleetDriftReason) extends Answer

  def fresh(at: Option[Instant], now: Instant, staleAfter: FiniteDuration): Boolean =
    at.exists(value => !value.plusMillis(staleAfter.toMillis).isBefore(now))

  /** `localDimensions = false` judges only what the database already knows, which is what an impact
    * preview may do: it answers "what would differ" without opening a single SSH connection.
    */
  def assess(evidence: FleetMemberEvidence, now: Instant, staleAfter: FiniteDuration,
    localDimensions: Boolean = true): FleetVerdict = {
    val desired = evidence.desired
    val inventoryFresh = fresh(evidence.inventoryObservedAt, now, staleAfter)
    val serverFresh = fresh(evidence.serverObservation.map(_.observedAt), now, staleAfter)
    val localFresh = fresh(evidence.localObservedAt, now, staleAfter)
    // A node whose panel record, binding or referenced objects are gone cannot be compared at all.
    val structural = List(
      Option.when(!evidence.bindingPresent || !evidence.bindingResourceId.contains(evidence.resourceId))(
        FleetDriftReason.BindingMissing),
      Option.when(evidence.node.isEmpty || !evidence.inventoryActive)(FleetDriftReason.InventoryMissing),
      Option.when(!evidence.resourceActive)(FleetDriftReason.ResourceUnavailable),
      Option.when(!evidence.serverProfileAvailable)(FleetDriftReason.ServerProfileUnavailable),
      Option.when(!evidence.configProfileAvailable)(FleetDriftReason.ConfigProfileUnavailable)
    ).flatten

    val answers: List[Answer] =
      if (structural.nonEmpty) structural.map(Structural(_))
      else {
        val node = evidence.node.get
        val assignmentAnswer: Answer = evidence.assignment match {
          case Some(assignment) if assignment.revisionId == desired.serverProfileRevisionId => Met
          case _ => Differs(FleetDriftReason.ServerProfileAssignmentMismatch)
        }
        // Content is judged against the desired revision's own content, whatever is assigned.
        val contentAnswer: Answer = (evidence.serverObservation, evidence.desiredServerContent) match {
          case (Some(observation), Some(content)) if serverFresh =>
            if (ServerProfileDiff.assess(content, observation.content).compliant) Met
            else Differs(FleetDriftReason.ServerProfileContentDrift)
          case _ => Unproven(FleetDriftReason.ServerProfileUnobserved)
        }
        val profileAnswer: Answer =
          if (!inventoryFresh) Unproven(FleetDriftReason.InventoryStale)
          else if (node.activeConfigProfileUuid.contains(desired.externalConfigProfileId.toString)) Met
          else Differs(FleetDriftReason.ConfigProfileMismatch)
        val configAnswer: Answer =
          if (!inventoryFresh) Unproven(FleetDriftReason.InventoryStale)
          else evidence.remoteConfigSha256 match {
            case Some(hash) if hash == desired.configRevisionHash => Met
            case Some(_) => Differs(FleetDriftReason.ConfigRevisionDrift)
            case None => Unproven(FleetDriftReason.ConfigRevisionUnobserved)
          }
        // Set semantics: a missing inbound and an extra inbound are both differences, order is not.
        val inboundAnswer: Answer =
          if (!inventoryFresh) Unproven(FleetDriftReason.InventoryStale)
          else node.activeInboundIds match {
            case Some(actual) if actual.toSet == desired.inboundSet => Met
            case Some(_) => Differs(FleetDriftReason.ActiveInboundsDrift)
            case None => Unproven(FleetDriftReason.ActiveInboundsUnobserved)
          }
        val intentAnswer: Answer = evidence.desiredStateRecord match {
          case Some(record) if record.state == desired.desiredNodeState => Met
          case _ => Differs(FleetDriftReason.DesiredNodeStateDrift)
        }
        // The panel's own disabled flag, not its connectivity: being offline is a health matter.
        val actualStateAnswer: Answer =
          if (!inventoryFresh) Unproven(FleetDriftReason.InventoryStale)
          else if (node.isDisabled == (desired.desiredNodeState == IntegrationDesiredNodeState.Disabled)) Met
          else Differs(FleetDriftReason.ActualNodeStateDrift)
        val localAnswers: List[Answer] =
          if (!localDimensions) Nil
          else if (!evidence.localManaged) List(Unproven(FleetDriftReason.LocalManagementUnavailable))
          else evidence.localEvidence.filter(_ => localFresh && !evidence.localObservationFailed) match {
            case None => List(Unproven(FleetDriftReason.LocalObservationUnavailable))
            case Some(local) => List(
              Option.when(!local.portListening)(Differs(FleetDriftReason.NodePortDrift)),
              Option.when(!local.firewallMatches)(Differs(FleetDriftReason.PanelCidrDrift)),
              // Managed files and the running image are one managed installation, so one reason.
              Option.when(!local.managedFiles || !local.imageMatches)(Differs(FleetDriftReason.LocalInstallationDrift))
            ).flatten
          }
        List(assignmentAnswer, contentAnswer, profileAnswer, configAnswer, inboundAnswer, intentAnswer,
          actualStateAnswer) ++ localAnswers ++ (if (!localDimensions) Nil
          else List(if (evidence.apiContractConfirmed) Met else Unproven(FleetDriftReason.ApiContractUnconfirmed)))
      }

    val compliance =
      if (answers.exists(_.isInstanceOf[Structural])) FleetCompliance.Blocked
      else if (answers.exists(_.isInstanceOf[Differs])) FleetCompliance.Drifted
      else if (answers.exists(_.isInstanceOf[Unproven])) FleetCompliance.Unknown
      else FleetCompliance.Compliant
    val driftReasons = answers.collect {
      case Structural(reason) => reason
      case Differs(reason) => reason
      case Unproven(reason) => reason
    }.distinct.sortBy(_.code)

    val (health, healthReasons) = judgeHealth(evidence, inventoryFresh, localFresh)
    val blockers = List(
      Option.when(!evidence.trustedSsh)(FleetRolloutBlocker.NoTrustedSsh),
      Option.when(!evidence.localManaged)(FleetRolloutBlocker.NoManagedLocalInstallation),
      Option.when(!evidence.apiContractConfirmed)(FleetRolloutBlocker.ApiContractUnconfirmed),
      Option.when(structural.contains(FleetDriftReason.BindingMissing))(FleetRolloutBlocker.BindingInvalid),
      Option.when(evidence.busy)(FleetRolloutBlocker.ActiveConflictingOperation),
      Option.when(compliance == FleetCompliance.Unknown)(FleetRolloutBlocker.UnknownRemoteState),
      Option.when(structural.exists(_ != FleetDriftReason.BindingMissing))(
        FleetRolloutBlocker.ReferencedObjectUnavailable)
    ).flatten.distinct.sortBy(_.code)
    FleetVerdict(compliance, health, driftReasons, healthReasons, blockers)
  }

  /** Health answers "is it doing what the fleet intends", so a node the fleet wants disabled is not
    * unhealthy for being offline, and a local observation that failed is unknown rather than bad.
    */
  private def judgeHealth(evidence: FleetMemberEvidence, inventoryFresh: Boolean,
    localFresh: Boolean): (FleetHealth, List[FleetHealthReason]) = {
    val wantsEnabled = evidence.desired.desiredNodeState == IntegrationDesiredNodeState.Enabled
    evidence.node.filter(_ => evidence.inventoryActive) match {
      case None => (FleetHealth.Unknown, List(FleetHealthReason.InventoryMissing))
      case Some(_) if !inventoryFresh => (FleetHealth.Unknown, List(FleetHealthReason.InventoryStale))
      case Some(node) =>
        val panel = List(
          Option.when(wantsEnabled && node.isDisabled)(FleetHealthReason.NodeDisabledUnexpectedly),
          Option.when(wantsEnabled && !node.isDisabled && !node.isConnected && !node.isConnecting)(
            FleetHealthReason.NodeDisconnected),
          Option.when(!wantsEnabled && !node.isDisabled)(FleetHealthReason.NodeEnabledUnexpectedly)
        ).flatten
        val local =
          if (!evidence.localManaged) Nil
          else evidence.localEvidence.filter(_ => localFresh && !evidence.localObservationFailed) match {
            case None => List(FleetHealthReason.LocalObservationFailed)
            case Some(_) if !wantsEnabled => Nil
            case Some(value) => List(
              Option.when(!value.containerRunning)(FleetHealthReason.LocalContainerNotRunning),
              Option.when(value.containerRunning && !value.portListening)(FleetHealthReason.LocalPortNotListening),
              Option.when(value.containerRunning && !value.stable)(FleetHealthReason.LocalContainerUnstable)
            ).flatten
          }
        val reasons = (panel ++ local).distinct.sortBy(_.code)
        if (reasons.isEmpty) (FleetHealth.Healthy, Nil)
        else if (reasons.forall(_ == FleetHealthReason.LocalObservationFailed)) (FleetHealth.Unknown, reasons)
        else (FleetHealth.Degraded, reasons)
    }
  }
}
