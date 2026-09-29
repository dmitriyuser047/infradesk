package ru.bitec.app.ops
package application.integration

import application.port.{ClaimedIntegrationSync, IntegrationSyncStateRepository, TimeProvider, TransactionRunner}
import application.scheduler.SyncBackoffPolicy
import cats.effect.IO
import cats.effect.syntax.all._
import cats.syntax.all._
import domain.integration.IntegrationSyncStatus
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

final case class IntegrationSyncSchedulerSettings(pollInterval: FiniteDuration, interval: FiniteDuration,
  batchSize: Int, maxConcurrency: Int, claimLease: FiniteDuration) {
  require(maxConcurrency > 0, "maxConcurrency must be positive")
  require(batchSize > 0, "batchSize must be positive")
  require(interval.toSeconds > 0, "interval must be positive")
}

/** Automatic observation of enabled integrations, separate from connection synchronization.
  *
  * Due schedules are claimed durably (FOR UPDATE SKIP LOCKED) with a lease, so several instances
  * never run the same integration at once; each claim is fenced by its own token. A failure of one
  * integration never stops the others. Scheduled runs are not audited: nobody asked for them.
  */
final class IntegrationSyncScheduler[Tx[_]](sync: IntegrationSync[Tx], states: IntegrationSyncStateRepository[Tx],
  runner: TransactionRunner[IO, Tx], time: TimeProvider[IO], logger: Logger[IO],
  settings: IntegrationSyncSchedulerSettings, instanceId: UUID) {

  def run: IO[Nothing] =
    (tick.handleErrorWith(error => logError(s"integration.scheduler.tick.failed schedulerInstanceId=$instanceId " +
      s"errorType=${error.getClass.getSimpleName}")) *> IO.sleep(settings.pollInterval)).foreverM

  def tick: IO[Unit] = time.now.flatMap(now =>
    runner.run(states.claimDue(instanceId, settings.batchSize, settings.claimLease.toSeconds, now)))
    .flatMap(_.parTraverseN(settings.maxConcurrency)(claim =>
      runClaim(claim).handleErrorWith(error => logError(s"integration.sync.failed " +
        s"organizationId=${claim.organizationId} integrationId=${claim.integrationId} errorType=${error.getClass.getSimpleName}"))))
    .void

  private def runClaim(claim: ClaimedIntegrationSync): IO[Unit] = {
    val interval = settings.interval.toSeconds
    sync.scheduled(claim.organizationId, claim.integrationId).attempt.flatMap { result =>
      val failed = result match {
        case Right(ScheduledIntegrationSync.Finished(session)) => session.status == IntegrationSyncStatus.Failed
        case Right(_) => false
        case Left(_) => true
      }
      val unchanged = result match {
        case Right(ScheduledIntegrationSync.AlreadyRunning) | Right(ScheduledIntegrationSync.Skipped) => true
        case _ => false
      }
      val failures =
        if (failed) SyncBackoffPolicy.nextFailureCount(claim.consecutiveFailures)
        else if (unchanged) claim.consecutiveFailures
        else 0L
      val delay = if (failed) SyncBackoffPolicy.delaySeconds(interval, failures) else interval
      val logged = result match {
        case Left(error) => logError(s"integration.sync.failed organizationId=${claim.organizationId} " +
          s"integrationId=${claim.integrationId} errorCode=${IntegrationSync.codeOf(error)}")
        case _ => IO.unit
      }
      logged *> time.now.flatMap(now => runner.run(states.completeClaimedRun(claim, now.plusSeconds(delay), failures, now)))
        .flatMap {
          case true => IO.unit
          case false => logger.warn(s"integration.scheduler.claim.lost organizationId=${claim.organizationId} " +
            s"integrationId=${claim.integrationId} schedulerInstanceId=$instanceId").handleErrorWith(_ => IO.unit)
        }
    }
  }

  private def logError(message: String): IO[Unit] = logger.error(message).handleErrorWith(_ => IO.unit)
}
