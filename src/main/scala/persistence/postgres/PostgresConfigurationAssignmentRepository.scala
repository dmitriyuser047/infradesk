package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  AssignmentProfileView,
  AssignmentResourceView,
  AssignmentRuleView,
  ConfigurationAssignmentCursor,
  ConfigurationAssignmentFilter,
  ConfigurationAssignmentListItem,
  ConfigurationAssignmentQuery,
  ConfigurationAssignmentRepository,
  ConfigurationAssignmentEligibility,
  ConfigurationAssignmentWrite,
  ConfigurationTarget,
  ConfigurationTargetQuery,
  EnvironmentReference,
  ProjectReference
}
import cats.syntax.all._
import domain.configuration.{ConfigurationAssignment, ConfigurationVariableValue}
import org.postgresql.util.PSQLException
import org.typelevel.doobie.{ConnectionIO, Fragment, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

import PostgresConfigurationAssignmentRepository.{AssignmentRow, ContextRow, ValueRow, assignmentColumns}

/** Every change is one conditional statement: the partial unique index on an active target path
  * decides who claims a file, and `version = expected` decides who changes an assignment. A write
  * that loses is told so and writes nothing else.
  */
final class PostgresConfigurationAssignmentRepository extends ConfigurationAssignmentRepository[ConnectionIO] {

  override def lockResource(organizationId: UUID, resourceId: UUID): ConnectionIO[Boolean] =
    sql"""select 1 from resource where organization_id = $organizationId and id = $resourceId for update"""
      .query[Int].option.map(_.isDefined)

  override def createEligibility(organizationId: UUID, resourceId: UUID, profileId: UUID,
                                 revisionNumber: Int): ConnectionIO[ConfigurationAssignmentEligibility] = {
    import ConfigurationAssignmentEligibility._
    // FOR UPDATE conflicts with the resource deactivation/upsert and profile archive UPDATE paths.
    // Lock the resource first, then the profile, for the remainder of this write transaction.
    sql"""
      select rt.code, r.is_active from resource r
      join resource_type rt on rt.id = r.resource_type_id
      where r.organization_id = $organizationId and r.id = $resourceId
      for update of r
    """.query[(String, Boolean)].option.flatMap {
      case None => (TargetMissing: ConfigurationAssignmentEligibility).pure[ConnectionIO]
      case Some((code, _)) if code != "NODE" => (TargetUnsupported: ConfigurationAssignmentEligibility).pure[ConnectionIO]
      case Some((_, false)) => (TargetInactive: ConfigurationAssignmentEligibility).pure[ConnectionIO]
      case Some(_) =>
        sql"""select archived from configuration_profile
               where organization_id = $organizationId and id = $profileId for update"""
          .query[Boolean].option.flatMap {
            case None => (ProfileMissing: ConfigurationAssignmentEligibility).pure[ConnectionIO]
            case Some(true) => (ProfileArchived: ConfigurationAssignmentEligibility).pure[ConnectionIO]
            case Some(false) =>
              sql"""select 1 from configuration_revision
                     where organization_id = $organizationId and profile_id = $profileId
                       and revision_number = $revisionNumber""".query[Int].option.map {
                case Some(_) => Eligible: ConfigurationAssignmentEligibility
                case None => RevisionMissing: ConfigurationAssignmentEligibility
              }
          }
    }
  }

  override def insert(assignment: ConfigurationAssignment, values: List[ConfigurationVariableValue]): ConnectionIO[ConfigurationAssignmentWrite] =
    sql"""
      insert into configuration_assignment (
        id, organization_id, resource_id, profile_id, profile_revision_number, target_path, version,
        removed_at, created_at, updated_at, source_rule_id
      ) values (
        ${assignment.id}, ${assignment.organizationId}, ${assignment.resourceId}, ${assignment.profileId},
        ${assignment.profileRevisionNumber}, ${assignment.targetPath}, ${assignment.version},
        ${assignment.removedAt}, ${assignment.createdAt}, ${assignment.updatedAt}, ${assignment.sourceRuleId}
      )
      on conflict (organization_id, resource_id, target_path) where removed_at is null do nothing
    """.update.run.flatMap[ConfigurationAssignmentWrite] {
      case 1 => insertValues(assignment.organizationId, assignment.id, values).as[ConfigurationAssignmentWrite](ConfigurationAssignmentWrite.Written)
      case _ => (ConfigurationAssignmentWrite.PathTaken: ConfigurationAssignmentWrite).pure[ConnectionIO]
    }

  override def update(
    organizationId: UUID,
    id: UUID,
    expectedVersion: Int,
    profileRevisionNumber: Int,
    targetPath: String,
    values: List[ConfigurationVariableValue],
    at: Instant
  ): ConnectionIO[ConfigurationAssignmentWrite] =
    sql"""
      update configuration_assignment
         set profile_revision_number = $profileRevisionNumber,
             target_path = $targetPath,
             version = version + 1,
             updated_at = $at
       where organization_id = $organizationId and id = $id
         and removed_at is null and version = $expectedVersion
    """.update.run.attemptSql.flatMap[ConfigurationAssignmentWrite] {
      case Right(1) =>
        for {
          _ <- sql"delete from configuration_assignment_value where organization_id = $organizationId and assignment_id = $id".update.run
          _ <- insertValues(organizationId, id, values)
        } yield (ConfigurationAssignmentWrite.Written: ConfigurationAssignmentWrite)
      case Right(_) => lost(organizationId, id)
      // Moving to a path another active assignment claims; the transaction is failed and rolls back.
      case Left(error: PSQLException) if isActiveTargetConflict(error) =>
        (ConfigurationAssignmentWrite.PathTaken: ConfigurationAssignmentWrite).pure[ConnectionIO]
      case Left(error) => error.raiseError[ConnectionIO, ConfigurationAssignmentWrite]
    }

  override def remove(organizationId: UUID, id: UUID, expectedVersion: Int, at: Instant): ConnectionIO[ConfigurationAssignmentWrite] =
    sql"""
      update configuration_assignment
         set removed_at = $at, version = version + 1, updated_at = $at
       where organization_id = $organizationId and id = $id and removed_at is null and version = $expectedVersion
    """.update.run.flatMap {
      case 1 => (ConfigurationAssignmentWrite.Written: ConfigurationAssignmentWrite).pure[ConnectionIO]
      case _ => lost(organizationId, id)
    }

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationAssignment]] =
    (fr"select" ++ assignmentColumns ++ fr"from configuration_assignment a where a.organization_id = $organizationId and a.id = $id")
      .query[AssignmentRow].option.map(_.map(_.toDomain))

  /** Why a conditional change matched nothing: gone (or removed) is "missing", otherwise "stale". */
  private def lost(organizationId: UUID, id: UUID): ConnectionIO[ConfigurationAssignmentWrite] =
    find(organizationId, id).map[ConfigurationAssignmentWrite] {
      case Some(assignment) if assignment.active => ConfigurationAssignmentWrite.Stale
      case _ => ConfigurationAssignmentWrite.Missing
    }

  private def insertValues(organizationId: UUID, id: UUID, values: List[ConfigurationVariableValue]): ConnectionIO[Unit] =
    Update[ValueRow]("""
      insert into configuration_assignment_value (assignment_id, organization_id, name, value) values (?, ?, ?, ?)
    """).updateMany(values.map(value => ValueRow(id, organizationId, value.name, value.value))).void

  private def isActiveTargetConflict(error: PSQLException): Boolean =
    error.getSQLState == "23505" &&
      Option(error.getServerErrorMessage).exists(_.getConstraint == "ux_configuration_assignment_active_target")
}

final class PostgresConfigurationTargetQuery extends ConfigurationTargetQuery[ConnectionIO] {

  override def find(organizationId: UUID, resourceId: UUID): ConnectionIO[Option[ConfigurationTarget]] =
    sql"""
      select r.id, rt.code, r.is_active
      from resource r
      join resource_type rt on rt.id = r.resource_type_id
      where r.organization_id = $organizationId and r.id = $resourceId
    """.query[(UUID, String, Boolean)].option.map(_.map { case (id, code, active) => ConfigurationTarget(id, code, active) })
}

/** One statement per list: the assignment joined on the same organization with its resource, the
  * resource's environment and project, and its profile. A list costs one read however many
  * resources, environments or profiles it mentions, and a row of another tenant can never join.
  */
final class PostgresConfigurationAssignmentQuery extends ConfigurationAssignmentQuery[ConnectionIO] {

  override def list(
    organizationId: UUID,
    filter: ConfigurationAssignmentFilter,
    before: Option[ConfigurationAssignmentCursor],
    limit: Int
  ): ConnectionIO[List[ConfigurationAssignmentListItem]] = {
    val filters = List(
      Some(fr"a.removed_at is null"),
      filter.resourceId.map(id => fr"a.resource_id = $id"),
      filter.profileId.map(id => fr"a.profile_id = $id"),
      filter.ruleId.map(id => fr"a.source_rule_id = $id"),
      before.map(cursor => fr"(a.created_at, a.id) < (${cursor.createdAt}, ${cursor.id})")
    ).flatten.foldLeft(fr"")((all, next) => all ++ fr"and" ++ next)
    (select(organizationId) ++ filters ++ fr"order by a.created_at desc, a.id desc limit $limit")
      .query[(AssignmentRow, ContextRow)].to[List]
      .map(_.map { case (row, context) => context.toItem(row.toDomain) })
  }

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[ConfigurationAssignmentListItem]] =
    (select(organizationId) ++ fr"and a.id = $id")
      .query[(AssignmentRow, ContextRow)].option
      .map(_.map { case (row, context) => context.toItem(row.toDomain) })

  override def values(organizationId: UUID, assignmentId: UUID): ConnectionIO[List[ConfigurationVariableValue]] =
    sql"""
      select name, value from configuration_assignment_value
      where organization_id = $organizationId and assignment_id = $assignmentId
      order by name
    """.query[(String, String)].to[List].map(_.map { case (name, value) => ConfigurationVariableValue(name, value) })

  private def select(organizationId: UUID): Fragment =
    fr"select" ++ assignmentColumns ++ fr""",
        r.name, r.code, rt.code, r.is_active,
        p.id, p.name, e.id, e.name, e.kind,
        cp.code, cp.name, cp.archived, cp.latest_revision_number,
        sr.code, sr.name
      from configuration_assignment a
      join resource r on r.id = a.resource_id and r.organization_id = a.organization_id
      join resource_type rt on rt.id = r.resource_type_id
      join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
      join project p on p.id = e.project_id and p.organization_id = e.organization_id
      join configuration_profile cp on cp.id = a.profile_id and cp.organization_id = a.organization_id
      left join configuration_assignment_rule sr on sr.id = a.source_rule_id and sr.organization_id = a.organization_id
      where a.organization_id = $organizationId
    """
}

object PostgresConfigurationAssignmentRepository {

  private[postgres] val assignmentColumns: Fragment = fr"""
    a.id, a.organization_id, a.resource_id, a.profile_id, a.profile_revision_number, a.target_path,
    a.version, a.removed_at, a.created_at, a.updated_at, a.source_rule_id
  """

  private[postgres] final case class AssignmentRow(
    id: UUID,
    organizationId: UUID,
    resourceId: UUID,
    profileId: UUID,
    profileRevisionNumber: Int,
    targetPath: String,
    version: Int,
    removedAt: Option[Instant],
    createdAt: Instant,
    updatedAt: Instant,
    sourceRuleId: Option[UUID]
  ) {
    def toDomain: ConfigurationAssignment =
      ConfigurationAssignment(id, organizationId, resourceId, profileId, profileRevisionNumber, targetPath, version,
        removedAt, createdAt, updatedAt, sourceRuleId)
  }

  private[postgres] final case class ContextRow(
    resourceName: String,
    resourceCode: String,
    resourceTypeCode: String,
    resourceActive: Boolean,
    projectId: UUID,
    projectName: String,
    environmentId: UUID,
    environmentName: String,
    environmentKind: String,
    profileCode: String,
    profileName: String,
    profileArchived: Boolean,
    latestRevisionNumber: Int,
    ruleCode: Option[String],
    ruleName: Option[String]
  ) {
    def toItem(assignment: ConfigurationAssignment): ConfigurationAssignmentListItem =
      ConfigurationAssignmentListItem(
        assignment,
        AssignmentResourceView(assignment.resourceId, resourceName, resourceCode, resourceTypeCode, resourceActive,
          ProjectReference(projectId, projectName), EnvironmentReference(environmentId, environmentName, environmentKind)),
        AssignmentProfileView(assignment.profileId, profileCode, profileName, profileArchived, latestRevisionNumber),
        (assignment.sourceRuleId, ruleCode, ruleName).mapN(AssignmentRuleView.apply)
      )
  }

  private[postgres] final case class ValueRow(assignmentId: UUID, organizationId: UUID, name: String, value: String)
}
