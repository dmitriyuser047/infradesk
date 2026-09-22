package ru.bitec.app.ops
package application.connector

import application.monitor.MonitorRuleEvaluator
import application.port.{ConnectionSynchronizer, ConnectionSyncResult, ConnectionSyncRunner, TimeProvider, TransactionRunner}
import cats.MonadThrow
import cats.syntax.all._

import java.util.UUID

/** Shared execution semantics for scheduled and manually triggered runs. */
final class RunConnectionSync[F[_]: MonadThrow, Tx[_]](
  synchronizer: ConnectionSynchronizer[F],
  evaluator: MonitorRuleEvaluator[Tx],
  runner: TransactionRunner[F, Tx],
  timeProvider: TimeProvider[F]
) extends ConnectionSyncRunner[F] {
  override def execute(organizationId: UUID, connectionId: UUID): F[ConnectionSyncResult] =
    synchronizer.execute(organizationId, connectionId).flatTap { result =>
      timeProvider.now.flatMap(now => runner.run(evaluator.execute(result.resources, now)))
        .attempt.void
    }
}
