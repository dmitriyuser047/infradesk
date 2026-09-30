package ru.bitec.app.ops
package application.port

import domain.configuration.{ConfigurationProfile, ConfigurationRevision}

import java.time.Instant
import java.util.UUID

/** Writes configuration profiles. Revisions are only ever inserted: there is no way to change one.
  *
  * Every method is scoped by organization, so a profile of another tenant is never reached.
  */
trait ConfigurationProfileRepository[F[_]] {

  /** False when the organization already has a profile with this code; nothing is written then. */
  def insertProfile(profile: ConfigurationProfile): F[Boolean]

  /** The profile, locked until the end of the transaction. Numbering a revision starts here. */
  def findForUpdate(organizationId: UUID, id: UUID): F[Option[ConfigurationProfile]]

  /** Writes the mutable part of a profile: name, description, archived, latest revision, updated at.
    * The code and the creation time are never rewritten.
    */
  def updateProfile(profile: ConfigurationProfile): F[Unit]

  /** Inserts a revision with its variable definitions, in their order. */
  def insertRevision(revision: ConfigurationRevision): F[Unit]
}

/** Who wrote a revision, as the history shows it. */
final case class ConfigurationActor(id: UUID, displayName: String)

/** A profile as the list shows it: metadata and when its newest revision was written. */
final case class ConfigurationProfileSummary(profile: ConfigurationProfile, latestRevisionCreatedAt: Instant)

/** A revision as the history shows it: no content. */
final case class ConfigurationRevisionSummary(
  revisionNumber: Int,
  variableCount: Int,
  createdBy: ConfigurationActor,
  createdAt: Instant
)

final case class ConfigurationRevisionView(revision: ConfigurationRevision, createdBy: ConfigurationActor)

/** Reads configuration profiles. Each list is one bounded statement, whatever it mentions. */
trait ConfigurationProfileQuery[F[_]] {

  def list(organizationId: UUID, archived: Boolean, limit: Int,
    kind: Option[domain.configuration.ConfigurationProfileKind] = None): F[List[ConfigurationProfileSummary]]

  def find(organizationId: UUID, id: UUID): F[Option[ConfigurationProfile]]

  /** Newest first, starting below `before` when it is given. */
  def listRevisions(organizationId: UUID, profileId: UUID, before: Option[Int], limit: Int): F[List[ConfigurationRevisionSummary]]

  def findRevision(organizationId: UUID, profileId: UUID, revisionNumber: Int): F[Option[ConfigurationRevisionView]]
}
