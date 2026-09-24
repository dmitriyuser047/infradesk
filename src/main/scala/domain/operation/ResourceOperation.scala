package ru.bitec.app.ops
package domain.operation

import java.time.Instant
import java.util.UUID

sealed trait ResourceOperationCode { def code: String }
object ResourceOperationCode {
  case object ContainerStart extends ResourceOperationCode { val code = "CONTAINER_START" }
  case object ContainerStop extends ResourceOperationCode { val code = "CONTAINER_STOP" }
  case object ContainerRestart extends ResourceOperationCode { val code = "CONTAINER_RESTART" }
  val All: List[ResourceOperationCode] = List(ContainerStart, ContainerStop, ContainerRestart)
  def fromCode(code: String): Either[IllegalArgumentException, ResourceOperationCode] =
    All.find(_.code == code).toRight(new IllegalArgumentException(s"Unsupported resource operation '$code'"))
}

sealed trait OperationExecutionStatus { def code: String }
object OperationExecutionStatus {
  case object Running extends OperationExecutionStatus { val code = "RUNNING" }
  case object Succeeded extends OperationExecutionStatus { val code = "SUCCEEDED" }
  case object Failed extends OperationExecutionStatus { val code = "FAILED" }
  case object Unknown extends OperationExecutionStatus { val code = "UNKNOWN" }
  val All: List[OperationExecutionStatus] = List(Running, Succeeded, Failed, Unknown)
  def fromCode(code: String): Either[IllegalArgumentException, OperationExecutionStatus] =
    All.find(_.code == code).toRight(new IllegalArgumentException(s"Unsupported operation status '$code'"))
}

final case class OperationExecution(
  id: UUID,
  organizationId: UUID,
  resourceId: UUID,
  actorUserId: UUID,
  operation: ResourceOperationCode,
  targetConnectionId: UUID,
  targetExternalType: String,
  targetExternalId: String,
  status: OperationExecutionStatus,
  startedAt: Instant,
  /** When this attempt may be declared abandoned, fixed from the conditions it started under. */
  recoverAfterAt: Instant,
  finishedAt: Option[Instant],
  errorCode: Option[String],
  errorMessage: Option[String],
  createdAt: Instant,
  updatedAt: Instant
)

final case class OperationExecutionCursor(startedAt: Instant, id: UUID)

