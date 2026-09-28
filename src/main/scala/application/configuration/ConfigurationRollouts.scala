package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration._

import java.time.Instant
import java.util.UUID

final case class ConfigurationRolloutTarget(assignmentId: UUID, expectedVersion: Int,
                                            connectionId: UUID, policy: ConfigurationExecutionPolicy)
final case class ConfigurationRolloutApprovedTarget(target: ConfigurationRolloutTarget,
  connectionUpdatedAt: Instant, desiredSha256: String, expectedRemoteState: ExpectedRemoteState)
final case class ConfigurationRolloutPreflightItem(target: ConfigurationRolloutTarget,
  ready: Boolean, desiredSha256: Option[String], connectionUpdatedAt: Option[Instant],
  expectedRemoteState: Option[ExpectedRemoteState], addedLines: Int, removedLines: Int,
  errorCode: Option[String])

sealed abstract class ConfigurationRolloutError(val code: String, message: String) extends RuntimeException(message)
object ConfigurationRolloutError {
  case object NotFound extends ConfigurationRolloutError("CONFIGURATION_ROLLOUT_NOT_FOUND", "Rollout was not found")
  case object Invalid extends ConfigurationRolloutError("INVALID_REQUEST", "Invalid rollout request")
  case object Stale extends ConfigurationRolloutError("CONFIGURATION_ASSIGNMENT_CHANGED", "Rollout target changed")
  case object ConnectionChanged extends ConfigurationRolloutError("CONFIGURATION_DEPLOYMENT_CONNECTION_CHANGED", "Connection changed")
  case object DesiredChanged extends ConfigurationRolloutError("CONFIGURATION_PROMOTION_CONFLICT", "Desired content changed")
}

/** Remote preflight is read-only and outside a DB transaction. Launch only commits immutable snapshots. */
final class ConfigurationRollouts[F[_]: MonadThrow, Tx[_]: MonadThrow](
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
  writes: TransactionRunner[F, Tx]
) {
  import ConfigurationRolloutError._

  def preflight(organizationId: UUID, targets: List[ConfigurationRolloutTarget]): F[List[ConfigurationRolloutPreflightItem]] =
    validateTargets(targets) *> targets.traverse { target =>
      deployments.preview(organizationId, target.assignmentId, target.expectedVersion,
        target.connectionId).attempt.map {
        case Right(value) => ConfigurationRolloutPreflightItem(target, ready = true,
          Some(value.desiredSha256), Some(value.connectionUpdatedAt),
          Some(value.remoteSha256.fold[ExpectedRemoteState](ExpectedRemoteState.Missing)(ExpectedRemoteState.Sha256.apply)),
          value.diff.addedLines, value.diff.removedLines, None)
        case Left(error: ConfigurationDeploymentError) => ConfigurationRolloutPreflightItem(target,
          ready = false, None, None, None, 0, 0, Some(error.code))
        case Left(_: integration.ssh.RemoteFileTooLarge) => ConfigurationRolloutPreflightItem(target,
          ready = false, None, None, None, 0, 0, Some("CONFIGURATION_REMOTE_FILE_TOO_LARGE"))
        case Left(_: integration.ssh.SshTransportFailure.HostKeyMismatch) => ConfigurationRolloutPreflightItem(target,
          ready = false, None, None, None, 0, 0, Some("CONFIGURATION_HOST_KEY_MISMATCH"))
        case Left(_) => ConfigurationRolloutPreflightItem(target,
          ready = false, None, None, None, 0, 0, Some("CONFIGURATION_SSH_UNAVAILABLE"))
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
        ExpectedRemoteState.validate(item.expectedRemoteState).leftMap(_ => Invalid).liftTo[F]
    }
    existing <- reads.run(rollouts.findRequest(actor.organizationId, requestId))
    id <- existing match {
      case Some(value) => value.id.pure[F]
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
            false, actor.userId, now, None, None, None, -1)
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

  def detail(organizationId: UUID, id: UUID): F[(ConfigurationRollout, List[ConfigurationRolloutItem])] =
    reads.run((rollouts.find(organizationId, id), rollouts.items(organizationId, id)).tupled)
      .flatMap { case (rollout, items) => rollout.liftTo[F](NotFound).map(_ -> items) }

  def history(organizationId: UUID, before: Option[(Instant, UUID)], limit: Int): F[List[ConfigurationRollout]] =
    reads.run(rollouts.history(organizationId, before, limit))

  def cancel(actor: ActorContext, id: UUID): F[Unit] = writes.run(for {
    changed <- rollouts.cancel(actor.organizationId, id)
    _ <- MonadThrow[Tx].raiseUnless(changed)(NotFound)
    _ <- audit.record(actor, AuditAction.ConfigurationRolloutCancelled,
      AuditTargetType.ConfigurationRollout, Some(id))
  } yield ())

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
    MonadThrow[F].raiseUnless(targets.nonEmpty && targets.size <= 100 &&
      targets.forall(_.expectedVersion >= 1) &&
      targets.map(_.assignmentId).distinct.size == targets.size)(Invalid)
}
