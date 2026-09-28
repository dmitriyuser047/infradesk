package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.effect.Concurrent
import cats.effect.syntax.all._
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration._

import java.time.Instant
import java.util.UUID

final case class ConfigurationRolloutTarget(assignmentId: UUID, expectedVersion: Int,
                                            connectionId: UUID, policy: ConfigurationExecutionPolicy)

/** What an operator approved for one node after preflight. Each child deployment re-checks all of it. */
final case class ConfigurationRolloutApprovedTarget(target: ConfigurationRolloutTarget,
  connectionUpdatedAt: Instant, desiredSha256: String, expectedRemoteState: ExpectedRemoteState)

final case class ConfigurationRolloutPreflightItem(
  target: ConfigurationRolloutTarget,
  ready: Boolean,
  desiredSha256: Option[String],
  connectionUpdatedAt: Option[Instant],
  connectionName: Option[String],
  expectedRemoteState: Option[ExpectedRemoteState],
  changed: Boolean,
  addedLines: Int,
  removedLines: Int,
  errorCode: Option[String]
)

sealed abstract class ConfigurationRolloutError(val code: String, message: String) extends RuntimeException(message)
object ConfigurationRolloutError {
  case object NotFound extends ConfigurationRolloutError("CONFIGURATION_ROLLOUT_NOT_FOUND", "Rollout was not found")
  case object Invalid extends ConfigurationRolloutError("INVALID_REQUEST", "Invalid rollout request")
  case object Stale extends ConfigurationRolloutError("CONFIGURATION_ASSIGNMENT_CHANGED", "Rollout target changed")
  case object ConnectionChanged extends ConfigurationRolloutError("CONFIGURATION_DEPLOYMENT_CONNECTION_CHANGED", "Connection changed")
  case object DesiredChanged extends ConfigurationRolloutError("CONFIGURATION_PROMOTION_CONFLICT", "Desired content changed")
  case object NotCancellable extends ConfigurationRolloutError("CONFIGURATION_ROLLOUT_NOT_CANCELLABLE", "Rollout has already finished")
}

/** Rolling deployment of one profile revision across nodes.
  *
  * Remote preflight is read-only and runs outside any transaction; launching commits immutable
  * per-node snapshots and nothing else. The orchestrator, not this service, creates child
  * deployments, and the deployment worker, not the orchestrator, touches servers.
  */
final class ConfigurationRollouts[F[_]: Concurrent, Tx[_]: MonadThrow](
  assignments: ConfigurationAssignmentRepository[Tx],
  assignmentQuery: ConfigurationAssignmentQuery[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  sources: ConfigurationDeploymentSourceQuery[Tx],
  deployments: ConfigurationDeployments[F, Tx],
  rollouts: ConfigurationRolloutRepository[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx],
  reads: TransactionRunner[F, Tx],
  writes: TransactionRunner[F, Tx],
  settings: ConfigurationDeploymentSettings = ConfigurationDeploymentSettings.Default
) {
  import ConfigurationRolloutError._

  /** Every check a child deployment will make, made now without changing anything remote. */
  def preflight(organizationId: UUID, profileId: UUID, revisionNumber: Int,
                targets: List[ConfigurationRolloutTarget]): F[List[ConfigurationRolloutPreflightItem]] =
    validateTargets(targets) *> MonadThrow[F].raiseUnless(revisionNumber >= 1)(Invalid) *>
      targets.parTraverseN(settings.preflightParallelism) { target =>
        def blocked(code: String) = ConfigurationRolloutPreflightItem(target, ready = false, None, None, None, None,
          changed = false, 0, 0, Some(code))
        if (ConfigurationExecutionPolicy.validate(target.policy).isLeft)
          blocked(ConfigurationRollouts.InvalidExecutionPolicy).pure[F]
        else deployments.preview(organizationId, target.assignmentId, target.expectedVersion, target.connectionId)
          .attempt.map {
            case Right(preview) if preview.profileId != profileId || preview.profileRevisionNumber != revisionNumber =>
              blocked(Stale.code)
            case Right(preview) if !preview.atomicReplaceSupported =>
              blocked(RemoteConfigurationFailure.AtomicReplaceUnsupported.code)
            case Right(preview) => ConfigurationRolloutPreflightItem(target, ready = true,
              Some(preview.desiredSha256), Some(preview.connectionUpdatedAt), Some(preview.connectionName),
              Some(ExpectedRemoteState.of(preview.remoteSha256)), preview.changed,
              preview.diff.addedLines, preview.diff.removedLines, None)
            case Left(error: ConfigurationDeploymentError) => blocked(error.code)
            case Left(_) => blocked(ConfigurationDeploymentWorker.InternalError)
          }
      }

  def launch(actor: ActorContext, profileId: UUID, revisionNumber: Int, requestId: UUID,
             strategy: ConfigurationRolloutStrategy,
             approved: List[ConfigurationRolloutApprovedTarget]): F[UUID] = for {
    _ <- validateTargets(approved.map(_.target))
    _ <- MonadThrow[F].raiseUnless(ConfigurationRolloutStrategy.valid(strategy) &&
      strategy.canaryCount <= approved.size && revisionNumber >= 1)(Invalid)
    _ <- approved.traverse_ { item =>
      ConfigurationExecutionPolicy.validate(item.target.policy).leftMap(_ => Invalid).liftTo[F] *>
        ExpectedRemoteState.validate(item.expectedRemoteState).leftMap(_ => Invalid).liftTo[F] *>
        MonadThrow[F].raiseUnless(item.desiredSha256.matches("[0-9a-f]{64}"))(Invalid)
    }
    existing <- reads.run(rollouts.findRequest(actor.organizationId, requestId))
    id <- existing match {
      case Some(value) if value.profileId == profileId => value.id.pure[F]
      case Some(_) => MonadThrow[F].raiseError[UUID](Invalid)
      case None => for {
        snapshots <- approved.traverse(item => prepare(actor.organizationId, profileId, revisionNumber, item))
        result <- writes.run(for {
          rolloutId <- ids.nextId
          now <- time.now
          items <- snapshots.zipWithIndex.traverse { case ((target, assignment, values), position) =>
            ids.nextId.map(itemId => ConfigurationRolloutItem(itemId, actor.organizationId, rolloutId,
              position, assignment.id, assignment.version, assignment.resourceId, assignment.targetPath,
              target.target.connectionId, target.connectionUpdatedAt, target.desiredSha256,
              target.expectedRemoteState, target.target.policy, values,
              ConfigurationRolloutItemState.Pending, None, now, now))
          }
          rollout = ConfigurationRollout(rolloutId, actor.organizationId, requestId, profileId,
            revisionNumber, ConfigurationRolloutState.Queued, strategy, None, None, None,
            cancelRequested = false, actor.userId, now, None, None, None, -1)
          inserted <- rollouts.insert(rollout, items)
          id <- inserted match {
            case ConfigurationRolloutInsert.Written =>
              audit.record(actor, AuditAction.ConfigurationRolloutRequested,
                AuditTargetType.ConfigurationRollout, Some(rolloutId)).as(rolloutId)
            case ConfigurationRolloutInsert.Repeated(existingId) => existingId.pure[Tx]
          }
        } yield id)
      } yield result
    }
  } yield id

  def detail(organizationId: UUID, id: UUID): F[(ConfigurationRolloutListItem, List[ConfigurationRolloutItemView])] =
    reads.run(rollouts.view(organizationId, id)).flatMap(_.liftTo[F](NotFound))

  def history(organizationId: UUID, profileId: Option[UUID], before: Option[(Instant, UUID)],
              limit: Int): F[List[ConfigurationRolloutListItem]] =
    reads.run(rollouts.history(organizationId, profileId, before, limit))

  /** Future items never start; running ones finish safely. With `rollback`, every node the rollout
    * applied is then restored, in reverse order, whatever its rollback mode.
    */
  def cancel(actor: ActorContext, id: UUID, rollback: Boolean): F[Unit] = writes.run(for {
    found <- rollouts.find(actor.organizationId, id)
    _ <- MonadThrow[Tx].raiseWhen(found.isEmpty)(NotFound)
    now <- time.now
    changed <- rollouts.cancel(actor.organizationId, id, rollback, now)
    _ <- MonadThrow[Tx].raiseUnless(changed)(NotCancellable)
    _ <- audit.record(actor, AuditAction.ConfigurationRolloutCancelled,
      AuditTargetType.ConfigurationRollout, Some(id))
  } yield ())

  /** The approved snapshot must still be exactly what the database says now. */
  private def prepare(organizationId: UUID, profileId: UUID, revisionNumber: Int,
                      approved: ConfigurationRolloutApprovedTarget): F[(ConfigurationRolloutApprovedTarget,
                        ConfigurationAssignment, List[ConfigurationVariableValue])] = for {
    data <- reads.run(for {
      assignment <- assignments.find(organizationId, approved.target.assignmentId)
      values <- assignmentQuery.values(organizationId, approved.target.assignmentId)
      revision <- profiles.findRevision(organizationId, profileId, revisionNumber)
      source <- assignment.traverse(a => sources.resolve(organizationId, a.resourceId, approved.target.connectionId))
    } yield (assignment, values, revision, source))
    (assignment, values, revision, source) = data
    current <- assignment.filter(a => a.active && a.version == approved.target.expectedVersion &&
      a.profileId == profileId && a.profileRevisionNumber == revisionNumber).liftTo[F](Stale)
    connection <- source.collect { case ConfigurationDeploymentSource.Ready(value) => value }.liftTo[F](ConnectionChanged)
    _ <- MonadThrow[F].raiseUnless(connection.updatedAt == approved.connectionUpdatedAt)(ConnectionChanged)
    validRevision <- revision.liftTo[F](Stale)
    rendered <- ConfigurationDesiredState.render(validRevision.revision, values).leftMap(_ => Stale).liftTo[F]
    _ <- MonadThrow[F].raiseUnless(ConfigurationDeployment.sha256(rendered) == approved.desiredSha256)(DesiredChanged)
  } yield (approved, current, values)

  private def validateTargets(targets: List[ConfigurationRolloutTarget]): F[Unit] =
    MonadThrow[F].raiseUnless(targets.nonEmpty && targets.size <= ConfigurationRolloutStrategy.MaxTargets &&
      targets.forall(_.expectedVersion >= 1) &&
      targets.map(_.assignmentId).distinct.size == targets.size)(Invalid)
}

object ConfigurationRollouts {
  val InvalidExecutionPolicy = "CONFIGURATION_EXECUTION_POLICY_INVALID"
}
