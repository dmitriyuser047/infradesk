package ru.bitec.app.ops
package application.operation

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.history.{HistoryEntry, HistoryRecorder}
import application.port._
import cats.{Monad, MonadThrow}
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.history.HistoryEventType
import domain.operation._
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

sealed abstract class ResourceOperationError(val code: String, val safeMessage: String)
  extends RuntimeException(safeMessage)
final case class OperationResourceNotFound() extends ResourceOperationError("RESOURCE_NOT_FOUND", "Resource was not found")
final case class OperationUnsupported() extends ResourceOperationError("OPERATION_UNSUPPORTED", "Operations are not available for this resource")
final case class OperationTargetAmbiguous() extends ResourceOperationError("OPERATION_TARGET_AMBIGUOUS", "More than one operation target was found")
final case class OperationAlreadyRunning() extends ResourceOperationError("OPERATION_ALREADY_RUNNING", "Another operation is already running")
final case class OperationCompletionConflict() extends ResourceOperationError("OPERATION_COMPLETION_CONFLICT", "Operation result could not be recorded")
final case class ResourceOperationFailure(override val code: String, override val safeMessage: String, cause: Throwable = null)
  extends ResourceOperationError(code, safeMessage) { if (cause != null) initCause(cause) }

final case class AvailableResourceOperations(operations: List[ResourceOperationCode], unavailableReason: Option[String])
final case class PreparedResourceOperation(execution: OperationExecution, target: ResourceOperationTarget,
  recoveredExecutions: List[OperationExecution])

object ResourceOperationPolicy {
  /** The floor: even a fast transport keeps its running operation for this long. */
  val MinimumStaleAfter: FiniteDuration = 10.minutes

  /** Room for connection setup, the completion transaction and clock skew between instances. */
  val SafetyMargin: FiniteDuration = 1.minute

  /** A running execution is abandoned only once its own attempt can no longer be in flight.
    *
    * Recovering earlier would let a second SSH command start next to a first one that is still
    * running, which is exactly the duplicate remediation the design rules out.
    */
  def staleAfter(attemptBudget: FiniteDuration): FiniteDuration =
    if (attemptBudget + SafetyMargin > MinimumStaleAfter) attemptBudget + SafetyMargin
    else MinimumStaleAfter

  val UnknownCode = "OPERATION_RESULT_UNKNOWN"
  val UnknownMessage = "Operation result is unknown because execution was interrupted"
}

final class ResourceOperationPreparation[Tx[_]: MonadThrow](
  targets: ResourceOperationTargetQuery[Tx], executions: OperationExecutionRepository[Tx],
  ids: IdGenerator[Tx], time: TimeProvider[Tx], audit: AuditRecorder[Tx],
  history: HistoryRecorder[Tx], budget: ResourceOperationBudget
) {
  def availability(organizationId: UUID, resourceId: UUID): Tx[AvailableResourceOperations] =
    targets.find(organizationId, resourceId).flatMap {
      case None => OperationResourceNotFound().raiseError[Tx, AvailableResourceOperations]
      case Some(value) if !value.resourceActive || value.resourceTypeCode != "CONTAINER" =>
        AvailableResourceOperations(Nil, Some("RESOURCE_UNSUPPORTED")).pure[Tx]
      case Some(value) if value.targets.size > 1 =>
        AvailableResourceOperations(Nil, Some("OPERATION_TARGET_AMBIGUOUS")).pure[Tx]
      case Some(value) if value.targets.size == 1 =>
        AvailableResourceOperations(ResourceOperationCode.All, None).pure[Tx]
      case Some(_) => AvailableResourceOperations(Nil, Some("OPERATION_TARGET_UNAVAILABLE")).pure[Tx]
    }

  def prepare(actor: ActorContext, resourceId: UUID, operation: ResourceOperationCode): Tx[PreparedResourceOperation] =
    for {
      projection <- targets.find(actor.organizationId, resourceId).flatMap(_.liftTo[Tx](OperationResourceNotFound()))
      _ <- MonadThrow[Tx].raiseWhen(!projection.resourceActive || projection.resourceTypeCode != "CONTAINER")(OperationUnsupported())
      target <- projection.targets match {
        case one :: Nil => one.pure[Tx]
        case Nil => OperationUnsupported().raiseError[Tx, ResourceOperationTarget]
        case _ => OperationTargetAmbiguous().raiseError[Tx, ResourceOperationTarget]
      }
      id <- ids.nextId
      now <- time.now
      // Each running execution carries its own deadline, so recovery never reads settings that
      // may have changed since that execution sent its command.
      recovered <- executions.recoverStaleRunning(actor.organizationId, resourceId, now,
        ResourceOperationPolicy.UnknownCode, ResourceOperationPolicy.UnknownMessage)
      staleAfter = ResourceOperationPolicy.staleAfter(budget.maxAttemptDuration(target))
      execution = OperationExecution(id, actor.organizationId, resourceId, actor.userId, operation,
        target.connection.id, target.externalType, target.externalId, OperationExecutionStatus.Running,
        now, now.plusMillis(staleAfter.toMillis), None, None, None, now, now)
      created <- executions.tryCreateRunning(execution)
      _ <- MonadThrow[Tx].raiseUnless(created)(OperationAlreadyRunning())
      _ <- audit.record(actor, auditAction(operation), AuditTargetType.Resource, Some(resourceId))
      // Retired executions and this request are facts of the same transaction: if the journal
      // cannot take them, nothing is created and no remote command is sent.
      _ <- history.recordAll(
        recovered.map(value =>
          HistoryEntry.system(value.organizationId, HistoryEventType.OperationUnknown,
            value.finishedAt.getOrElse(now))
            .copy(resourceId = Some(value.resourceId), operationExecutionId = Some(value.id))
        ) :+
          HistoryEntry.user(actor.organizationId, HistoryEventType.OperationRequested,
            execution.startedAt, actor.userId)
            .copy(resourceId = Some(resourceId), operationExecutionId = Some(execution.id))
      )
    } yield PreparedResourceOperation(execution, target, recovered)

  private def auditAction(value: ResourceOperationCode): AuditAction = value match {
    case ResourceOperationCode.ContainerStart => AuditAction.ContainerStartRequested
    case ResourceOperationCode.ContainerStop => AuditAction.ContainerStopRequested
    case ResourceOperationCode.ContainerRestart => AuditAction.ContainerRestartRequested
  }
}

final class ExecuteResourceOperation[Tx[_]: Monad](
  preparation: ResourceOperationPreparation[Tx], executions: OperationExecutionRepository[Tx],
  executor: ResourceOperationExecutor[IO], runner: TransactionRunner[IO, Tx], time: TimeProvider[IO],
  history: HistoryRecorder[Tx], logger: Logger[IO]
) {
  def execute(actor: ActorContext, resourceId: UUID, operation: ResourceOperationCode): IO[OperationExecution] =
    runner.run(preparation.prepare(actor, resourceId, operation)).handleErrorWith { error =>
      val code = error match { case value: ResourceOperationError => value.code; case _ => "OPERATION_REJECTED" }
      logger.warn(s"operation.rejected organizationId=${actor.organizationId} resourceId=$resourceId " +
        s"operationCode=${operation.code} errorCode=$code").handleErrorWith(_ => IO.unit) *> IO.raiseError(error)
    }.flatTap(prepared =>
      prepared.recoveredExecutions.traverse_(recovered =>
        logger.warn(s"operation.unknown operationExecutionId=${recovered.id} organizationId=${actor.organizationId} " +
          s"resourceId=$resourceId operationCode=${operation.code}").handleErrorWith(_ => IO.unit)
      ) *> logInfo("operation.requested", prepared.execution)
    ).flatMap { prepared =>
      (logInfo("operation.started", prepared.execution) *> executor.execute(prepared.target, operation)).attempt.flatMap { result =>
        time.now.flatMap { finishedAt =>
          result match {
            case Right(_) => runner.run(complete(prepared.execution, finishedAt,
              HistoryEventType.OperationSucceeded,
              executions.markSucceeded(actor.organizationId, prepared.execution.id, finishedAt)))
              .flatMap(requireUpdated(prepared.execution)).as(prepared.execution.copy(status = OperationExecutionStatus.Succeeded,
                finishedAt = Some(finishedAt), updatedAt = finishedAt)).flatTap(value => logInfo("operation.succeeded", value))
            case Left(failure: ResourceOperationFailure) =>
              runner.run(complete(prepared.execution, finishedAt, HistoryEventType.OperationFailed,
                executions.markFailed(actor.organizationId, prepared.execution.id, finishedAt,
                  failure.code, failure.safeMessage))).flatMap(requireUpdated(prepared.execution))
                .as(prepared.execution.copy(status = OperationExecutionStatus.Failed, finishedAt = Some(finishedAt),
                  errorCode = Some(failure.code), errorMessage = Some(failure.safeMessage), updatedAt = finishedAt))
                .flatTap(value => logFailure(value, failure.code))
            case Left(error) =>
              val failure = ResourceOperationFailure("OPERATION_EXECUTION_FAILED", "Operation execution failed", error)
              runner.run(complete(prepared.execution, finishedAt, HistoryEventType.OperationFailed,
                executions.markFailed(actor.organizationId, prepared.execution.id, finishedAt,
                  failure.code, failure.safeMessage))).flatMap(requireUpdated(prepared.execution))
                .as(prepared.execution.copy(status = OperationExecutionStatus.Failed, finishedAt = Some(finishedAt),
                  errorCode = Some(failure.code), errorMessage = Some(failure.safeMessage), updatedAt = finishedAt))
                .flatTap(value => logFailure(value, failure.code))
          }
        }
      }
    }

  /** The terminal update and its journal entry share one short transaction. The entry is only
    * written when this instance still owned the running execution.
    */
  private def complete(
    execution: OperationExecution,
    finishedAt: Instant,
    eventType: HistoryEventType,
    update: Tx[Boolean]
  ): Tx[Boolean] =
    update.flatMap { updated =>
      if (!updated) false.pure[Tx]
      else
        history.record(
          HistoryEntry.system(execution.organizationId, eventType, finishedAt)
            .copy(resourceId = Some(execution.resourceId), operationExecutionId = Some(execution.id))
        ).as(true)
    }

  private def requireUpdated(execution: OperationExecution)(updated: Boolean): IO[Unit] =
    if (updated) IO.unit
    else logger.error(
      s"operation.completion.conflict operationExecutionId=${execution.id} organizationId=${execution.organizationId} " +
        s"resourceId=${execution.resourceId} operationCode=${execution.operation.code}"
    ).handleErrorWith(_ => IO.unit) *> IO.raiseError(OperationCompletionConflict())

  private def logInfo(event: String, execution: OperationExecution): IO[Unit] =
    logger.info(s"$event operationExecutionId=${execution.id} organizationId=${execution.organizationId} " +
      s"resourceId=${execution.resourceId} operationCode=${execution.operation.code} status=${execution.status.code}")
      .handleErrorWith(_ => IO.unit)

  private def logFailure(execution: OperationExecution, errorCode: String): IO[Unit] =
    logger.warn(s"operation.failed operationExecutionId=${execution.id} organizationId=${execution.organizationId} " +
      s"resourceId=${execution.resourceId} operationCode=${execution.operation.code} status=${execution.status.code} " +
      s"errorCode=$errorCode").handleErrorWith(_ => IO.unit)
}

final class ListResourceOperationExecutions[Tx[_]: Monad](
  targets: ResourceOperationTargetQuery[Tx], executions: OperationExecutionRepository[Tx]
) {
  def execute(organizationId: UUID, resourceId: UUID, cursor: Option[OperationExecutionCursor], limit: Int): Tx[Option[List[OperationExecution]]] =
    targets.find(organizationId, resourceId).flatMap {
      case None => none[List[OperationExecution]].pure[Tx]
      case Some(_) => executions.listByResource(organizationId, resourceId, cursor, limit).map(_.some)
    }
}
