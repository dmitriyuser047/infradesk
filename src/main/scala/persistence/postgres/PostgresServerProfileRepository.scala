package ru.bitec.app.ops
package persistence.postgres

import application.port.{ServerProfileRepository, ServerProfileSummary, ServerProfileAssignmentView}
import cats.syntax.all._
import domain.provisioning._
import io.circe.Json
import io.circe.parser.parse
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID
import ServerProfileRepositoryRows.{ObservationRow,ProfileRow,RevisionRow}
import ServerProfileSqlRows.profileColumns

final class PostgresServerProfileRepository extends ServerProfileRepository[ConnectionIO] {
  override def insertProfile(p: ServerProfile): ConnectionIO[Boolean] = sql"""
    insert into server_profile(id,organization_id,code,name,description,archived,latest_revision,created_by_user_id,created_at,updated_at)
    values (${p.id},${p.organizationId},${p.code},${p.name},${p.description},${p.archived},${p.latestRevision},${p.createdBy},${p.createdAt},${p.updatedAt})
    on conflict (organization_id,code) do nothing
  """.update.run.map(_ == 1)

  override def profileForUpdate(org: UUID, id: UUID): ConnectionIO[Option[ServerProfile]] =
    (fr"select " ++ profileColumns ++ fr" from server_profile p where p.organization_id=$org and p.id=$id for update")
      .query[ProfileRow].option.map(_.map(_.toDomain))

  override def profile(org: UUID, id: UUID): ConnectionIO[Option[ServerProfile]] =
    (fr"select " ++ profileColumns ++ fr" from server_profile p where p.organization_id=$org and p.id=$id")
      .query[ProfileRow].option.map(_.map(_.toDomain))

  override def profiles(org: UUID, archived: Boolean, limit: Int): ConnectionIO[List[ServerProfileSummary]] =
    sql"""select p.id,p.organization_id,p.code,p.name,p.description,p.archived,p.latest_revision,p.created_by_user_id,p.created_at,p.updated_at,
      (select count(*) from server_profile_assignment a where a.organization_id=p.organization_id and a.profile_id=p.id)
      from server_profile p where p.organization_id=$org and p.archived=$archived order by lower(p.name),p.id limit ${limit.max(1).min(200)}"""
      .query[(ProfileRow,Long)].to[List].map(_.map { case (p,count) => ServerProfileSummary(p.toDomain,count.toInt) })

  override def insertRevision(r: ServerProfileRevision): ConnectionIO[Unit] = sql"""
    insert into server_profile_revision(id,organization_id,profile_id,revision_number,schema_version,content,content_hash,created_by_user_id,created_at)
    values (${r.id},${r.organizationId},${r.profileId},${r.number},${r.content.schemaVersion},cast(${r.content.canonical} as jsonb),${r.contentHash},${r.createdBy},${r.createdAt})
  """.update.run.void

  override def updateProfile(p: ServerProfile): ConnectionIO[Unit] = sql"""
    update server_profile set name=${p.name},description=${p.description},archived=${p.archived},latest_revision=${p.latestRevision},updated_at=${p.updatedAt}
    where organization_id=${p.organizationId} and id=${p.id}
  """.update.run.flatMap(n => if (n == 1) ().pure[ConnectionIO] else new IllegalStateException("Server profile not found").raiseError[ConnectionIO,Unit])

  override def revision(org: UUID, profileId: UUID, number: Int): ConnectionIO[Option[ServerProfileRevision]] =
    sql"select id,organization_id,profile_id,revision_number,content::text,content_hash,created_by_user_id,created_at from server_profile_revision where organization_id=$org and profile_id=$profileId and revision_number=$number"
      .query[RevisionRow].option.map(_.map(_.toDomain))

  override def revisions(org: UUID, profileId: UUID): ConnectionIO[List[ServerProfileRevision]] =
    sql"select id,organization_id,profile_id,revision_number,content::text,content_hash,created_by_user_id,created_at from server_profile_revision where organization_id=$org and profile_id=$profileId order by revision_number desc"
      .query[RevisionRow].to[List].map(_.map(_.toDomain))

  override def lockResource(org: UUID, resourceId: UUID): ConnectionIO[Unit] = PostgresProvisioningLocks.lockResource(org,resourceId)

  override def assignableNode(org: UUID, resourceId: UUID): ConnectionIO[Boolean] = sql"""
    select exists(select 1 from resource r join resource_type t on t.id=r.resource_type_id
      where r.organization_id=$org and r.id=$resourceId and r.is_active and t.code='NODE')
  """.query[Boolean].unique

  override def hasActiveRun(org: UUID, resourceId: UUID): ConnectionIO[Boolean] = sql"""
    select exists(select 1 from provisioning_run where organization_id=$org and resource_id=$resourceId and status in ('QUEUED','RUNNING'))
      or exists(select 1 from remnawave_node_onboarding where organization_id=$org and resource_id=$resourceId and state in ('QUEUED','RUNNING'))
      or exists(select 1 from remnawave_fleet_rollout_member m where m.organization_id=$org and m.resource_id=$resourceId
        and m.active and m.rollout_id is distinct from infradesk_fleet_rollout_caller())
  """.query[Boolean].unique

  override def executionGeneration(org: UUID, resourceId: UUID): ConnectionIO[Long] = sql"""
    select (select count(*) from provisioning_run where organization_id=$org and resource_id=$resourceId and request_id is not null)
      + (select count(*) from remnawave_node_onboarding where organization_id=$org and resource_id=$resourceId and request_id is not null)
  """.query[Long].unique

  override def assignment(org: UUID, resourceId: UUID): ConnectionIO[Option[ServerProfileAssignment]] =
    sql"select id,organization_id,resource_id,profile_id,revision_id,revision_number,version,assigned_by_user_id,assigned_at from server_profile_assignment where organization_id=$org and resource_id=$resourceId"
      .query[ServerProfileAssignment].option

  override def assignmentsForProfile(org: UUID, profileId: UUID): ConnectionIO[List[ServerProfileAssignmentView]] =
    sql"""select a.id,a.organization_id,a.resource_id,a.profile_id,a.revision_id,a.revision_number,a.version,
      a.assigned_by_user_id,a.assigned_at,r.name,r.is_active from server_profile_assignment a
      join resource r on r.organization_id=a.organization_id and r.id=a.resource_id
      where a.organization_id=$org and a.profile_id=$profileId order by lower(r.name),r.id"""
      .query[(ServerProfileAssignment,String,Boolean)].to[List].map(_.map { case (a,name,active) => ServerProfileAssignmentView(a,name,active) })

  override def storeAssignment(a: ServerProfileAssignment): ConnectionIO[Unit] = sql"""
    insert into server_profile_assignment(id,organization_id,resource_id,profile_id,revision_id,revision_number,version,assigned_by_user_id,assigned_at)
    values (${a.id},${a.organizationId},${a.resourceId},${a.profileId},${a.revisionId},${a.revisionNumber},${a.version},${a.assignedBy},${a.assignedAt})
    on conflict (organization_id,resource_id) do update set profile_id=excluded.profile_id,revision_id=excluded.revision_id,
      revision_number=excluded.revision_number,version=excluded.version,assigned_by_user_id=excluded.assigned_by_user_id,assigned_at=excluded.assigned_at
  """.update.run.void

  override def removeAssignment(org: UUID, resourceId: UUID): ConnectionIO[Option[ServerProfileAssignment]] =
    sql"delete from server_profile_assignment where organization_id=$org and resource_id=$resourceId returning id,organization_id,resource_id,profile_id,revision_id,revision_number,version,assigned_by_user_id,assigned_at"
      .query[ServerProfileAssignment].option

  override def saveObservation(o: ServerProfileObservation): ConnectionIO[Unit] = sql"""
    insert into server_profile_observation(id,organization_id,resource_id,source_connection_id,source_updated_at,assignment_id,assignment_version,
      revision_id,schema_version,content,content_hash,observed_at,verified_run_id)
    values (${o.id},${o.organizationId},${o.resourceId},${o.sourceConnectionId},${o.sourceUpdatedAt},${o.assignmentId},${o.assignmentVersion},
      ${o.revisionId},1,cast(${o.content.noSpaces} as jsonb),${o.contentHash},${o.observedAt},${o.verifiedRunId})
    on conflict (organization_id,resource_id) do update set id=excluded.id,source_connection_id=excluded.source_connection_id,
      source_updated_at=excluded.source_updated_at,assignment_id=excluded.assignment_id,assignment_version=excluded.assignment_version,
      revision_id=excluded.revision_id,content=excluded.content,content_hash=excluded.content_hash,observed_at=excluded.observed_at,verified_run_id=excluded.verified_run_id
  """.update.run.void

  override def observation(org: UUID, resourceId: UUID): ConnectionIO[Option[ServerProfileObservation]] =
    sql"select id,organization_id,resource_id,source_connection_id,source_updated_at,assignment_id,assignment_version,revision_id,content::text,content_hash,observed_at,verified_run_id from server_profile_observation where organization_id=$org and resource_id=$resourceId"
      .query[ObservationRow].option.map(_.map(_.toDomain))
}

private object ServerProfileRepositoryRows {
  final case class ProfileRow(id: UUID, organizationId: UUID, code: String, name: String, description: Option[String], archived: Boolean,
    latestRevision: Int, createdBy: UUID, createdAt: Instant, updatedAt: Instant) {
    def toDomain = ServerProfile(id,organizationId,name,code,description,archived,latestRevision,createdBy,createdAt,updatedAt)
  }
  final case class RevisionRow(id: UUID,organizationId: UUID,profileId: UUID,number: Int,contentJson: String,contentHash: String,createdBy: UUID,createdAt: Instant) {
    def toDomain = {
      val content=ServerProfileContent.parsePersisted(contentJson,contentHash).fold(code => throw new IllegalStateException(code),identity)
      ServerProfileRevision(id,organizationId,profileId,number,content,content.hash,createdBy,createdAt)
    }
  }
  final case class ObservationRow(id: UUID,organizationId: UUID,resourceId: UUID,sourceId: UUID,sourceAt: Instant,assignmentId: Option[UUID],
    assignmentVersion: Option[Long],revisionId: Option[UUID],contentJson: String,contentHash: String,observedAt: Instant,verifiedRunId: Option[UUID]) {
    def toDomain = {
      val content = parse(contentJson).fold(e => throw new IllegalStateException("Invalid sanitized observation"),identity)
      require(ServerProfileObservationCodec.validate(content).isRight && ServerProfileDiff.hashObservation(content) == contentHash,
        "Invalid persisted sanitized observation")
      ServerProfileObservation(id,organizationId,resourceId,sourceId,sourceAt,assignmentId,assignmentVersion,revisionId,
        content,contentHash,observedAt,verifiedRunId)
    }
  }
}
private object ServerProfileSqlRows {
  val profileColumns: Fragment = fr"p.id,p.organization_id,p.code,p.name,p.description,p.archived,p.latest_revision,p.created_by_user_id,p.created_at,p.updated_at"
}
