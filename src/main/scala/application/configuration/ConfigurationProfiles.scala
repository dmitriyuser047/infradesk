package ru.bitec.app.ops
package application.configuration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{
  ConfigurationProfileQuery,
  ConfigurationProfileRepository,
  ConfigurationProfileSummary,
  ConfigurationRevisionSummary,
  ConfigurationRevisionView,
  IdGenerator,
  TimeProvider
}
import cats.{Monad, MonadThrow}
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration.{ConfigurationLimits, ConfigurationProfile, ConfigurationRevision, ValidatedConfiguration}

import java.util.UUID

/** A business outcome a caller can act on. Unexpected failures are not this. */
final case class ConfigurationError(code: String, override val getMessage: String) extends RuntimeException(getMessage)

object ConfigurationError {
  val NotFoundCode = "CONFIGURATION_PROFILE_NOT_FOUND"
  val RevisionNotFoundCode = "CONFIGURATION_REVISION_NOT_FOUND"
  val CodeExistsCode = "CONFIGURATION_PROFILE_CODE_EXISTS"
  val ArchivedCode = "CONFIGURATION_PROFILE_ARCHIVED"
  val InvalidMetadataCode = "INVALID_CONFIGURATION_PROFILE"

  val notFound: ConfigurationError = ConfigurationError(NotFoundCode, "Configuration profile was not found")
  val codeExists: ConfigurationError = ConfigurationError(CodeExistsCode, "A configuration profile with this code already exists")
  val archived: ConfigurationError = ConfigurationError(ArchivedCode, "The configuration profile is archived")
  def invalidMetadata(field: String): ConfigurationError = ConfigurationError(InvalidMetadataCode, s"Invalid $field")
}

/** Name and description, checked and normalized. The code is fixed when a profile is created. */
final case class ConfigurationProfileMetadata(name: String, description: Option[String])

final case class CreateConfigurationProfileCommand(code: String, metadata: ConfigurationProfileMetadata, content: ValidatedConfiguration)

/** Pure checks of what a request says about a profile, done before any transaction. */
object ConfigurationProfileValidation {
  import ConfigurationLimits._

  def code(raw: String): Either[ConfigurationError, String] =
    Some(raw.trim).filter(_.matches(ProfileCodePattern)).toRight(ConfigurationError.invalidMetadata("code"))

  def metadata(name: String, description: Option[String]): Either[ConfigurationError, ConfigurationProfileMetadata] = {
    val trimmedName = name.trim
    val trimmedDescription = description.map(_.trim).filter(_.nonEmpty)
    if (trimmedName.isEmpty || trimmedName.length > MaxProfileNameLength) Left(ConfigurationError.invalidMetadata("name"))
    else if (trimmedDescription.exists(_.length > MaxProfileDescriptionLength)) Left(ConfigurationError.invalidMetadata("description"))
    else Right(ConfigurationProfileMetadata(trimmedName, trimmedDescription))
  }
}

/** Creating profiles, changing their metadata, archiving them and adding revisions.
  *
  * Content arrives already validated: parsing a large template is pure work and never holds a
  * lock. Each operation is then one short write transaction whose journal entry commits with it —
  * if the journal fails, nothing happened. No I/O leaves the database from here.
  */
final class ConfigurationProfileManagement[Tx[_]: MonadThrow](
  profiles: ConfigurationProfileRepository[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx]
) {

  /** The profile and its revision 1: a profile never exists without content. Both are journalled. */
  def create(actor: ActorContext, command: CreateConfigurationProfileCommand): Tx[(ConfigurationProfile, ConfigurationRevision)] =
    for {
      id <- ids.nextId
      revisionId <- ids.nextId
      now <- time.now
      profile = ConfigurationProfile(id, actor.organizationId, command.code, command.metadata.name,
        command.metadata.description, archived = false, latestRevisionNumber = 1, now, now)
      revision = ConfigurationRevision(revisionId, actor.organizationId, id, 1, command.content.template,
        command.content.variables, actor.userId, now)
      inserted <- profiles.insertProfile(profile)
      _ <- (if (inserted) ().pure[Tx] else MonadThrow[Tx].raiseError[Unit](ConfigurationError.codeExists))
      _ <- profiles.insertRevision(revision)
      _ <- audit.record(actor, AuditAction.ConfigurationProfileCreated, AuditTargetType.ConfigurationProfile, Some(id))
      _ <- audit.record(actor, AuditAction.ConfigurationRevisionCreated, AuditTargetType.ConfigurationProfile, Some(id))
    } yield (profile, revision)

  /** The next revision, numbered under the profile's row lock.
    *
    * Two concurrent saves serialize on that lock: the second reads the number the first wrote, so
    * they become consecutive versions rather than two claims to the same one.
    */
  def appendRevision(actor: ActorContext, profileId: UUID, content: ValidatedConfiguration): Tx[ConfigurationRevision] =
    for {
      profile <- locked(actor.organizationId, profileId)
      _ <- (if (profile.archived) MonadThrow[Tx].raiseError[Unit](ConfigurationError.archived) else ().pure[Tx])
      revisionId <- ids.nextId
      now <- time.now
      next = profile.latestRevisionNumber + 1
      revision = ConfigurationRevision(revisionId, actor.organizationId, profileId, next, content.template,
        content.variables, actor.userId, now)
      _ <- profiles.insertRevision(revision)
      _ <- profiles.updateProfile(profile.copy(latestRevisionNumber = next, updatedAt = now))
      _ <- audit.record(actor, AuditAction.ConfigurationRevisionCreated, AuditTargetType.ConfigurationProfile, Some(profileId))
    } yield revision

  /** Name and description only; revisions are untouched. An archived profile is read-only.
    * Asking for what the profile already says changes nothing and is journalled as nothing.
    */
  def updateMetadata(actor: ActorContext, profileId: UUID, metadata: ConfigurationProfileMetadata): Tx[ConfigurationProfile] =
    locked(actor.organizationId, profileId).flatMap { profile =>
      if (profile.archived) MonadThrow[Tx].raiseError[ConfigurationProfile](ConfigurationError.archived)
      else if (profile.name == metadata.name && profile.description == metadata.description) profile.pure[Tx]
      else for {
        now <- time.now
        next = profile.copy(name = metadata.name, description = metadata.description, updatedAt = now)
        _ <- profiles.updateProfile(next)
        _ <- audit.record(actor, AuditAction.ConfigurationProfileUpdated, AuditTargetType.ConfigurationProfile, Some(profileId))
      } yield next
    }

  /** Archiving keeps the profile and its whole history; it only stops new revisions. It is
    * journalled once: archiving an archived profile changes nothing.
    */
  def archive(actor: ActorContext, profileId: UUID): Tx[ConfigurationProfile] =
    locked(actor.organizationId, profileId).flatMap { profile =>
      if (profile.archived) profile.pure[Tx]
      else for {
        now <- time.now
        next = profile.copy(archived = true, updatedAt = now)
        _ <- profiles.updateProfile(next)
        _ <- audit.record(actor, AuditAction.ConfigurationProfileArchived, AuditTargetType.ConfigurationProfile, Some(profileId))
      } yield next
    }

  private def locked(organizationId: UUID, profileId: UUID): Tx[ConfigurationProfile] =
    profiles.findForUpdate(organizationId, profileId).flatMap(_.liftTo[Tx](ConfigurationError.notFound))
}

/** A profile as its page shows it: metadata and the content of its newest revision. */
final case class ConfigurationProfileDetail(profile: ConfigurationProfile, latestRevision: ConfigurationRevisionView)

/** Reads for the configuration pages. Every one is scoped by organization; another tenant's
  * profile is simply not found.
  */
final class ConfigurationProfileQueries[Tx[_]: Monad](query: ConfigurationProfileQuery[Tx]) {

  def list(organizationId: UUID, archived: Boolean, limit: Int): Tx[List[ConfigurationProfileSummary]] =
    query.list(organizationId, archived, limit)

  /** Two statements for a profile: meant for one read-only snapshot, so they cannot disagree. */
  def detail(organizationId: UUID, profileId: UUID): Tx[Option[ConfigurationProfileDetail]] =
    query.find(organizationId, profileId).flatMap {
      case None => none[ConfigurationProfileDetail].pure[Tx]
      case Some(profile) =>
        query.findRevision(organizationId, profileId, profile.latestRevisionNumber)
          .map(_.map(ConfigurationProfileDetail(profile, _)))
    }

  /** None when the profile is not this organization's; an empty page when it has no more. */
  def revisions(organizationId: UUID, profileId: UUID, before: Option[Int], limit: Int): Tx[Option[List[ConfigurationRevisionSummary]]] =
    query.find(organizationId, profileId).flatMap {
      case None => none[List[ConfigurationRevisionSummary]].pure[Tx]
      case Some(_) => query.listRevisions(organizationId, profileId, before, limit).map(Some(_))
    }

  def revision(organizationId: UUID, profileId: UUID, revisionNumber: Int): Tx[Option[ConfigurationRevisionView]] =
    query.findRevision(organizationId, profileId, revisionNumber)
}

object ConfigurationProfileQueries {
  val DefaultLimit = 50
  val MaxLimit = 200
}
