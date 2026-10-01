package ru.bitec.app.ops
package application.provisioning

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.provisioning._
import java.time.Instant
import java.util.UUID

sealed abstract class ProvisioningError(val code: String, message: String) extends RuntimeException(message)
object ProvisioningError {
  case object Disabled extends ProvisioningError("PROVISIONING_DISABLED", "Provisioning is disabled")
  case object NotFound extends ProvisioningError("PROVISIONING_RUN_NOT_FOUND", "Provisioning run was not found")
  case object TargetNotFound extends ProvisioningError("PROVISIONING_TARGET_NOT_FOUND", "Provisioning target was not found")
  case object Ambiguous extends ProvisioningError("PROVISIONING_TARGET_AMBIGUOUS", "Provisioning target is ambiguous")
  case object UnsupportedTarget extends ProvisioningError("PROVISIONING_TARGET_UNSUPPORTED", "Provisioning target is not supported")
  case object RequestReused extends ProvisioningError("PROVISIONING_REQUEST_REUSED", "Request ID belongs to another plan")
  case object AlreadyActive extends ProvisioningError("PROVISIONING_ALREADY_ACTIVE", "Provisioning is already active for this resource")
  case object SourceChanged extends ProvisioningError("PROVISIONING_SOURCE_CHANGED", "SSH source changed after planning")
}

final case class ProvisioningPlan(run: ProvisioningRun, steps: List[ProvisioningStep],
  warnings: List[String], blockingProblems: List[String])

final class ProvisioningRuns[F[_]: MonadThrow, Tx[_]: MonadThrow](
  repository: ProvisioningRunRepository[Tx], targets: ProvisioningTargetQuery[Tx],
  ids: IdGenerator[Tx], time: TimeProvider[Tx], audit: AuditRecorder[Tx],
  reads: TransactionRunner[F, Tx], writes: TransactionRunner[F, Tx], settings: ProvisioningSettings
) {
  import ProvisioningError._

  private def ensureEnabled: F[Unit] = MonadThrow[F].raiseUnless(settings.enabled)(Disabled)

  def plan(organizationId: UUID, resourceId: UUID): F[ProvisioningPlan] = for {
    _ <- ensureEnabled
    target <- reads.run(targets.eligible(organizationId, resourceId)).flatMap {
      case Right(value) => value.pure[F]
      case Left("PROVISIONING_TARGET_AMBIGUOUS") => MonadThrow[F].raiseError[ProvisioningTarget](Ambiguous)
      case Left("PROVISIONING_TARGET_NOT_FOUND") => MonadThrow[F].raiseError[ProvisioningTarget](TargetNotFound)
      case _ => MonadThrow[F].raiseError[ProvisioningTarget](UnsupportedTarget)
    }
    answer <- writes.run(for {
      id <- ids.nextId
      now <- time.now
      snapshot = ProvisioningInputSnapshot(1, ProvisioningRunKind.ServerBaselineCheck, organizationId, resourceId, target.resourceType,
        target.resourceKind, target.connectionId, target.connectionUpdatedAt,
        List(ProvisioningStepKind.Preflight, ProvisioningStepKind.Verify))
      run = ProvisioningRun(id, organizationId, resourceId, None, None, snapshot,
        ProvisioningRunState.Planned, now, now)
      _ <- repository.insertPlan(run)
      stored <- repository.find(organizationId, id)
    } yield ProvisioningPlan(run, stored.fold(snapshot.steps.map(k => ProvisioningStep(id, k,
      ProvisioningStepState.Pending)))(_._2), Nil, Nil))
  } yield answer

  def start(actor: ActorContext, planId: UUID, requestId: UUID): F[ProvisioningRun] = for {
    _ <- ensureEnabled
    existing <- reads.run(repository.findRequest(actor.organizationId, requestId))
    result <- existing match {
      case Some(run) if run.id == planId => run.pure[F]
      case Some(_) => MonadThrow[F].raiseError[ProvisioningRun](RequestReused)
      case None => writes.run(for {
        found <- repository.find(actor.organizationId, planId)
        stored <- found.liftTo[Tx](NotFound)
        planned = stored._1
        unchanged <- targets.unchanged(planned.input)
        _ <- MonadThrow[Tx].raiseUnless(unchanged)(SourceChanged)
        now <- time.now
        queued <- repository.start(actor.organizationId, planId, requestId, actor.userId, now)
        started <- queued.liftTo[Tx](AlreadyActive)
        (run, created) = started
        _ <- MonadThrow[Tx].raiseUnless(run.id == planId)(RequestReused)
        _ <- if (created) audit.record(actor, AuditAction.ProvisioningRunRequested, AuditTargetType.Resource, Some(run.resourceId)) else MonadThrow[Tx].unit
      } yield run)
    }
  } yield result

  def detail(organizationId: UUID, id: UUID): F[(ProvisioningRun, List[ProvisioningStep])] =
    reads.run(repository.find(organizationId, id)).flatMap(_.liftTo[F](NotFound))

  def history(organizationId: UUID, resourceId: Option[UUID], limit: Int): F[List[ProvisioningRun]] =
    reads.run(repository.history(organizationId, resourceId, limit.max(1).min(100)))
}
