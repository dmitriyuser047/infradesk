package ru.bitec.app.ops
package application.auth

import application.port.{LoginThrottleRepository, TransactionRunner}
import cats.effect.{Async, Temporal}
import cats.syntax.all._
import org.typelevel.log4cats.Logger

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** Keeps the login-throttle table bounded: every interval it removes rows untouched for the
  * retention period that are not currently blocked. The condition is inside the DELETE, so a row
  * that took a failure since the sweep began is never removed. It is a light periodic sweep, not a
  * delete on every login, and a failure of one sweep only logs and waits for the next.
  */
final class CleanupLoginThrottle[F[_]: Async, Tx[_]](
  throttle: LoginThrottleRepository[Tx],
  runner: TransactionRunner[F, Tx],
  logger: Logger[F],
  retention: FiniteDuration,
  interval: FiniteDuration
) {

  def tick: F[Unit] =
    for {
      now <- Async[F].delay(Instant.now())
      deleted <- runner.run(throttle.deleteStale(now.minusSeconds(retention.toSeconds), now))
      _ <- if (deleted > 0) logInfo(s"auth.login.throttle.cleanup deleted=$deleted") else ().pure[F]
    } yield ()

  def run: F[Nothing] =
    (tick.handleErrorWith(error =>
      logError(s"auth.login.throttle.cleanup.failed errorType=${error.getClass.getSimpleName}")
    ) *> Temporal[F].sleep(interval)).foreverM

  private def logInfo(message: String): F[Unit] = logger.info(message).handleErrorWith(_ => Async[F].unit)
  private def logError(message: String): F[Unit] = logger.error(message).handleErrorWith(_ => Async[F].unit)
}
