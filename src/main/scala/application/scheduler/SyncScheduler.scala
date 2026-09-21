package ru.bitec.app.ops
package application.scheduler

import application.port.{
  ConnectionScheduleRepository,
  ConnectionSynchronizer,
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
                                                               connectionSynchronizer: ConnectionSynchronizer[F],
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
    connectionSynchronizer
      .execute(schedule.organizationId, schedule.connectionId)
      .attempt
      .void *>
      timeProvider.now.flatMap { finishedAt =>
        transactionRunner
          .run(
            connectionScheduleRepository.scheduleNext(
              schedule.organizationId,
              schedule.connectionId,
              finishedAt.plusSeconds(schedule.intervalSeconds)
            )
          )
          .attempt
          .void
      }
}
