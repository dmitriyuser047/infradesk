package ru.bitec.app.ops
package application.configuration

import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.configuration._
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/** Orchestrates rollouts: decides which items start, when, and when the rollout rolls back. It only
  * changes durable state and creates child deployments; SSH belongs to the deployment worker.
  *
  * Each pass claims a rollout under a lease, reconciles items with their child deployments, makes one
  * decision and gives the lease back, so any backend instance can take the next pass. Every write is
  * fenced on the lease token; nothing here depends on a lock held in this process.
  */
final class ConfigurationRolloutWorker[Tx[_]: MonadThrow](
  rollouts: ConfigurationRolloutRepository[Tx],
  deployments: ConfigurationDeploymentRepository[Tx],
  ids: IdGenerator[Tx],
  runner: TransactionRunner[IO, Tx],
  owner: UUID,
  logger: Logger[IO],
  leaseDuration: FiniteDuration = 2.minutes,
  pollInterval: FiniteDuration = 2.seconds,
  clock: IO[Instant] = IO.realTimeInstant,
  /** Restricts the orchestrator to one organization; production orchestrators serve every tenant. */
  scope: Option[UUID] = None
) {
  import ConfigurationRolloutItemState._
  import ConfigurationRolloutState.{Cancelled, Failed, Paused, RolledBack, RollingBack, Running, Succeeded}
  import ConfigurationRolloutWorker._

  /** Claims and advances every claimable rollout, then waits for the next poll. */
  def tick: IO[Unit] = {
    def drain(seen: List[UUID]): IO[Unit] =
      if (seen.size >= MaxPerTick) IO.unit
      else for {
        token <- IO(UUID.randomUUID())
        now <- clock
        claimed <- runner.run(rollouts.claim(owner, token, now, now.plusMillis(leaseDuration.toMillis), seen, scope))
        _ <- claimed.traverse_(rollout => process(rollout, token) *> drain(rollout.id :: seen))
      } yield ()
    drain(Nil)
  }

  def run: IO[Nothing] =
    (tick.handleErrorWith(error => logger.error(error)("configuration.rollout.worker.failed")) *>
      IO.sleep(pollInterval)).foreverM

  private def process(rollout: ConfigurationRollout, token: UUID): IO[Unit] =
    execute(rollout, token).handleErrorWith {
      case LostLease => logger.warn(s"configuration.rollout.lease_lost ${context(rollout)}")
      case error => logger.error(error)(s"configuration.rollout.unexpected ${context(rollout)}")
    }

  private def execute(rollout: ConfigurationRollout, token: UUID): IO[Unit] = for {
    initial <- runner.run(rollouts.items(rollout.organizationId, rollout.id))
    _ <- initial.filter(_.state == Deploying).traverse_(item => reconcile(rollout, token, item))
    current <- runner.run(rollouts.view(rollout.organizationId, rollout.id))
      .flatMap(IO.fromOption(_)(LostLease))
    _ <- decide(rollout, token, current._2)
  } yield ()

  /** Takes a finished child deployment's outcome over into its item. */
  private def reconcile(rollout: ConfigurationRollout, token: UUID, item: ConfigurationRolloutItem): IO[Unit] =
    item.deploymentId match {
      case None => IO.raiseError(new IllegalStateException("Deploying rollout item has no deployment"))
      case Some(id) => runner.run(deployments.find(rollout.organizationId, id)).flatMap {
        case Some(deployment) if deployment.state.terminal =>
          val next = deployment.state match {
            case ConfigurationDeploymentState.Succeeded => ConfigurationRolloutItemState.Succeeded
            case ConfigurationDeploymentState.RolledBack => ConfigurationRolloutItemState.RolledBack
            case _ => ConfigurationRolloutItemState.Failed
          }
          setItem(rollout, token, item, Deploying, next, None)
        case Some(_) => IO.unit
        case None => IO.raiseError(new IllegalStateException("Rollout child deployment disappeared"))
      }
    }

  private def decide(rollout: ConfigurationRollout, token: UUID,
                     views: List[ConfigurationRolloutItemView]): IO[Unit] = {
    val items = views.map(_.item)
    val pending = items.filter(_.state == Pending)
    val deploying = items.exists(_.state == Deploying)
    val failed = items.exists(item => item.state == ConfigurationRolloutItemState.Failed ||
      item.state == ConfigurationRolloutItemState.RolledBack)
    val stopping = rollout.cancelRequested || (failed && rollout.strategy.stopOnFailure)
    val rollBackAll = rollout.state == RollingBack || rollout.rollbackRequested ||
      (rollout.strategy.rollbackMode == ConfigurationRollbackMode.AllApplied && stopping)
    // A rollback of a node that failed its own rollback cannot be vouched for.
    val rollbackFailed = views.exists(_.deploymentState.contains(ConfigurationDeploymentState.RollbackFailed))

    if (deploying)
      // Running deployments always finish their current safe flow; nothing is interrupted.
      release(rollout, token, if (rollout.state == RollingBack) RollingBack else Running, None, None)
    else if (rollBackAll)
      skip(rollout, token, pending) *>
        // Reverse deployment order: the last node applied is the first one restored.
        items.reverse.find(_.state == ConfigurationRolloutItemState.Succeeded).fold[IO[Unit]] {
          val outcome = if (rollbackFailed) Failed else RolledBack
          release(rollout, token, outcome, None, None) *>
            logger.warn(s"configuration.rollout.finished ${context(rollout)} state=${outcome.code}")
        } { item => queueRollback(rollout, token, item) }
    else if (stopping) {
      val outcome = if (rollout.cancelRequested && !failed) Cancelled else Failed
      skip(rollout, token, pending) *> release(rollout, token, outcome, None, None) *>
        logger.warn(s"configuration.rollout.finished ${context(rollout)} state=${outcome.code}")
    } else if (pending.isEmpty) {
      val outcome = if (failed) Failed else Succeeded
      release(rollout, token, outcome, None, None) *>
        logger.info(s"configuration.rollout.finished ${context(rollout)} state=${outcome.code}")
    } else {
      val completedPosition = items.filter(_.state != Pending).map(_.position).maxOption.getOrElse(-1)
      if (rollout.strategy.pauseSeconds > 0 && completedPosition > rollout.lastPausedPosition)
        clock.flatMap { now =>
          release(rollout, token, Paused, Some(now.plusSeconds(rollout.strategy.pauseSeconds.toLong)),
            Some(completedPosition))
        }
      else {
        // The canary goes alone; after it, batches of at most batchSize run at the same time.
        val launched = items.count(item => item.state != Pending && item.state != Skipped)
        val size = if (launched < rollout.strategy.canaryCount) rollout.strategy.canaryCount - launched
          else rollout.strategy.batchSize
        pending.take(size).traverse_(item => launch(rollout, token, item)) *>
          release(rollout, token, Running, None, None)
      }
    }
  }

  private def skip(rollout: ConfigurationRollout, token: UUID, pending: List[ConfigurationRolloutItem]): IO[Unit] =
    pending.traverse_(item => setItem(rollout, token, item, Pending, Skipped, None))

  /** A node that is busy right now stays applied and is retried on a later pass. */
  private def queueRollback(rollout: ConfigurationRollout, token: UUID,
                            item: ConfigurationRolloutItem): IO[Unit] = item.deploymentId match {
    case None => IO.raiseError(new IllegalStateException("Successful rollout item has no deployment"))
    case Some(id) => for {
      now <- clock
      queued <- runner.run(for {
        queued <- deployments.queueRollback(rollout.organizationId, id, rollout.id, now)
        _ <- if (!queued) ().pure[Tx]
          else rollouts.setItem(rollout.organizationId, rollout.id, token, item.id,
            ConfigurationRolloutItemState.Succeeded, Deploying, None, now).flatMap {
            case true => ().pure[Tx]
            case false => MonadThrow[Tx].raiseError[Unit](LostLease)
          }
      } yield queued)
      _ <- if (queued) logger.warn(s"configuration.rollout.node_rollback_queued ${context(rollout)} deploymentId=$id")
        else IO.unit
      _ <- release(rollout, token, RollingBack, None, None)
    } yield ()
  }

  private def launch(rollout: ConfigurationRollout, token: UUID,
                     item: ConfigurationRolloutItem): IO[Unit] = for {
    requestId <- IO(UUID.randomUUID())
    now <- clock
    launched <- runner.run(for {
      id <- ids.nextId
      child = ConfigurationDeployment(id, rollout.organizationId, requestId,
        item.assignmentId, item.assignmentVersion, item.resourceId, rollout.profileId,
        rollout.profileRevisionNumber, item.targetPath, item.values, item.desiredSha256,
        item.connectionId, item.connectionUpdatedAt, item.expectedRemoteState, item.policy,
        rollout.actorUserId, now, ConfigurationDeploymentState.Queued,
        ConfigurationDeploymentPhase.Precheck, None, None, None, None, None, None,
        cancelRequested = false, Some(rollout.id), Some(item.id))
      inserted <- deployments.insert(child)
      // The child and the item move together, or neither does.
      result <- inserted match {
        case ConfigurationDeploymentInsert.Written =>
          rollouts.setItem(rollout.organizationId, rollout.id, token, item.id,
            Pending, Deploying, Some(id), now).flatMap {
            case true => Option(id).pure[Tx]
            case false => MonadThrow[Tx].raiseError[Option[UUID]](LostLease)
          }
        case ConfigurationDeploymentInsert.AlreadyActive => Option.empty[UUID].pure[Tx]
        case ConfigurationDeploymentInsert.Repeated(_) =>
          MonadThrow[Tx].raiseError[Option[UUID]](new IllegalStateException("Unexpected repeated child request"))
      }
    } yield result)
    _ <- launched.fold(logger.info(s"configuration.rollout.node_busy ${context(rollout)} resourceId=${item.resourceId}"))(
      id => logger.info(s"configuration.rollout.node_started ${context(rollout)} deploymentId=$id"))
  } yield ()

  private def setItem(rollout: ConfigurationRollout, token: UUID, item: ConfigurationRolloutItem,
                      expected: ConfigurationRolloutItemState, next: ConfigurationRolloutItemState,
                      deploymentId: Option[UUID]): IO[Unit] = for {
    now <- clock
    changed <- runner.run(rollouts.setItem(rollout.organizationId, rollout.id, token, item.id,
      expected, next, deploymentId, now))
    _ <- IO.raiseUnless(changed)(LostLease)
  } yield ()

  private def release(rollout: ConfigurationRollout, token: UUID, state: ConfigurationRolloutState,
                      nextAt: Option[Instant], pausedPosition: Option[Int]): IO[Unit] = for {
    now <- clock
    changed <- runner.run(rollouts.setState(rollout.organizationId, rollout.id, token,
      state, now, nextAt, pausedPosition))
    _ <- IO.raiseUnless(changed)(LostLease)
  } yield ()

  private def context(rollout: ConfigurationRollout): String =
    s"organizationId=${rollout.organizationId} rolloutId=${rollout.id} profileId=${rollout.profileId} " +
      s"profileRevisionNumber=${rollout.profileRevisionNumber}"
}

object ConfigurationRolloutWorker {
  val MaxPerTick = 20
  private case object LostLease extends RuntimeException("Rollout lease lost") with NoStackTrace
}
