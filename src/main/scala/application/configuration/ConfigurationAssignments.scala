package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{
  ConfigurationAssignmentCursor,
  ConfigurationAssignmentFilter,
  ConfigurationAssignmentListItem,
  ConfigurationAssignmentQuery,
  ConfigurationAssignmentRepository,
  ConfigurationAssignmentWrite,
  ConfigurationProfileQuery,
  ConfigurationRevisionView,
  ConfigurationTargetQuery,
  IdGenerator,
  TimeProvider,
  TransactionRunner
}
import cats.{Monad, MonadThrow}
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration.{
  ConfigurationAssignment,
  ConfigurationDesiredState,
  ConfigurationLimits,
  ConfigurationProfile,
  ConfigurationRenderError,
  ConfigurationRevision,
  ConfigurationTargetPath,
  ConfigurationVariableValue
}

import java.util.UUID

/** A business outcome of an assignment request a caller can act on. Unexpected failures are not this. */
final case class ConfigurationAssignmentError(code: String, override val getMessage: String, variableName: Option[String] = None)
  extends RuntimeException(getMessage)

object ConfigurationAssignmentError {
  val NotFoundCode = "CONFIGURATION_ASSIGNMENT_NOT_FOUND"
  val TargetNotFoundCode = "CONFIGURATION_TARGET_NOT_FOUND"
  val TargetUnsupportedCode = "CONFIGURATION_TARGET_UNSUPPORTED"
  val TargetInactiveCode = "CONFIGURATION_TARGET_INACTIVE"
  val PathConflictCode = "CONFIGURATION_ASSIGNMENT_PATH_CONFLICT"
  val ChangedCode = "CONFIGURATION_ASSIGNMENT_CHANGED"
  val InvalidCode = "INVALID_CONFIGURATION_ASSIGNMENT"

  val notFound: ConfigurationAssignmentError = ConfigurationAssignmentError(NotFoundCode, "Configuration assignment was not found")
  val targetNotFound: ConfigurationAssignmentError = ConfigurationAssignmentError(TargetNotFoundCode, "Target resource was not found")
  val targetUnsupported: ConfigurationAssignmentError =
    ConfigurationAssignmentError(TargetUnsupportedCode, "Configuration can only be assigned to a node")
  val targetInactive: ConfigurationAssignmentError = ConfigurationAssignmentError(TargetInactiveCode, "Target resource is inactive")
  val pathConflict: ConfigurationAssignmentError =
    ConfigurationAssignmentError(PathConflictCode, "Another assignment already manages this file on this resource")
  val changed: ConfigurationAssignmentError =
    ConfigurationAssignmentError(ChangedCode, "The configuration assignment was changed by someone else")
  val profileNotFound: ConfigurationAssignmentError =
    ConfigurationAssignmentError(ConfigurationError.NotFoundCode, "Configuration profile was not found")
  val revisionNotFound: ConfigurationAssignmentError =
    ConfigurationAssignmentError(ConfigurationError.RevisionNotFoundCode, "Configuration revision was not found")
  val profileArchived: ConfigurationAssignmentError =
    ConfigurationAssignmentError(ConfigurationError.ArchivedCode, "The configuration profile is archived")
  def invalid(field: String): ConfigurationAssignmentError = ConfigurationAssignmentError(InvalidCode, s"Invalid $field")

  /** A value problem, by its code and the variable it concerns; the value itself is never echoed. */
  def values(error: ConfigurationRenderError): ConfigurationAssignmentError =
    ConfigurationAssignmentError(error.code, "The configuration values are not valid", Some(error.variableName).filter(_.nonEmpty))
}

/** What a request says the desired state should be, with its shape already checked. */
final case class ConfigurationAssignmentDraft(profileRevisionNumber: Int, targetPath: String, values: List[ConfigurationVariableValue])

/** Pure checks of a request, done before any read or transaction. */
object ConfigurationAssignmentValidation {

  def values(values: List[ConfigurationVariableValue]): Either[ConfigurationAssignmentError, List[ConfigurationVariableValue]] =
    if (values.sizeIs > ConfigurationLimits.MaxVariables) Left(ConfigurationAssignmentError.invalid("values"))
    else Right(values)

  def draft(
    profileRevisionNumber: Int,
    targetPath: String,
    given: List[ConfigurationVariableValue]
  ): Either[ConfigurationAssignmentError, ConfigurationAssignmentDraft] =
    for {
      _ <- Either.cond(profileRevisionNumber >= 1, (), ConfigurationAssignmentError.invalid("profileRevisionNumber"))
      checked <- values(given)
      path <- ConfigurationTargetPath.validate(targetPath).leftMap(_ => ConfigurationAssignmentError.invalid("targetPath"))
    } yield ConfigurationAssignmentDraft(profileRevisionNumber, path, checked)
}

/** A rendered preview of a draft, or why it cannot be rendered. */
final case class ConfigurationAssignmentPreview(revisionNumber: Int, rendered: Either[ConfigurationRenderError, String])

/** Creating, changing and removing assignments: the desired state only.
  *
  * Each operation reads what it depends on in a read transaction, validates and renders outside any
  * transaction — the work is pure and may be large — and only then opens one short write transaction
  * whose journal entry commits with it. The database, not this class, settles races: the partial
  * unique index on an active target path and a compare-and-set on the assignment's version.
  *
  * Nothing here connects to a server, writes a file or runs a command.
  */
final class ConfigurationAssignments[F[_]: MonadThrow, Tx[_]: MonadThrow](
  assignments: ConfigurationAssignmentRepository[Tx],
  targets: ConfigurationTargetQuery[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx],
  reads: TransactionRunner[F, Tx],
  writes: TransactionRunner[F, Tx]
) {
  import ConfigurationAssignmentError._

  /** Content for a draft; nothing is written. */
  def preview(
    organizationId: UUID,
    profileId: UUID,
    revisionNumber: Int,
    values: List[ConfigurationVariableValue]
  ): F[ConfigurationAssignmentPreview] =
    for {
      _ <- ConfigurationAssignmentValidation.values(values).liftTo[F]
      loaded <- reads.run(revisionOf(organizationId, profileId, revisionNumber))
    } yield ConfigurationAssignmentPreview(revisionNumber, ConfigurationDesiredState.render(loaded._2, values))

  /** A new active assignment on an active node, pinned to the exact revision asked for. */
  def create(actor: ActorContext, resourceId: UUID, profileId: UUID, draft: ConfigurationAssignmentDraft): F[UUID] =
    for {
      loaded <- reads.run(for {
        target <- targets.find(actor.organizationId, resourceId).flatMap(_.liftTo[Tx](targetNotFound))
        _ <- MonadThrow[Tx].raiseUnless(target.resourceTypeCode == ConfigurationAssignments.SupportedTargetType)(targetUnsupported)
        _ <- MonadThrow[Tx].raiseUnless(target.active)(targetInactive)
        revision <- revisionOf(actor.organizationId, profileId, draft.profileRevisionNumber)
      } yield revision)
      (profile, revision) = loaded
      _ <- MonadThrow[F].raiseWhen(profile.archived)(profileArchived)
      _ <- ConfigurationDesiredState.render(revision, draft.values).leftMap(values).liftTo[F]
      id <- writes.run(for {
        id <- ids.nextId
        now <- time.now
        assignment = ConfigurationAssignment(id, actor.organizationId, resourceId, profileId, draft.profileRevisionNumber,
          draft.targetPath, version = 1, removedAt = None, now, now)
        outcome <- assignments.insert(assignment, draft.values)
        _ <- written(outcome)
        _ <- audit.record(actor, AuditAction.ConfigurationAssignmentCreated, AuditTargetType.ConfigurationAssignment, Some(id))
      } yield id)
    } yield id

  /** The whole desired state replaced at once — revision, path and values — if nobody changed it
    * since `expectedVersion`. The resource and the profile of an assignment never change.
    */
  def update(actor: ActorContext, id: UUID, expectedVersion: Int, draft: ConfigurationAssignmentDraft): F[Unit] =
    for {
      current <- reads.run(assignments.find(actor.organizationId, id)).flatMap(_.filter(_.active).liftTo[F](notFound))
      _ <- MonadThrow[F].raiseUnless(current.version == expectedVersion)(changed)
      loaded <- reads.run(revisionOf(actor.organizationId, current.profileId, draft.profileRevisionNumber))
      _ <- ConfigurationDesiredState.render(loaded._2, draft.values).leftMap(values).liftTo[F]
      _ <- writes.run(for {
        now <- time.now
        outcome <- assignments.update(actor.organizationId, id, expectedVersion, draft.profileRevisionNumber,
          draft.targetPath, draft.values, now)
        _ <- written(outcome)
        _ <- audit.record(actor, AuditAction.ConfigurationAssignmentUpdated, AuditTargetType.ConfigurationAssignment, Some(id))
      } yield ())
    } yield ()

  /** Removes the assignment from the desired state. The file on the server is not touched. */
  def remove(actor: ActorContext, id: UUID, expectedVersion: Option[Int]): F[Unit] =
    writes.run(for {
      now <- time.now
      outcome <- assignments.remove(actor.organizationId, id, expectedVersion, now)
      _ <- written(outcome)
      _ <- audit.record(actor, AuditAction.ConfigurationAssignmentRemoved, AuditTargetType.ConfigurationAssignment, Some(id))
    } yield ())

  private def revisionOf(organizationId: UUID, profileId: UUID, revisionNumber: Int): Tx[(ConfigurationProfile, ConfigurationRevision)] =
    for {
      profile <- profiles.find(organizationId, profileId).flatMap(_.liftTo[Tx](profileNotFound))
      revision <- profiles.findRevision(organizationId, profileId, revisionNumber).flatMap(_.liftTo[Tx](revisionNotFound))
    } yield (profile, revision.revision)

  private def written(outcome: ConfigurationAssignmentWrite): Tx[Unit] = outcome match {
    case ConfigurationAssignmentWrite.Written => ().pure[Tx]
    case ConfigurationAssignmentWrite.Missing => MonadThrow[Tx].raiseError(notFound)
    case ConfigurationAssignmentWrite.Stale => MonadThrow[Tx].raiseError(changed)
    case ConfigurationAssignmentWrite.PathTaken => MonadThrow[Tx].raiseError(pathConflict)
  }
}

object ConfigurationAssignments {
  /** Only nodes take configuration for now; the schema itself is not node-specific. */
  val SupportedTargetType = "NODE"
}

/** An assignment as its editor needs it: context, the pinned revision's definitions, the explicit values. */
final case class ConfigurationAssignmentDetail(
  item: ConfigurationAssignmentListItem,
  revision: ConfigurationRevisionView,
  values: List[ConfigurationVariableValue]
)

/** Reads for the assignment views. Every one is scoped by organization; another tenant's
  * assignment is simply not found.
  */
final class ConfigurationAssignmentQueries[Tx[_]: Monad](query: ConfigurationAssignmentQuery[Tx], profiles: ConfigurationProfileQuery[Tx]) {

  def list(
    organizationId: UUID,
    filter: ConfigurationAssignmentFilter,
    before: Option[ConfigurationAssignmentCursor],
    limit: Int
  ): Tx[List[ConfigurationAssignmentListItem]] =
    query.list(organizationId, filter, before, limit)

  /** A fixed number of statements, whatever the number of variables: meant for one read-only snapshot. */
  def detail(organizationId: UUID, id: UUID): Tx[Option[ConfigurationAssignmentDetail]] =
    query.find(organizationId, id).flatMap {
      case None => none[ConfigurationAssignmentDetail].pure[Tx]
      case Some(item) =>
        (profiles.findRevision(organizationId, item.assignment.profileId, item.assignment.profileRevisionNumber),
          query.values(organizationId, id)).mapN((revision, values) => revision.map(ConfigurationAssignmentDetail(item, _, values)))
    }
}

object ConfigurationAssignmentQueries {
  val DefaultLimit = 50
  val MaxLimit = 200
}
