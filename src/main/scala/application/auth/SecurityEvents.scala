package ru.bitec.app.ops
package application.auth

import application.port.{SecurityEventQuery, SecurityEventRepository, TransactionRunner}
import cats.Functor
import cats.effect.{Async, Temporal}
import cats.syntax.all._
import domain.auth.{SecurityEvent, SecurityEventCursor, SecurityEventPage}
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

final class ListSecurityEvents[Tx[_]: Functor](events: SecurityEventQuery[Tx]) {
  def execute(userId: UUID, before: Option[SecurityEventCursor], limit: Int): Tx[SecurityEventPage] =
    events.listByUser(userId, before, limit + 1).map { rows =>
      val page = rows.take(limit)
      SecurityEventPage(page, rows.drop(limit).headOption.map(row => SecurityEventCursor(row.occurredAt, row.id)))
    }
}
object ListSecurityEvents {
  val DefaultLimit = 50
  val MaxLimit = 100
  def boundedLimit(value: Int): Int = math.max(1, math.min(value, MaxLimit))
}

/** Retains account security history for a fixed, documented period without putting a DELETE on
  * login or account requests. */
final class CleanupSecurityEvents[F[_]: Async, Tx[_]](
  events: SecurityEventRepository[Tx],
  runner: TransactionRunner[F, Tx],
  logger: Logger[F],
  retention: FiniteDuration,
  interval: FiniteDuration
) {
  def run: F[Nothing] =
    (tick.handleErrorWith(error => logger.error(error)("auth.security-events.cleanup.failed")
      .handleErrorWith(_ => Async[F].unit)) *> Temporal[F].sleep(interval)).foreverM

  private def tick: F[Unit] = Async[F].delay(Instant.now()).flatMap { now =>
    runner.run(events.deleteBefore(now.minusSeconds(retention.toSeconds))).void
  }
}
