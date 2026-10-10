package ru.bitec.app.ops
package domain.maintenance

import java.time.{Duration, Instant}
import java.util.UUID

/**
 * A planned stretch of time during which one resource and the resources directly beneath it are
 * expected to misbehave. Monitoring keeps its truth; only notifications are withheld.
 */
final case class MaintenanceWindow(
  id: UUID,
  organizationId: UUID,
  resourceId: UUID,
  startsAt: Instant,
  endsAt: Instant,
  reason: String,
  createdBy: UUID,
  createdAt: Instant,
  cancelledAt: Option[Instant],
  cancelledBy: Option[UUID]
) {
  /** When the window stops covering its resources: its end, or its cancellation if earlier. */
  def effectiveEnd: Instant = cancelledAt.filter(_.isBefore(endsAt)).getOrElse(endsAt)

  def state(now: Instant): MaintenanceWindowState =
    if (cancelledAt.exists(!_.isAfter(startsAt))) MaintenanceWindowState.Cancelled
    else if (!effectiveEnd.isAfter(now)) MaintenanceWindowState.Finished
    else if (startsAt.isAfter(now)) MaintenanceWindowState.Scheduled
    else MaintenanceWindowState.Active
}

sealed abstract class MaintenanceWindowState(val code: String)
object MaintenanceWindowState {
  case object Scheduled extends MaintenanceWindowState("SCHEDULED")
  case object Active extends MaintenanceWindowState("ACTIVE")
  case object Finished extends MaintenanceWindowState("FINISHED")
  /** Cancelled before it ever started. */
  case object Cancelled extends MaintenanceWindowState("CANCELLED")
}

object MaintenanceWindow {
  val MaxDuration: Duration = Duration.ofDays(30)
  val MaxReasonLength: Int = 500
  /** How far in the past a new window may begin, to absorb the clock of a slow form. */
  val MaxBackdating: Duration = Duration.ofMinutes(5)
}
