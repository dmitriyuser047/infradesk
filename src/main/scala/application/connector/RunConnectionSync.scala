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
  * Monitoring is evaluated after a run that actually reached the connection, successful or not:
  * a connection that cannot be reached is exactly when rules have to notice that their data
  * stopped arriving. A run that never started - unknown, inactive or already running connection -
  * says nothing about the data, so it is skipped. The evaluation is a secondary, best-effort step
  * and never changes the outcome the caller sees.
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
      val monitoring =
        if (startedSynchronizing(outcome)) evaluateMonitoring(organizationId, connectionId, outcome)
        else ().pure[F]

      monitoring *> outcome.liftTo[F]
    }

  /** True only when this run opened a synchronization session of its own: a skipped run leaves
    * the data exactly as the run that is already in progress left it.
    */
  private def startedSynchronizing(outcome: Either[Throwable, ConnectionSyncResult]): Boolean =
    outcome match {
      case Right(_) => true
      case Left(_: ConnectionSyncExecutionFailed) => true
      case Left(_) => false
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
        logger.error(
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
