package ru.bitec.app.ops
package application.provisioning

import application.port.{ProvisioningRunRepository, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** Deletes abandoned preview records only. It has no transport or credential dependency. */
final class ProvisioningPlanCleanup[Tx[_]](
  repository: ProvisioningRunRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  logger: Logger[IO],
  clock: IO[Instant] = IO.realTimeInstant,
  scope: Option[UUID] = None
) {
  import ProvisioningPlanCleanup._

  def tick: IO[Int] = clock.flatMap(now => runner.run(repository.deleteExpiredPlans(
    now.minusMillis(ProvisioningRuns.PlanLifetime24h.toMillis), BatchSize, scope)))

  def run: IO[Nothing] = IO.defer(
    tick.void.handleErrorWith(_ => logger.warn("provisioning.plan_cleanup.failed").handleErrorWith(_ => IO.unit)) *>
      IO.sleep(Interval)).foreverM
}

object ProvisioningPlanCleanup {
  val BatchSize = 500
  val Interval: FiniteDuration = 1.hour
}
