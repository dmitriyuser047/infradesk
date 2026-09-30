package ru.bitec.app.ops
package domain.integration

import java.time.Instant
import java.util.UUID

sealed trait IntegrationActionCode { def code: String }
object IntegrationActionCode {
  case object NodeEnable extends IntegrationActionCode { val code = "NODE_ENABLE" }
  case object NodeDisable extends IntegrationActionCode { val code = "NODE_DISABLE" }
  case object NodeRestart extends IntegrationActionCode { val code = "NODE_RESTART" }
  val All: List[IntegrationActionCode] = List(NodeEnable, NodeDisable, NodeRestart)
  def fromCode(code: String): Option[IntegrationActionCode] = All.find(_.code == code)
}

sealed trait IntegrationActionStatus { def code: String }
object IntegrationActionStatus {
  case object Queued extends IntegrationActionStatus { val code = "QUEUED" }
  case object Running extends IntegrationActionStatus { val code = "RUNNING" }
  case object Succeeded extends IntegrationActionStatus { val code = "SUCCEEDED" }
  case object Failed extends IntegrationActionStatus { val code = "FAILED" }
  case object Unknown extends IntegrationActionStatus { val code = "UNKNOWN" }
  val All: List[IntegrationActionStatus] = List(Queued, Running, Succeeded, Failed, Unknown)
  def fromCode(code: String): Option[IntegrationActionStatus] = All.find(_.code == code)
}

/** Why an action exists: a person's one-shot request, or the reconciliation of a desired state. */
sealed trait IntegrationActionSource { def code: String }
object IntegrationActionSource {
  case object Manual extends IntegrationActionSource { val code = "MANUAL" }
  case object DesiredState extends IntegrationActionSource { val code = "DESIRED_STATE" }
  val All: List[IntegrationActionSource] = List(Manual, DesiredState)
  def fromCode(code: String): Option[IntegrationActionSource] = All.find(_.code == code)
}

final case class IntegrationActionTarget(inventoryObjectId: UUID, objectType: IntegrationObjectType,
  externalId: String, displayName: String)

final case class IntegrationActionExecution(id: UUID, organizationId: UUID, integrationId: UUID,
  target: IntegrationActionTarget, requestId: UUID, action: IntegrationActionCode, requestedByUserId: UUID,
  status: IntegrationActionStatus, createdAt: Instant, startedAt: Option[Instant],
  recoverAfterAt: Option[Instant], finishedAt: Option[Instant], claimedBy: Option[UUID],
  claimToken: Option[UUID], errorCode: Option[String], errorMessage: Option[String], updatedAt: Instant,
  requestedByName: Option[String] = None,
  source: IntegrationActionSource = IntegrationActionSource.Manual,
  /** For a desired-state action: which intent, and which version of it, this action executed. */
  desiredStateId: Option[UUID] = None, desiredStateVersion: Option[Long] = None)

sealed trait IntegrationActionRemoteOutcome
object IntegrationActionRemoteOutcome {
  case object Succeeded extends IntegrationActionRemoteOutcome
  final case class DefinitelyFailed(code: String) extends IntegrationActionRemoteOutcome
  final case class OutcomeUnknown(code: String) extends IntegrationActionRemoteOutcome
}
