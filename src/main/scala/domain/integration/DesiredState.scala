package ru.bitec.app.ops
package domain.integration

import java.time.Instant
import java.util.UUID

/** What a selected node should be. Only a Remnawave node can carry it; there is no free-form
  * desired state, and restart is never a desired state.
  */
sealed trait IntegrationDesiredNodeState { def code: String }
object IntegrationDesiredNodeState {
  case object Enabled extends IntegrationDesiredNodeState { val code = "ENABLED" }
  case object Disabled extends IntegrationDesiredNodeState { val code = "DISABLED" }
  val All: List[IntegrationDesiredNodeState] = List(Enabled, Disabled)
  def fromCode(code: String): Option[IntegrationDesiredNodeState] = All.find(_.code == code)

  /** The one action that moves a node towards `desired`, or none when it is already there. */
  def remediation(desired: IntegrationDesiredNodeState, observedDisabled: Boolean): Option[IntegrationActionCode] =
    (desired, observedDisabled) match {
      case (Enabled, true) => Some(IntegrationActionCode.NodeEnable)
      case (Disabled, false) => Some(IntegrationActionCode.NodeDisable)
      case _ => None
    }

  /** The manual action that would work against the intent. Restart contradicts neither. */
  def contradicts(desired: IntegrationDesiredNodeState, action: IntegrationActionCode): Boolean =
    (desired, action) match {
      case (Enabled, IntegrationActionCode.NodeDisable) | (Disabled, IntegrationActionCode.NodeEnable) => true
      case _ => false
    }
}

/** A person's persistent intent for one node. `version` names the exact intent an automatic action
  * executed; it changes only when the intent does.
  */
final case class IntegrationDesiredState(
  id: UUID,
  organizationId: UUID,
  integrationId: UUID,
  inventoryObjectId: UUID,
  state: IntegrationDesiredNodeState,
  version: Long,
  setByUserId: UUID,
  createdAt: Instant,
  updatedAt: Instant,
  /** The observation the latest remediation was decided on; a new one needs a newer observation. */
  lastAttemptObservationAt: Option[Instant],
  lastActionExecutionId: Option[UUID]
)

/** Where a managed node stands. Never stored: it follows from the intent, the observed inventory
  * and the latest remediation, so there is one truth and not two.
  */
sealed trait IntegrationDesiredStateStatus { def code: String }
object IntegrationDesiredStateStatus {
  case object Compliant extends IntegrationDesiredStateStatus { val code = "COMPLIANT" }
  case object Drifted extends IntegrationDesiredStateStatus { val code = "DRIFTED" }
  case object Applying extends IntegrationDesiredStateStatus { val code = "APPLYING" }
  case object WaitingRefresh extends IntegrationDesiredStateStatus { val code = "WAITING_REFRESH" }
  case object RemediationFailed extends IntegrationDesiredStateStatus { val code = "REMEDIATION_FAILED" }
  case object Unavailable extends IntegrationDesiredStateStatus { val code = "UNAVAILABLE" }
  val All: List[IntegrationDesiredStateStatus] =
    List(Compliant, Drifted, Applying, WaitingRefresh, RemediationFailed, Unavailable)
  def fromCode(code: String): Option[IntegrationDesiredStateStatus] = All.find(_.code == code)

  /** The observed facts a status follows from. */
  final case class Facts(objectActive: Boolean, observedDisabled: Boolean, lastSeenAt: Instant,
    lastAction: Option[(IntegrationActionStatus, Option[Instant])])

  def derive(desired: IntegrationDesiredNodeState, facts: Facts): IntegrationDesiredStateStatus = {
    // An observation counts for an action only when it began after that action finished.
    def unobserved(finishedAt: Option[Instant]) = finishedAt.forall(!facts.lastSeenAt.isAfter(_))
    if (!facts.objectActive) Unavailable
    else facts.lastAction match {
      case Some((IntegrationActionStatus.Queued | IntegrationActionStatus.Running, _)) => Applying
      case Some((IntegrationActionStatus.Succeeded | IntegrationActionStatus.Unknown, finished)) if unobserved(finished) =>
        WaitingRefresh
      case Some((IntegrationActionStatus.Failed, finished)) if unobserved(finished) => RemediationFailed
      case _ =>
        if (IntegrationDesiredNodeState.remediation(desired, facts.observedDisabled).isEmpty) Compliant else Drifted
    }
  }
}
