package ru.bitec.app.ops
package application.monitor

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{IncidentAcknowledgement, IncidentAcknowledgementRepository, MaintenanceWindowCancellation,
  MaintenanceWindowRepository, MaintenanceWindowView, ResourceRepository, TransactionRunner}
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.maintenance.MaintenanceWindow

import java.time.Instant
import java.util.UUID

final case class MaintenanceError(code: String, override val getMessage: String) extends RuntimeException(getMessage)

final case class CreateMaintenanceWindow(resourceId: UUID, startsAt: Instant, endsAt: Instant, reason: String)

/**
 * Planning, listing and cancelling maintenance windows. A window changes no monitoring state: the
 * evaluation reads it when an incident opens and silences that incident's notifications.
 */
final class MaintenanceWindows[Tx[_]: MonadThrow](
  windows: MaintenanceWindowRepository[Tx],
  resources: ResourceRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  audit: AuditRecorder[Tx]
) {
  def create(actor: ActorContext, request: CreateMaintenanceWindow): IO[MaintenanceWindowView] =
    for {
      now <- IO.realTimeInstant
      id <- IO.randomUUID
      reason = request.reason.trim
      _ <- IO.fromEither(validate(request.copy(reason = reason), now))
      view <- runner.run(for {
        resource <- resources.findById(actor.organizationId, request.resourceId)
        _ <- resource.filter(_.isActive).liftTo[Tx](MaintenanceError("RESOURCE_NOT_FOUND", "Resource was not found"))
        _ <- windows.insert(MaintenanceWindow(id, actor.organizationId, request.resourceId, request.startsAt,
          request.endsAt, reason, actor.userId, now, None, None))
        _ <- audit.record(actor, AuditAction.MaintenanceWindowCreated, AuditTargetType.MaintenanceWindow, Some(id))
        created <- windows.find(actor.organizationId, id)
        view <- created.liftTo[Tx](new IllegalStateException("A created maintenance window could not be read back"))
      } yield view)
    } yield view

  def cancel(actor: ActorContext, id: UUID): IO[MaintenanceWindowView] =
    IO.realTimeInstant.flatMap(now => runner.run(windows.cancel(actor.organizationId, id, actor.userId, now).flatMap {
      case MaintenanceWindowCancellation.NotFound =>
        MonadThrow[Tx].raiseError[Unit](MaintenanceError("MAINTENANCE_WINDOW_NOT_FOUND", "Maintenance window was not found"))
      case MaintenanceWindowCancellation.Finished =>
        MonadThrow[Tx].raiseError[Unit](MaintenanceError("MAINTENANCE_WINDOW_FINISHED", "Maintenance window has already ended"))
      // Cancelling twice is answered with the window as it stands, and journalled once.
      case MaintenanceWindowCancellation.Unchanged(_) => MonadThrow[Tx].unit
      case MaintenanceWindowCancellation.Cancelled(_) =>
        audit.record(actor, AuditAction.MaintenanceWindowCancelled, AuditTargetType.MaintenanceWindow, Some(id))
    } *> windows.find(actor.organizationId, id).flatMap(
      _.liftTo[Tx](MaintenanceError("MAINTENANCE_WINDOW_NOT_FOUND", "Maintenance window was not found")))))

  def list(organizationId: UUID): IO[(Instant, List[MaintenanceWindowView])] =
    IO.realTimeInstant.flatMap(now => runner.run(windows.list(organizationId, now, MaintenanceWindows.ListLimit)).map(now -> _))

  private def validate(request: CreateMaintenanceWindow, now: Instant): Either[MaintenanceError, Unit] = {
    def check(valid: Boolean, message: String) = Either.cond(valid, (), MaintenanceError("INVALID_REQUEST", message))
    for {
      _ <- check(request.reason.nonEmpty && request.reason.length <= MaintenanceWindow.MaxReasonLength,
        s"Reason must be 1 to ${MaintenanceWindow.MaxReasonLength} characters")
      _ <- check(request.endsAt.isAfter(request.startsAt), "The window must end after it starts")
      _ <- check(!request.endsAt.isAfter(request.startsAt.plus(MaintenanceWindow.MaxDuration)),
        s"A window lasts at most ${MaintenanceWindow.MaxDuration.toDays} days")
      // Backdating would silence incidents that already notified nobody about their cause.
      _ <- check(!request.startsAt.isBefore(now.minus(MaintenanceWindow.MaxBackdating)), "A window cannot start in the past")
    } yield ()
  }
}

object MaintenanceWindows {
  val ListLimit: Int = 200
}

/** Records who took an open incident in hand; the first acknowledgement stands. */
final class AcknowledgeIncident[Tx[_]: MonadThrow](
  acknowledgements: IncidentAcknowledgementRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  audit: AuditRecorder[Tx]
) {
  def execute(actor: ActorContext, incidentId: UUID): IO[Unit] =
    IO.realTimeInstant.flatMap(now => runner.run(
      acknowledgements.acknowledge(actor.organizationId, incidentId, actor.userId, now).flatMap {
        case IncidentAcknowledgement.Acknowledged =>
          audit.record(actor, AuditAction.IncidentAcknowledged, AuditTargetType.Incident, Some(incidentId))
        case IncidentAcknowledgement.AlreadyAcknowledged => MonadThrow[Tx].unit
        case IncidentAcknowledgement.NotOpen =>
          MonadThrow[Tx].raiseError[Unit](MaintenanceError("INCIDENT_NOT_OPEN", "Only an open incident can be acknowledged"))
        case IncidentAcknowledgement.NotFound =>
          MonadThrow[Tx].raiseError[Unit](MaintenanceError("INCIDENT_NOT_FOUND", "Incident was not found"))
      }))
}
