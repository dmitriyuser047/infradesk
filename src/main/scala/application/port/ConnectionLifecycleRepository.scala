package ru.bitec.app.ops
package application.port

import java.time.Instant
import java.util.UUID

sealed trait ConnectionDeletion
object ConnectionDeletion {
  case object NotFound extends ConnectionDeletion
  /** Work that still needs the connection is queued or running; it must finish first. */
  case object Busy extends ConnectionDeletion
  /** The secret reference of the deleted connection and how many servers it took with it. */
  final case class Deleted(secretRef: Option[String], deactivatedResources: Int) extends ConnectionDeletion
}

/** Connection changes that span more than the connection row. All run in the caller's transaction. */
trait ConnectionLifecycleRepository[Tx[_]] {
  /** Serializes creating and re-pointing connections of one organization. */
  def lockOrganization(organizationId: UUID): Tx[Unit]

  /**
   * Turns the connection into an inactive tombstone with its schedule disabled, and deactivates
   * the servers only it observes, together with everything beneath them. History stays.
   */
  def delete(organizationId: UUID, connectionId: UUID, now: Instant): Tx[ConnectionDeletion]
}
