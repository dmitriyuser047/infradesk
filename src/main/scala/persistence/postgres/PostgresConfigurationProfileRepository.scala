package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  ConfigurationActor,
  ConfigurationProfileQuery,
  ConfigurationProfileRepository,
  ConfigurationProfileSummary,
  ConfigurationRevisionSummary,
  ConfigurationRevisionView
}
import cats.syntax.all._
import domain.configuration.{ConfigurationProfile, ConfigurationProfileKind, ConfigurationRevision, ConfigurationValueType, ConfigurationVariableDefinition}
import org.typelevel.doobie.{ConnectionIO, Fragment, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

import PostgresConfigurationProfileRepository.{ProfileRow, VariableRow, profileColumns}

final class PostgresConfigurationProfileRepository extends ConfigurationProfileRepository[ConnectionIO] {

  /** A code already taken in the organization inserts nothing: the unique constraint decides, so
    * two concurrent creations cannot both win.
    */
  override def insertProfile(profile: ConfigurationProfile): ConnectionIO[Boolean] =
    sql"""
      insert into configuration_profile (
        id, organization_id, code, name, description, archived, latest_revision_number, created_at, updated_at, kind
      ) values (
        ${profile.id}, ${profile.organizationId}, ${profile.code}, ${profile.name}, ${profile.description},
        ${profile.archived}, ${profile.latestRevisionNumber}, ${profile.createdAt}, ${profile.updatedAt}, ${profile.kind.code}
      )
      on conflict on constraint uq_configuration_profile_organization_code do nothing
    """.update.run.map(_ == 1)

  override def findForUpdate(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationProfile]] =
    (fr"select" ++ profileColumns ++ fr"from configuration_profile p where p.organization_id = $organizationId and p.id = $id for update")
      .query[ProfileRow].option.map(_.map(_.toDomain))

  override def updateProfile(profile: ConfigurationProfile): ConnectionIO[Unit] =
    sql"""
      update configuration_profile
         set name = ${profile.name},
             description = ${profile.description},
             archived = ${profile.archived},
             latest_revision_number = ${profile.latestRevisionNumber},
             updated_at = ${profile.updatedAt}
       where organization_id = ${profile.organizationId} and id = ${profile.id}
    """.update.run.flatMap { rows =>
      if (rows == 1) ().pure[ConnectionIO]
      else new IllegalStateException(s"Expected to update one configuration profile, affected: $rows").raiseError[ConnectionIO, Unit]
    }

  override def insertRevision(revision: ConfigurationRevision): ConnectionIO[Unit] =
    for {
      _ <- sql"""
        insert into configuration_revision (
          id, organization_id, profile_id, revision_number, template_text, created_by_user_id, created_at
        ) values (
          ${revision.id}, ${revision.organizationId}, ${revision.profileId}, ${revision.revisionNumber},
          ${revision.template}, ${revision.createdByUserId}, ${revision.createdAt}
        )
      """.update.run
      rows = revision.variables.zipWithIndex.map { case (variable, position) =>
        VariableRow(revision.id, revision.organizationId, variable.name, variable.valueType.code, variable.required,
          variable.defaultValue, variable.description, position)
      }
      _ <- Update[VariableRow]("""
        insert into configuration_revision_variable (
          revision_id, organization_id, name, value_type, required, default_value, description, position
        ) values (?, ?, ?, ?, ?, ?, ?, ?)
      """).updateMany(rows)
    } yield ()
}

/** One statement per list, joined with what the page needs to name its rows. */
final class PostgresConfigurationProfileQuery extends ConfigurationProfileQuery[ConnectionIO] {

  override def list(organizationId: UUID, archived: Boolean, limit: Int,
    kind: Option[ConfigurationProfileKind] = None): ConnectionIO[List[ConfigurationProfileSummary]] = {
    val kindFilter = kind.fold(fr"")(value => fr"and p.kind = ${value.code}")
    (fr"select" ++ profileColumns ++ fr""", r.created_at
      from configuration_profile p
      join configuration_revision r
        on r.profile_id = p.id and r.organization_id = p.organization_id
       and r.revision_number = p.latest_revision_number
      where p.organization_id = $organizationId and p.archived = $archived
    """ ++ kindFilter ++ fr"""order by lower(p.name), p.id
      limit $limit
    """).query[(ProfileRow, Instant)].to[List]
      .map(_.map { case (row, latestCreatedAt) => ConfigurationProfileSummary(row.toDomain, latestCreatedAt) })
  }

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationProfile]] =
    (fr"select" ++ profileColumns ++ fr"from configuration_profile p where p.organization_id = $organizationId and p.id = $id")
      .query[ProfileRow].option.map(_.map(_.toDomain))

  override def listRevisions(
    organizationId: UUID,
    profileId: UUID,
    before: Option[Int],
    limit: Int
  ): ConnectionIO[List[ConfigurationRevisionSummary]] = {
    val cursor = before.fold(fr"")(number => fr"and r.revision_number < $number")
    (fr"""
      select r.revision_number,
        (select count(*) from configuration_revision_variable v
          where v.revision_id = r.id and v.organization_id = r.organization_id),
        u.id, u.display_name, r.created_at
      from configuration_revision r
      join user_account u on u.id = r.created_by_user_id
      where r.organization_id = $organizationId and r.profile_id = $profileId
    """ ++ cursor ++ fr"order by r.revision_number desc limit $limit")
      .query[(Int, Long, UUID, String, Instant)].to[List]
      .map(_.map { case (number, variables, userId, displayName, createdAt) =>
        ConfigurationRevisionSummary(number, variables.toInt, ConfigurationActor(userId, displayName), createdAt)
      })
  }

  /** Two statements: the revision with its author, then its variables in their order. */
  override def findRevision(
    organizationId: UUID,
    profileId: UUID,
    revisionNumber: Int
  ): ConnectionIO[Option[ConfigurationRevisionView]] =
    sql"""
      select r.id, r.template_text, r.created_by_user_id, u.display_name, r.created_at
      from configuration_revision r
      join user_account u on u.id = r.created_by_user_id
      where r.organization_id = $organizationId and r.profile_id = $profileId and r.revision_number = $revisionNumber
    """.query[(UUID, String, UUID, String, Instant)].option.flatMap {
      case None => none[ConfigurationRevisionView].pure[ConnectionIO]
      case Some((id, template, userId, displayName, createdAt)) =>
        sql"""
          select revision_id, organization_id, name, value_type, required, default_value, description, position
          from configuration_revision_variable
          where organization_id = $organizationId and revision_id = $id
          order by position
        """.query[VariableRow].to[List].flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO])).map { variables =>
          Some(ConfigurationRevisionView(
            ConfigurationRevision(id, organizationId, profileId, revisionNumber, template, variables, userId, createdAt),
            ConfigurationActor(userId, displayName)))
        }
    }
}

object PostgresConfigurationProfileRepository {

  private[postgres] val profileColumns: Fragment = fr"""
    p.id, p.organization_id, p.code, p.name, p.description, p.archived, p.latest_revision_number,
    p.created_at, p.updated_at, p.kind
  """

  private[postgres] final case class ProfileRow(
    id: UUID,
    organizationId: UUID,
    code: String,
    name: String,
    description: Option[String],
    archived: Boolean,
    latestRevisionNumber: Int,
    createdAt: Instant,
    updatedAt: Instant,
    kind: String
  ) {
    def toDomain: ConfigurationProfile =
      ConfigurationProfile(id, organizationId, code, name, description, archived, latestRevisionNumber, createdAt, updatedAt,
        ConfigurationProfileKind.fromCode(kind).fold(throw _, identity))
  }

  private[postgres] final case class VariableRow(
    revisionId: UUID,
    organizationId: UUID,
    name: String,
    valueType: String,
    required: Boolean,
    defaultValue: Option[String],
    description: Option[String],
    position: Int
  ) {
    def toDomain: Either[IllegalArgumentException, ConfigurationVariableDefinition] =
      ConfigurationValueType.fromCode(valueType).map(ConfigurationVariableDefinition(name, _, required, defaultValue, description))
  }
}
