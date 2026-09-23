package ru.bitec.app.ops
package application.connector

import application.monitor.MonitorRuleEvaluator
import application.port.{ConnectionSynchronizer, ConnectionSyncResult, ConnectionSyncRunner, TimeProvider, TransactionRunner}
import cats.MonadThrow
import cats.syntax.all._
import org.typelevel.log4cats.Logger

import java.util.UUID

/** Shared execution semantics for scheduled and manually triggered runs.
  *
  * Monitoring is evaluated after every run, successful or not: a connection that cannot be
  * reached is exactly when rules have to notice that their data stopped arriving. The evaluation
  * is a secondary, best-effort step and never changes the outcome the caller sees.
  */
final class RunConnectionSync[F[_]: MonadThrow, Tx[_]](
  synchronizer: ConnectionSynchronizer[F],
  evaluator: MonitorRuleEvaluator[Tx],
  runner: TransactionRunner[F, Tx],
  timeProvider: TimeProvider[F],
  logger: Logger[F]
) extends ConnectionSyncRunner[F] {

  override def execute(organizationId: UUID, connectionId: UUID): F[ConnectionSyncResult] =
    synchronizer.execute(organizationId, connectionId).attempt.flatMap { outcome =>
      evaluateMonitoring(organizationId, connectionId, outcome) *> outcome.liftTo[F]
    }

  private def evaluateMonitoring(
    organizationId: UUID,
    connectionId: UUID,
    outcome: Either[Throwable, ConnectionSyncResult]
  ): F[Unit] =
    timeProvider.now
      .flatMap(now => runner.run(evaluator.execute(organizationId, connectionId, now)))
      .flatMap(_.traverse_(transition =>
        logger.info(transition.logMessage).handleErrorWith(_ => ().pure[F])
      ))
      .handleErrorWith(error =>
        logger.error(error)(
          s"monitor.evaluation.failed organizationId=$organizationId connectionId=$connectionId " +
            s"syncSessionId=${syncSessionId(outcome)} errorType=${error.getClass.getSimpleName}"
        ).handleErrorWith(_ => ().pure[F])
      )

  private def syncSessionId(outcome: Either[Throwable, ConnectionSyncResult]): String =
    outcome match {
      case Right(result) => result.sessionId.toString
      case Left(ConnectionSyncExecutionFailed(sessionId, _)) => sessionId.toString
      case Left(_) => "none"
    }
}
