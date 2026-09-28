package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration._

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.time.Instant
import java.util.UUID

sealed abstract class ConfigurationDeploymentError(val code: String, message: String) extends RuntimeException(message)
object ConfigurationDeploymentError {
  case object NotFound extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_NOT_FOUND", "Deployment was not found")
  case object AssignmentNotFound extends ConfigurationDeploymentError("CONFIGURATION_ASSIGNMENT_NOT_FOUND", "Assignment was not found")
  case object AssignmentChanged extends ConfigurationDeploymentError("CONFIGURATION_ASSIGNMENT_CHANGED", "Assignment changed")
  case object ConnectionMissing extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_CONNECTION_NOT_FOUND", "SSH connection was not found")
  case object ConnectionNotSource extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_CONNECTION_NOT_SOURCE", "Connection is not a source of this resource")
  case object ConnectionChanged extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_CONNECTION_CHANGED", "Connection changed")
  case object AlreadyActive extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_ALREADY_ACTIVE", "Another deployment is active on this resource")
  case object DesiredInvalid extends ConfigurationDeploymentError("CONFIGURATION_DESIRED_STATE_INVALID", "Desired state cannot be rendered")
  case object RenderedTooLarge extends ConfigurationDeploymentError("CONFIGURATION_RENDERED_TOO_LARGE", "Desired file is larger than the remote file limit")
  case object InvalidExecution extends ConfigurationDeploymentError("INVALID_REQUEST", "Invalid execution policy")
  case object InvalidExpectedRemote extends ConfigurationDeploymentError("INVALID_REQUEST", "Invalid expected remote state")
  case object RequestReused extends ConfigurationDeploymentError("INVALID_REQUEST", "Request ID belongs to another deployment")
  case object InvalidRetry extends ConfigurationDeploymentError("INVALID_REQUEST", "Only a finished, unsuccessful deployment of this assignment can be retried")
  case object NotCancellable extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_NOT_CANCELLABLE", "Deployment has already finished")
  final case class Remote(failure: RemoteConfigurationFailure)
    extends ConfigurationDeploymentError(failure.code, "Remote configuration is unavailable")
}

final case class ConfigurationDeploymentPreview(
  assignmentId: UUID,
  profileId: UUID,
  resourceId: UUID,
  assignmentVersion: Int,
  profileRevisionNumber: Int,
  targetPath: String,
  remoteExists: Boolean,
  remoteSha256: Option[String],
  /** Remote bytes that are not UTF-8 text are hashed and replaced, but not diffed. */
  remoteText: Boolean,
  desiredSha256: String,
  changed: Boolean,
  atomicReplaceSupported: Boolean,
  diff: ConfigurationDiff,
  connectionId: UUID,
  connectionName: String,
  connectionUpdatedAt: Instant
)

final case class ConfigurationDeploymentDetail(
  item: ConfigurationDeploymentListItem,
  events: List[ConfigurationDeploymentEvent]
)

/** Network work in preview is outside any transaction; a request only inserts a QUEUED snapshot. */
final class ConfigurationDeployments[F[_]: MonadThrow, Tx[_]: MonadThrow](
  assignments: ConfigurationAssignmentRepository[Tx],
  assignmentQuery: ConfigurationAssignmentQuery[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  sources: ConfigurationDeploymentSourceQuery[Tx],
  deployments: ConfigurationDeploymentRepository[Tx],
  remote: RemoteConfigurationTransport[F],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx],
  reads: TransactionRunner[F, Tx],
  writes: TransactionRunner[F, Tx],
  settings: ConfigurationDeploymentSettings = ConfigurationDeploymentSettings.Default
) {
  import ConfigurationDeploymentError._

  private[configuration] final case class Prepared(
    assignment: ConfigurationAssignment,
    values: List[ConfigurationVariableValue],
    rendered: Array[Byte],
    connection: domain.connection.Connection
  ) {
    lazy val desiredSha256: String = ConfigurationDeployment.sha256Bytes(rendered)
  }

  /** Exact assignment version, its explicit values, its immutable revision and an eligible connection. */
  private[configuration] def prepare(organizationId: UUID, assignmentId: UUID, expectedVersion: Int,
                                     connectionId: UUID): F[Prepared] =
    for {
      loaded <- reads.run(for {
        assignment <- assignments.find(organizationId, assignmentId)
          .flatMap(_.filter(_.active).liftTo[Tx](AssignmentNotFound))
        _ <- MonadThrow[Tx].raiseUnless(assignment.version == expectedVersion)(AssignmentChanged)
        values <- assignmentQuery.values(organizationId, assignmentId)
        revision <- profiles.findRevision(organizationId, assignment.profileId, assignment.profileRevisionNumber)
          .flatMap(_.liftTo[Tx](AssignmentChanged))
        source <- sources.resolve(organizationId, assignment.resourceId, connectionId)
      } yield (assignment, values, revision.revision, source))
      (assignment, values, revision, source) = loaded
      connection <- source match {
        case ConfigurationDeploymentSource.Ready(value) => value.pure[F]
        case ConfigurationDeploymentSource.Missing => MonadThrow[F].raiseError[domain.connection.Connection](ConnectionMissing)
        case ConfigurationDeploymentSource.NotSource => MonadThrow[F].raiseError[domain.connection.Connection](ConnectionNotSource)
      }
      rendered <- ConfigurationDesiredState.render(revision, values).leftMap(_ => DesiredInvalid).liftTo[F]
      bytes = rendered.getBytes(StandardCharsets.UTF_8)
      _ <- MonadThrow[F].raiseUnless(bytes.length <= settings.maxRemoteFileBytes)(RenderedTooLarge)
    } yield Prepared(assignment, values, bytes, connection)

  /** Reads the remote target once, bounded, and returns hashes and a diff. Nothing is written or kept. */
  def preview(organizationId: UUID, assignmentId: UUID, expectedVersion: Int,
              connectionId: UUID): F[ConfigurationDeploymentPreview] =
    for {
      prepared <- prepare(organizationId, assignmentId, expectedVersion, connectionId)
      observed <- remote.withSession(prepared.connection) { session =>
        (session.read(prepared.assignment.targetPath, settings.maxRemoteFileBytes), session.supportsAtomicReplace).tupled
      }.adaptError { case failure: RemoteConfigurationFailure => Remote(failure) }
      (file, atomic) = observed
      desired = new String(prepared.rendered, StandardCharsets.UTF_8)
      remoteText = if (file.exists) decode(file.bytes) else Some("")
      diff = remoteText match {
        case Some(text) => ConfigurationLineDiff.between(text, desired)
        case None => ConfigurationDiff("", truncated = true, approximate = true, desired.linesIterator.size, 0)
      }
    } yield ConfigurationDeploymentPreview(prepared.assignment.id, prepared.assignment.profileId,
      prepared.assignment.resourceId, prepared.assignment.version, prepared.assignment.profileRevisionNumber,
      prepared.assignment.targetPath, file.exists, file.sha256, remoteText.isDefined, prepared.desiredSha256,
      !file.sha256.contains(prepared.desiredSha256), atomic, diff,
      prepared.connection.id, prepared.connection.name, prepared.connection.updatedAt)

  /** Accepts one deployment snapshot. The same request ID always answers with the same deployment. */
  def request(actor: ActorContext, assignmentId: UUID, expectedVersion: Int, connectionId: UUID,
              expectedRemote: ExpectedRemoteState, policy: ConfigurationExecutionPolicy,
              requestId: UUID, retryOf: Option[UUID] = None): F[UUID] =
    for {
      _ <- ExpectedRemoteState.validate(expectedRemote).leftMap(_ => InvalidExpectedRemote).liftTo[F]
      _ <- ConfigurationExecutionPolicy.validate(policy).leftMap(_ => InvalidExecution).liftTo[F]
      existing <- reads.run(deployments.findRequest(actor.organizationId, requestId))
      id <- existing match {
        case Some(deployment) if deployment.assignmentId == assignmentId => deployment.id.pure[F]
        case Some(_) => MonadThrow[F].raiseError[UUID](RequestReused)
        case None => for {
          _ <- retryOf.traverse_(previous => reads.run(deployments.find(actor.organizationId, previous)).flatMap {
            case Some(old) if old.assignmentId == assignmentId && old.state.terminal &&
              old.state != ConfigurationDeploymentState.Succeeded => MonadThrow[F].unit
            case _ => MonadThrow[F].raiseError[Unit](InvalidRetry)
          })
          prepared <- prepare(actor.organizationId, assignmentId, expectedVersion, connectionId)
          result <- writes.run(for {
            id <- ids.nextId
            now <- time.now
            deployment = ConfigurationDeployment(id, actor.organizationId, requestId, assignmentId,
              prepared.assignment.version, prepared.assignment.resourceId, prepared.assignment.profileId,
              prepared.assignment.profileRevisionNumber, prepared.assignment.targetPath, prepared.values,
              prepared.desiredSha256, connectionId, prepared.connection.updatedAt,
              expectedRemote, policy, actor.userId, now, ConfigurationDeploymentState.Queued,
              ConfigurationDeploymentPhase.Precheck, None, None, None, None, None, None,
              cancelRequested = false, None, None, retryOf)
            inserted <- deployments.insert(deployment)
            answer <- inserted match {
              case ConfigurationDeploymentInsert.Written =>
                // The audit record commits with the snapshot or neither exists.
                audit.record(actor, AuditAction.ConfigurationDeploymentRequested,
                  AuditTargetType.ConfigurationDeployment, Some(id)).as(id)
              case ConfigurationDeploymentInsert.Repeated(existingId) => existingId.pure[Tx]
              case ConfigurationDeploymentInsert.AlreadyActive =>
                MonadThrow[Tx].raiseError[UUID](AlreadyActive)
            }
          } yield answer)
        } yield result
      }
    } yield id

  def detail(organizationId: UUID, id: UUID): F[ConfigurationDeploymentDetail] =
    reads.run((deployments.view(organizationId, id), deployments.events(organizationId, id)).tupled).flatMap {
      case (Some(item), events) => ConfigurationDeploymentDetail(item, events).pure[F]
      case (None, _) => MonadThrow[F].raiseError(NotFound)
    }

  def history(organizationId: UUID, profileId: Option[UUID], resourceId: Option[UUID],
              before: Option[(Instant, UUID)], limit: Int): F[List[ConfigurationDeploymentListItem]] =
    reads.run(deployments.history(organizationId, profileId, resourceId, before, limit))

  def summaries(organizationId: UUID, assignmentIds: List[UUID]): F[List[ConfigurationDeploymentSummary]] =
    reads.run(deployments.summaries(organizationId, assignmentIds))

  /** Cancels a queued deployment at once; a running one stops at its next safe phase boundary. */
  def cancel(actor: ActorContext, id: UUID): F[Unit] =
    writes.run(for {
      now <- time.now
      found <- deployments.find(actor.organizationId, id)
      _ <- MonadThrow[Tx].raiseWhen(found.isEmpty)(NotFound)
      changed <- deployments.cancel(actor.organizationId, id, now)
      _ <- MonadThrow[Tx].raiseUnless(changed)(NotCancellable)
      _ <- audit.record(actor, AuditAction.ConfigurationDeploymentCancelled,
        AuditTargetType.ConfigurationDeployment, Some(id))
    } yield ())

  private def decode(bytes: Array[Byte]): Option[String] =
    scala.util.Try(StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString).toOption
}
