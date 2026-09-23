package ru.bitec.app.ops
package application.scheduler

import application.connector.{ConnectionSyncExecutionFailed, SyncAlreadyRunning, SyncFailure}
import application.port.{
  ConnectionScheduleRepository,
  ConnectionSyncRunner,
  TimeProvider,
  TransactionRunner
}
import domain.connection.ConnectionSchedule

import cats.MonadThrow
import cats.effect.Temporal
import cats.syntax.all._
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.FiniteDuration

final class SyncScheduler[F[_]: Temporal, Tx[_]: MonadThrow](
                                                               connectionScheduleRepository: ConnectionScheduleRepository[Tx],
                                                               connectionSyncRunner: ConnectionSyncRunner[F],
                                                               transactionRunner: TransactionRunner[F, Tx],
                                                               timeProvider: TimeProvider[F],
                                                               logger: Logger[F]
                                                             ) {

  def tick(limit: Int): F[Unit] =
    for {
      now <- timeProvider.now
      dueSchedules <- transactionRunner.run(
        connectionScheduleRepository.findDue(now, limit)
      )
      _ <- dueSchedules.traverse_(syncSchedule)
    } yield ()

  def run(pollInterval: FiniteDuration, limit: Int): F[Nothing] =
    (tick(limit).handleErrorWith(error =>
      logError(s"scheduler.tick.failed errorType=${error.getClass.getSimpleName}")
    ) *> Temporal[F].sleep(pollInterval)).foreverM

  private def syncSchedule(schedule: ConnectionSchedule): F[Unit] = {
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
            val failure = SyncFailure.from(underlying)
            logError(s"scheduler.sync.failed $context errorCode=${failure.code} errorType=${underlying.getClass.getSimpleName} consecutiveFailures=$failures nextDelaySeconds=$delay")
        }
        outcomeLog *> transactionRunner
          .run(
            connectionScheduleRepository.updateAfterRun(
              schedule.organizationId,
              schedule.connectionId,
              finishedAt.plusSeconds(delay),
              failures
            )
          )
          .handleErrorWith(error =>
            logError(s"scheduler.schedule.update.failed $context errorType=${error.getClass.getSimpleName}")
          )
      }
    }
  }

  private def logInfo(message: String): F[Unit] =
    logger.info(message).handleErrorWith(_ => ().pure[F])

  private def logError(message: String): F[Unit] =
    logger.error(message).handleErrorWith(_ => ().pure[F])
}
