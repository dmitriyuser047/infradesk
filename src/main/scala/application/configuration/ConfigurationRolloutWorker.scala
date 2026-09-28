package ru.bitec.app.ops
package application.configuration

import application.port._
import cats.MonadThrow
import cats.effect.{IO, Resource}
import cats.syntax.all._
import domain.configuration._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** The orchestrator only changes durable state and creates child deployments; SSH belongs to the deployment worker. */
final class ConfigurationRolloutWorker[Tx[_]: MonadThrow](
  rollouts: ConfigurationRolloutRepository[Tx],
  deployments: ConfigurationDeploymentRepository[Tx],
  ids: IdGenerator[Tx],
  runner: TransactionRunner[IO, Tx],
  owner: UUID,
  leaseDuration: FiniteDuration = 2.minutes
) {
  import ConfigurationRolloutItemState._
  import ConfigurationRolloutState.{Cancelled, Failed, Paused, RolledBack, RollingBack, Running, Succeeded}

  private case object LostLease extends RuntimeException("Rollout lease lost")

  def tick: IO[Unit] = for {
    token <- IO(UUID.randomUUID())
    now <- IO(Instant.now())
    claimed <- runner.run(rollouts.claim(owner, token, now, now.plusMillis(leaseDuration.toMillis)))
    _ <- claimed.traverse_(rollout => process(rollout, token))
  } yield ()

  def run: IO[Nothing] = (tick.handleErrorWith(_ => IO.unit) *> IO.sleep(2.seconds)).foreverM

  private def process(rollout: ConfigurationRollout, token: UUID): IO[Unit] = {
    val heartbeat = (IO.sleep(30.seconds) *> renew(rollout, token)).foreverM
    Resource.make(heartbeat.start)(_.cancel).use { _ =>
      execute(rollout, token).handleErrorWith {
        case LostLease => IO.unit
        case error => IO.raiseError(error)
      }
    }
  }

  private def execute(rollout: ConfigurationRollout, token: UUID): IO[Unit] = for {
    _ <- renew(rollout, token)
    initial <- runner.run(rollouts.items(rollout.organizationId, rollout.id))
    _ <- initial.filter(_.state == Deploying).traverse_(item => reconcile(rollout, token, item))
    current <- runner.run(rollouts.items(rollout.organizationId, rollout.id))
    _ <- decide(rollout, token, current)
  } yield ()

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
                     items: List[ConfigurationRolloutItem]): IO[Unit] = {
    val pending = items.filter(_.state == Pending)
    val deploying = items.exists(_.state == Deploying)
    val failed = items.exists(item => item.state == ConfigurationRolloutItemState.Failed ||
      item.state == ConfigurationRolloutItemState.RolledBack)
    val rollBackAll = rollout.strategy.rollbackMode == ConfigurationRollbackMode.AllApplied &&
      (rollout.state == RollingBack || rollout.cancelRequested || (failed && rollout.strategy.stopOnFailure))
    if (deploying) release(rollout, token, if (rollout.state == RollingBack) RollingBack else Running, None, None)
    else if (rollBackAll) {
      pending.traverse_(item => setItem(rollout, token, item, Pending, Skipped, None)) *>
        items.reverse.find(_.state == ConfigurationRolloutItemState.Succeeded).fold[IO[Unit]] {
          release(rollout, token,
            if (items.exists(_.state == ConfigurationRolloutItemState.Failed)) Failed else RolledBack,
            None, None)
        } { item => queueRollback(rollout, token, item) *>
          release(rollout, token, RollingBack, None, None)
        }
    }
    else if (rollout.cancelRequested || (failed && rollout.strategy.stopOnFailure)) {
      pending.traverse_(item => setItem(rollout, token, item, Pending, Skipped, None)) *>
        release(rollout, token, if (rollout.cancelRequested) Cancelled else Failed, None, None)
    } else if (pending.isEmpty) release(rollout, token, if (failed) Failed else Succeeded, None, None)
    else {
      val completedPosition = items.filter(_.state != Pending).map(_.position).maxOption.getOrElse(-1)
      if (rollout.strategy.pauseSeconds > 0 && completedPosition > rollout.lastPausedPosition) {
        IO(Instant.now()).flatMap { now =>
          release(rollout, token, Paused, Some(now.plusSeconds(rollout.strategy.pauseSeconds.toLong)),
            Some(completedPosition))
        }
      } else {
        val launchedCount = items.count(item => item.state != Pending && item.state != Skipped)
        val size = if (launchedCount < rollout.strategy.canaryCount)
          rollout.strategy.canaryCount - launchedCount else rollout.strategy.batchSize
        pending.take(size).traverse_(item => launch(rollout, token, item)) *>
          release(rollout, token, Running, None, None)
      }
    }
  }

  private def queueRollback(rollout: ConfigurationRollout, token: UUID,
                            item: ConfigurationRolloutItem): IO[Unit] = item.deploymentId match {
    case None => IO.raiseError(new IllegalStateException("Successful rollout item has no deployment"))
    case Some(id) => for {
      now <- IO(Instant.now())
      _ <- runner.run(for {
        queued <- deployments.queueRollback(rollout.organizationId, id, rollout.id, now)
        _ <- if (!queued) ().pure[Tx]
          else rollouts.setItem(rollout.organizationId, rollout.id, token, item.id,
            ConfigurationRolloutItemState.Succeeded, Deploying, None, now).flatMap {
            case true => ().pure[Tx]
            case false => new IllegalStateException("Lost rollout lease while queueing rollback").raiseError[Tx, Unit]
          }
      } yield ())
    } yield ()
  }

  private def launch(rollout: ConfigurationRollout, token: UUID,
                     item: ConfigurationRolloutItem): IO[Unit] = for {
    deploymentId <- IO(UUID.randomUUID())
    now <- IO(Instant.now())
    _ <- runner.run(for {
      id <- ids.nextId
      child = ConfigurationDeployment(id, rollout.organizationId, deploymentId,
        item.assignmentId, item.assignmentVersion, item.resourceId, rollout.profileId,
        rollout.profileRevisionNumber, item.targetPath, item.values, item.desiredSha256,
        item.connectionId, item.connectionUpdatedAt, item.expectedRemoteState, item.policy,
        rollout.actorUserId, now, ConfigurationDeploymentState.Queued,
        ConfigurationDeploymentPhase.Precheck, None, None, None, None, None, None,
        cancelRequested = false, Some(rollout.id), Some(item.id))
      inserted <- deployments.insert(child)
      result <- inserted match {
        case ConfigurationDeploymentInsert.Written =>
          rollouts.setItem(rollout.organizationId, rollout.id, token, item.id,
            Pending, Deploying, Some(id), now).flatMap {
            case true => true.pure[Tx]
            case false => new IllegalStateException("Lost rollout lease while launching child").raiseError[Tx, Boolean]
          }
        case ConfigurationDeploymentInsert.AlreadyActive => false.pure[Tx]
        case ConfigurationDeploymentInsert.Repeated(_) =>
          new IllegalStateException("Unexpected repeated child request").raiseError[Tx, Boolean]
      }
    } yield result)
  } yield ()

  private def setItem(rollout: ConfigurationRollout, token: UUID, item: ConfigurationRolloutItem,
                      expected: ConfigurationRolloutItemState, next: ConfigurationRolloutItemState,
                      deploymentId: Option[UUID]): IO[Unit] = for {
    now <- IO(Instant.now())
    changed <- runner.run(rollouts.setItem(rollout.organizationId, rollout.id, token, item.id,
      expected, next, deploymentId, now))
    _ <- IO.raiseUnless(changed)(LostLease)
  } yield ()

  private def release(rollout: ConfigurationRollout, token: UUID, state: ConfigurationRolloutState,
                      nextAt: Option[Instant], pausedPosition: Option[Int]): IO[Unit] = for {
    now <- IO(Instant.now())
    changed <- runner.run(rollouts.setState(rollout.organizationId, rollout.id, token,
      state, now, nextAt, pausedPosition))
    _ <- IO.raiseUnless(changed)(LostLease)
  } yield ()

  private def renew(rollout: ConfigurationRollout, token: UUID): IO[Unit] = for {
    now <- IO(Instant.now())
    changed <- runner.run(rollouts.renew(rollout.organizationId, rollout.id, token,
      now, now.plusMillis(leaseDuration.toMillis)))
    _ <- IO.raiseUnless(changed)(LostLease)
  } yield ()
}
