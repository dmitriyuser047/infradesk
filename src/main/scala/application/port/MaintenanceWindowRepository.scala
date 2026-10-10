package ru.bitec.app.ops
package application.port

import domain.maintenance.MaintenanceWindow

import java.time.Instant
import java.util.UUID

/** A maintenance window as lists show it, with the names a person reads. */
final case class MaintenanceWindowView(
  window: MaintenanceWindow,
  resourceName: String,
  resourceTypeCode: String,
  createdByName: String,
  cancelledByName: Option[String]
)

sealed trait MaintenanceWindowCancellation
object MaintenanceWindowCancellation {
  final case class Cancelled(window: MaintenanceWindow) extends MaintenanceWindowCancellation
  /** Already cancelled before: nothing changed. */
  final case class Unchanged(window: MaintenanceWindow) extends MaintenanceWindowCancellation
  /** It ended on its own; there is nothing left to cancel. */
  case object Finished extends MaintenanceWindowCancellation
  case object NotFound extends MaintenanceWindowCancellation
}

trait MaintenanceWindowRepository[Tx[_]] {
  def insert(window: MaintenanceWindow): Tx[Unit]

  /** Ends a scheduled or active window now; idempotent for one already cancelled. */
  def cancel(organizationId: UUID, id: UUID, by: UUID, now: Instant): Tx[MaintenanceWindowCancellation]

  /** Windows that have not ended at `now`, then the most recent finished ones, newest first. */
  def list(organizationId: UUID, now: Instant, limit: Int): Tx[List[MaintenanceWindowView]]

  def find(organizationId: UUID, id: UUID): Tx[Option[MaintenanceWindowView]]
}

sealed trait IncidentAcknowledgement
object IncidentAcknowledgement {
  case object Acknowledged extends IncidentAcknowledgement
  /** Someone acknowledged it before; the first acknowledgement stands. */
  case object AlreadyAcknowledged extends IncidentAcknowledgement
  /** Resolved incidents are history and are not acknowledged afterwards. */
  case object NotOpen extends IncidentAcknowledgement
  case object NotFound extends IncidentAcknowledgement
}

trait IncidentAcknowledgementRepository[Tx[_]] {
  def acknowledge(organizationId: UUID, incidentId: UUID, by: UUID, at: Instant): Tx[IncidentAcknowledgement]
}
