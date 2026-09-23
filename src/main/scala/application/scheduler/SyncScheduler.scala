package ru.bitec.app.ops
package application.scheduler

import application.connector.{ConnectionSyncExecutionFailed, SyncAlreadyRunning, SyncFailure}
import application.port.{
  ConnectionScheduleRepository,
  ConnectionSyncRunner,
  ResourceConnectorFailure,
  TimeProvider,
  TransactionRunner
}
import cats.{MonadThrow, Parallel}
import cats.effect.{Async, Temporal}
import cats.effect.implicits._
import cats.effect.std.Queue
import cats.syntax.all._
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.FiniteDuration

final class SyncScheduler[F[_]: Async, Tx[_]: MonadThrow](
                                                               connectionScheduleRepository: ConnectionScheduleRepository[Tx],
                                                               connectionSyncRunner: ConnectionSyncRunner[F],
                                                               transactionRunner: TransactionRunner[F, Tx],
                                                               timeProvider: TimeProvider[F],
                                                               logger: Logger[F],
                                                               maxConcurrency: Int,
                                                               schedulerInstanceId: java.util.UUID = new java.util.UUID(0L, 0L),
                                                               claimLease: FiniteDuration = scala.concurrent.duration.FiniteDuration(15, scala.concurrent.duration.MINUTES)
                                                             ) {

  require(maxConcurrency > 0, "maxConcurrency must be positive")

  def tick(limit: Int): F[Unit] =
    tickWith(limit)

  def run(pollInterval: FiniteDuration, limit: Int): F[Nothing] =
    (tickWith(limit).handleErrorWith(error =>
      logError(s"scheduler.tick.failed schedulerInstanceId=$schedulerInstanceId errorType=${error.getClass.getSimpleName}", error)
    ) *> Temporal[F].sleep(pollInterval)).foreverM

  private def tickWith(limit: Int): F[Unit] =
    for {
      _ <- timeProvider.now
      claimedSchedules <- transactionRunner.run(
        connectionScheduleRepository.claimDue(schedulerInstanceId, limit, claimLease.toSeconds)
      )
      _ <- processDueSchedules(claimedSchedules)
    } yield ()

  private def processDueSchedules(
                                   claimedSchedules: List[ClaimedConnectionSchedule]
                                 ): F[Unit] =
    if (claimedSchedules.isEmpty) ().pure[F]
    else {
      val workerCount = math.min(maxConcurrency, claimedSchedules.size)

      Queue.unbounded[F, Option[ClaimedConnectionSchedule]].flatMap { queue =>
        claimedSchedules.traverse_(schedule => queue.offer(Some(schedule))) *>
          List.fill(workerCount)(()).traverse_(_ => queue.offer(None)) *>
          Parallel.parTraverse_(List.fill(workerCount)(()))(_ => worker(queue))
      }
    }

  private def worker(
                      queue: Queue[F, Option[ClaimedConnectionSchedule]]
                    ): F[Unit] =
    queue.take.flatMap {
      case Some(schedule) =>
        syncSchedule(schedule)
          .handleErrorWith(error => logUnexpectedScheduleFailure(schedule, error)) *>
          worker(queue)
      case None => ().pure[F]
    }

  private def syncSchedule(claimed: ClaimedConnectionSchedule): F[Unit] = {
    val schedule = claimed.schedule
    val context = s"organizationId=${schedule.organizationId} connectionId=${schedule.connectionId}"
    logInfo(s"scheduler.sync.started $context") *>
      connectionSyncRunner.execute(schedule.organizationId, schedule.connectionId).attempt.flatMap { result =>
      timeProvider.now.flatMap { finishedAt =>
        val failures = result match {
          case Right(_) => 0L
          case Left(_: SyncAlreadyRunning) => schedule.consecutiveFailures
          case Left(_) => SyncBackoffPolicy.nextFailureCount(schedule.consecutiveFailures)
        }
        val delay = result match {
          case Right(_) | Left(_: SyncAlreadyRunning) => schedule.intervalSeconds
          case Left(_) => SyncBackoffPolicy.delaySeconds(schedule.intervalSeconds, failures)
        }
        val outcomeLog = result match {
          case Right(value) => logInfo(s"scheduler.sync.completed $context syncSessionId=${value.sessionId} consecutiveFailures=$failures nextDelaySeconds=$delay")
          case Left(_: SyncAlreadyRunning) => logInfo(s"scheduler.sync.skipped $context reason=already_running consecutiveFailures=$failures nextDelaySeconds=$delay")
          case Left(error) =>
            val underlying = error match {
              case wrapped: ConnectionSyncExecutionFailed => wrapped.underlying
              case other => other
            }
            val sessionContext = error match {
              case wrapped: ConnectionSyncExecutionFailed => s" syncSessionId=${wrapped.sessionId}"
              case _ => ""
            }
            val failure = SyncFailure.from(underlying)
            val message = s"scheduler.sync.failed $context$sessionContext errorCode=${failure.code} errorType=${underlying.getClass.getSimpleName} consecutiveFailures=$failures nextDelaySeconds=$delay"
            underlying match {
              case _: ResourceConnectorFailure => logError(message)
              case _ => logError(message, underlying)
            }
        }
        outcomeLog *> transactionRunner
          .run(
            connectionScheduleRepository.completeClaimedRun(
              schedule.organizationId,
              schedule.connectionId,
              claimed.claimedBy,
              finishedAt.plusSeconds(delay),
              failures
            )
          )
          .flatMap {
            case true => ().pure[F]
            case false => logger.warn(s"scheduler.claim.lost $context schedulerInstanceId=$schedulerInstanceId").handleErrorWith(_ => ().pure[F])
          }.handleErrorWith(error => logError(s"scheduler.schedule.update.failed $context errorType=${error.getClass.getSimpleName}", error))
      }
    }
  }

  private def logInfo(message: String): F[Unit] =
    logger.info(message).handleErrorWith(_ => ().pure[F])

  private def logError(message: String): F[Unit] =
    logger.error(message).handleErrorWith(_ => ().pure[F])

  private def logError(message: String, error: Throwable): F[Unit] =
    logger.error(error)(message).handleErrorWith(_ => ().pure[F])

  private def logUnexpectedScheduleFailure(claimed: ClaimedConnectionSchedule, error: Throwable): F[Unit] =
    logError(
      s"scheduler.sync.failed organizationId=${claimed.schedule.organizationId} connectionId=${claimed.schedule.connectionId} errorType=${error.getClass.getSimpleName}",
      error
    )
}
