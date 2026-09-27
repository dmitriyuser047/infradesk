package ru.bitec.app.ops
package support

import application.port.{SecurityEventQuery, SecurityEventRepository}
import cats.effect.IO
import domain.auth.{SecurityEvent, SecurityEventCursor}

import java.time.Instant
import java.util.UUID

final class InMemorySecurityEvents extends SecurityEventRepository[IO] with SecurityEventQuery[IO] {
  private var values: List[SecurityEvent] = Nil
  def recorded: List[SecurityEvent] = synchronized(values)
  override def save(event: SecurityEvent): IO[Unit] = IO(synchronized { values = event :: values })
  override def deleteBefore(cutoff: Instant): IO[Int] = IO(synchronized {
    val (expired, retained) = values.partition(_.occurredAt.isBefore(cutoff)); values = retained; expired.size
  })
  override def listByUser(userId: UUID, before: Option[SecurityEventCursor], limit: Int): IO[List[SecurityEvent]] = IO(synchronized {
    values.filter(_.userId == userId).filter(event => before.forall(c =>
      event.occurredAt.isBefore(c.occurredAt) || (event.occurredAt == c.occurredAt && event.id.toString < c.id.toString)))
      .sortBy(event => (event.occurredAt.toEpochMilli, event.id.toString))(Ordering.Tuple2(Ordering.Long.reverse, Ordering.String.reverse)).take(limit)
  })
}
