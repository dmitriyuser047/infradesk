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
  case object AssignmentChanged extends ConfigurationDeploymentError("CONFIGURATION_ASSIGNMENT_CHANGED", "Assignment changed")
  case object ConnectionMissing extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_CONNECTION_NOT_FOUND", "SSH connection was not found")
  case object ConnectionNotSource extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_CONNECTION_NOT_SOURCE", "Connection is not a source of this resource")
  case object ConnectionChanged extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_CONNECTION_CHANGED", "Connection changed")
  case object AlreadyActive extends ConfigurationDeploymentError("CONFIGURATION_DEPLOYMENT_ALREADY_ACTIVE", "Another deployment is active on this resource")
  case object RemoteTooLarge extends ConfigurationDeploymentError("CONFIGURATION_REMOTE_FILE_TOO_LARGE", "Remote file is too large")
  case object InvalidExecution extends ConfigurationDeploymentError("INVALID_REQUEST", "Invalid execution policy")
  case object InvalidExpectedRemote extends ConfigurationDeploymentError("INVALID_REQUEST", "Invalid expected remote state")
  case object InvalidRemoteEncoding extends ConfigurationDeploymentError("CONFIGURATION_REMOTE_FILE_UNSUPPORTED", "Remote file is not UTF-8 text")
}

final case class ConfigurationDeploymentDiff(text: String, truncated: Boolean, addedLines: Int, removedLines: Int)
final case class ConfigurationDeploymentPreview(
  assignmentVersion: Int,
  profileRevisionNumber: Int,
  targetPath: String,
  remoteExists: Boolean,
  remoteSha256: Option[String],
  desiredSha256: String,
  changed: Boolean,
  diff: ConfigurationDeploymentDiff,
  connectionId: UUID,
  connectionName: String,
  connectionUpdatedAt: Instant
)

/** Deterministic bounded line diff; omitted middle lines never enter logs or durable storage. */
object ConfigurationLineDiff {
  val MaxBytes = 65536
  val MaxLines = 200

  def between(before: String, after: String): ConfigurationDeploymentDiff = {
    val oldLines = before.split("\n", -1).toVector
    val newLines = after.split("\n", -1).toVector
    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines(prefix) == newLines(prefix)) prefix += 1
    var suffix = 0
    while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
      oldLines(oldLines.size - 1 - suffix) == newLines(newLines.size - 1 - suffix)) suffix += 1
    val removed = oldLines.slice(prefix, oldLines.size - suffix)
    val added = newLines.slice(prefix, newLines.size - suffix)
    val lines = (removed.map("-" + _) ++ added.map("+" + _)).iterator
    val output = new StringBuilder("--- remote\n+++ desired\n")
    var count = 0
    var truncated = false
    while (lines.hasNext) {
      val line = lines.next()
      if (count >= MaxLines || output.length + line.length + 1 > MaxBytes) truncated = true
      else { output.append(line).append('\n'); count += 1 }
    }
    ConfigurationDeploymentDiff(output.toString, truncated, added.size, removed.size)
  }
}

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
  writes: TransactionRunner[F, Tx]
) {
  import ConfigurationDeploymentError._

  private final case class Prepared(assignment: ConfigurationAssignment, values: List[ConfigurationVariableValue],
                                    rendered: String, connection: domain.connection.Connection)

  private def prepare(organizationId: UUID, assignmentId: UUID, expectedVersion: Int,
                      connectionId: UUID): F[Prepared] =
    for {
      loaded <- reads.run(for {
        assignment <- assignments.find(organizationId, assignmentId)
          .flatMap(_.filter(_.active).liftTo[Tx](AssignmentChanged))
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
      rendered <- ConfigurationDesiredState.render(revision, values)
        .leftMap(_ => AssignmentChanged).liftTo[F]
    } yield Prepared(assignment, values, rendered, connection)

  def preview(organizationId: UUID, assignmentId: UUID, expectedVersion: Int,
              connectionId: UUID): F[ConfigurationDeploymentPreview] =
    for {
      prepared <- prepare(organizationId, assignmentId, expectedVersion, connectionId)
      file <- remote.withSession(prepared.connection)(_.read(prepared.assignment.targetPath, ConfigurationTemplateRenderer.MaxRenderedLength))
      desiredHash = ConfigurationDeployment.sha256(prepared.rendered)
      remoteHash = Option.when(file.exists)(ConfigurationDeployment.sha256Bytes(file.bytes))
      remoteText <- if (!file.exists) "".pure[F] else decode(file.bytes)
    } yield ConfigurationDeploymentPreview(prepared.assignment.version, prepared.assignment.profileRevisionNumber,
      prepared.assignment.targetPath, file.exists, remoteHash, desiredHash,
      remoteHash != Some(desiredHash), ConfigurationLineDiff.between(remoteText, prepared.rendered),
      prepared.connection.id, prepared.connection.name, prepared.connection.updatedAt)

  def request(actor: ActorContext, assignmentId: UUID, expectedVersion: Int, connectionId: UUID,
              expectedRemote: ExpectedRemoteState, policy: ConfigurationExecutionPolicy,
              requestId: UUID): F[UUID] =
    for {
      _ <- ExpectedRemoteState.validate(expectedRemote).leftMap(_ => InvalidExpectedRemote).liftTo[F]
      _ <- ConfigurationExecutionPolicy.validate(policy).leftMap(_ => InvalidExecution).liftTo[F]
      existing <- reads.run(deployments.findRequest(actor.organizationId, requestId))
      id <- existing match {
        case Some(deployment) => deployment.id.pure[F]
        case None => for {
          prepared <- prepare(actor.organizationId, assignmentId, expectedVersion, connectionId)
          result <- writes.run(for {
            id <- ids.nextId
            now <- time.now
            deployment = ConfigurationDeployment(id, actor.organizationId, requestId, assignmentId,
              prepared.assignment.version, prepared.assignment.resourceId, prepared.assignment.profileId,
              prepared.assignment.profileRevisionNumber, prepared.assignment.targetPath, prepared.values,
              ConfigurationDeployment.sha256(prepared.rendered), connectionId, prepared.connection.updatedAt,
              expectedRemote, policy, actor.userId, now, ConfigurationDeploymentState.Queued,
              ConfigurationDeploymentPhase.Precheck, None, None, None, None, None, None,
              cancelRequested = false, None, None)
            inserted <- deployments.insert(deployment)
            answer <- inserted match {
              case ConfigurationDeploymentInsert.Written =>
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

  def detail(organizationId: UUID, id: UUID): F[ConfigurationDeployment] =
    reads.run(deployments.find(organizationId, id)).flatMap(_.liftTo[F](NotFound))

  def cancel(actor: ActorContext, id: UUID): F[Unit] =
    writes.run(for {
      now <- time.now
      changed <- deployments.cancel(actor.organizationId, id, now)
      _ <- MonadThrow[Tx].raiseUnless(changed)(NotFound)
      _ <- audit.record(actor, AuditAction.ConfigurationDeploymentCancelled,
        AuditTargetType.ConfigurationDeployment, Some(id))
    } yield ())

  private def decode(bytes: Array[Byte]): F[String] =
    MonadThrow[F].catchNonFatal(StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString).adaptError { case _ => InvalidRemoteEncoding }
}
