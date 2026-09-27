package ru.bitec.app.ops
package application.port

import domain.auth.{SecurityEvent, SecurityEventCursor}

import java.time.Instant
import java.util.UUID

trait SecurityEventRepository[F[_]] {
  def save(event: SecurityEvent): F[Unit]
  def deleteBefore(cutoff: Instant): F[Int]
}

/** Read model is separate from the append/retention port so account presentation never uses a
  * command API or filters a shared event collection in application memory.
  */
trait SecurityEventQuery[F[_]] {
  def listByUser(userId: UUID, before: Option[SecurityEventCursor], limit: Int): F[List[SecurityEvent]]
}
