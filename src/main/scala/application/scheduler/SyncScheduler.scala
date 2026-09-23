package ru.bitec.app.ops
package application.scheduler

import application.connector.SyncAlreadyRunning
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

import scala.concurrent.duration.FiniteDuration

final class SyncScheduler[F[_]: Temporal, Tx[_]: MonadThrow](
                                                               connectionScheduleRepository: ConnectionScheduleRepository[Tx],
                                                               connectionSyncRunner: ConnectionSyncRunner[F],
                                                               transactionRunner: TransactionRunner[F, Tx],
                                                               timeProvider: TimeProvider[F]
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
    (tick(limit).attempt.void *> Temporal[F].sleep(pollInterval)).foreverM

  private def syncSchedule(schedule: ConnectionSchedule): F[Unit] =
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
        transactionRunner
          .run(
            connectionScheduleRepository.updateAfterRun(
              schedule.organizationId,
              schedule.connectionId,
              finishedAt.plusSeconds(delay),
              failures
            )
          )
          .attempt
          .void
      }
    }
}
