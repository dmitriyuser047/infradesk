package ru.bitec.app.ops
package application.metric

import application.port.{MetricRetentionRepository, TransactionRunner}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.metric.MetricResolution
import org.typelevel.log4cats.Logger

import java.time.{Duration, Instant}
import scala.concurrent.duration._

final case class MetricRetentionSettings(
  rawRetention: Duration = Duration.ofDays(7),
  fiveMinuteRetention: Duration = Duration.ofDays(30),
  hourlyRetention: Duration = Duration.ofDays(400),
  interval: FiniteDuration = 5.minutes
) {
  require(!rawRetention.minusDays(1).isNegative, "raw metric retention must be at least one day")
  require(fiveMinuteRetention.compareTo(rawRetention) >= 0, "five-minute retention must cover raw retention")
  require(hourlyRetention.compareTo(fiveMinuteRetention) >= 0, "hourly retention must cover five-minute retention")
  require(interval >= 10.seconds && interval <= 1.hour)
}

object MetricRetention {
  /** Observations are written at sync time; a bucket is closed only once late writes are unlikely. */
  val Lateness: Duration = Duration.ofMinutes(2)
  /** One transaction rolls up at most this much history, so catching up never holds a long one. */
  val MaxSpan: Map[MetricResolution, Duration] =
    Map(MetricResolution.FiveMinutes -> Duration.ofHours(6), MetricResolution.Hour -> Duration.ofDays(2))
  /** Rows deleted per statement and statements per cycle: bounded work, even after a long outage. */
  val PurgeBatch: Int = 10000
  val MaxPurgeBatches: Int = 20

  /** The start of the bucket that contains `at`, buckets being aligned to the Unix epoch. */
  def bucketFloor(at: Instant, bucket: Duration): Instant = {
    val width = bucket.getSeconds
    Instant.ofEpochSecond(Math.floorDiv(at.getEpochSecond, width) * width)
  }

  /** Where the next rollup step ends: closed buckets only, and no more than one span at a time. */
  def nextUntil(watermark: Instant, now: Instant, resolution: MetricResolution): Option[Instant] = {
    val bucket = resolution.bucket.getOrElse(throw new IllegalArgumentException(s"${resolution.code} is not stored"))
    val closed = bucketFloor(now.minus(Lateness), bucket)
    val limit = bucketFloor(watermark.plus(MaxSpan(resolution)), bucket)
    val until = if (closed.isBefore(limit)) closed else limit
    Option.when(until.isAfter(watermark))(until)
  }
}

/**
 * Keeps metric history bounded: rolls closed buckets up, then deletes what has outlived its
 * retention. Raw rows go only once every stored resolution has summarised them. Each step is its
 * own short transaction under an advisory lock; a crash between steps leaves the watermark where
 * the last committed step put it, and the next cycle continues from there.
 */
final class MetricRetention[Tx[_]: Monad](
  repository: MetricRetentionRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  settings: MetricRetentionSettings,
  logger: Logger[IO]
) {
  import MetricRetention._

  /** One upkeep cycle: rollups caught up to now, then bounded deletion. */
  def runOnce(now: Instant): IO[Unit] =
    MetricResolution.Stored.traverse_(catchUp(_, now)) *> purge(now)

  def run: IO[Nothing] =
    (IO.realTimeInstant.flatMap(runOnce)
      .handleErrorWith(failure => logger.error(failure)("metrics.retention.failed")) *>
      IO.sleep(settings.interval)).foreverM

  private def catchUp(resolution: MetricResolution, now: Instant): IO[Unit] =
    runner.run(step(resolution, now)).flatMap {
      case Some(until) => logger.debug(s"metrics.rollup resolution=${resolution.code} until=$until") *> catchUp(resolution, now)
      case None => IO.unit
    }

  private def step(resolution: MetricResolution, now: Instant): Tx[Option[Instant]] =
    repository.tryLock.flatMap {
      case false => none[Instant].pure[Tx]
      case true => repository.watermark(resolution).flatMap {
        case None => none[Instant].pure[Tx]
        case Some(watermark) => nextUntil(watermark, now, resolution) match {
          case None => none[Instant].pure[Tx]
          case Some(until) => repository.rollUp(resolution, watermark, until).map(Option.when(_)(until))
        }
      }
    }

  private def purge(now: Instant): IO[Unit] = {
    def batches(statement: Tx[Int], remaining: Int, total: Int): IO[Int] =
      if (remaining == 0) IO.pure(total)
      else runner.run(repository.tryLock.flatMap(locked => if (locked) statement else 0.pure[Tx])).flatMap {
        case deleted if deleted == PurgeBatch => batches(statement, remaining - 1, total + deleted)
        case deleted => IO.pure(total + deleted)
      }
    for {
      rolledUp <- runner.run(repository.rolledUpUntil)
      // Without a watermark nothing is known to be summarised, so no raw row may go.
      rawCutoff = rolledUp.map(mark =>
        if (mark.isBefore(now.minus(settings.rawRetention))) mark else now.minus(settings.rawRetention))
      raw <- rawCutoff.fold(IO.pure(0))(cutoff =>
        batches(repository.purgeObservations(cutoff, PurgeBatch), MaxPurgeBatches, 0))
      fiveMinutes <- batches(repository.purgeRollups(MetricResolution.FiveMinutes,
        now.minus(settings.fiveMinuteRetention), PurgeBatch), MaxPurgeBatches, 0)
      hours <- batches(repository.purgeRollups(MetricResolution.Hour,
        now.minus(settings.hourlyRetention), PurgeBatch), MaxPurgeBatches, 0)
      _ <- logger.info(s"metrics.retention.purged raw=$raw fiveMinutes=$fiveMinutes hours=$hours")
        .whenA(raw + fiveMinutes + hours > 0)
    } yield ()
  }
}
