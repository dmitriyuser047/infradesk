package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.integration._
import io.circe.parser.parse
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class PostgresRemnawaveFleetRepository extends RemnawaveFleetRepository[ConnectionIO] {
  private case class FleetRow(id: UUID, org: UUID, integration: UUID, code: String, name: String,
    description: Option[String], desired: Option[UUID], version: Long, archived: Boolean, createdBy: UUID,
    created: Instant, updated: Instant) {
    def domain = RemnawaveFleet(id, org, integration, code, name, description, desired, version, archived,
      createdBy, created, updated)
  }
  private val fleetColumns = fr"id,organization_id,integration_id,code,name,description,desired_revision_id," ++
    fr"version,archived,created_by,created_at,updated_at"
  private def fleetsWhere(where: Fragment) =
    (fr"select" ++ fleetColumns ++ fr"from remnawave_fleet where" ++ where).query[FleetRow].map(_.domain)

  private case class RevisionRow(id: UUID, org: UUID, fleet: UUID, number: Int, schema: Int, hash: String,
    content: String, createdBy: UUID, created: Instant) {
    def domain: RemnawaveFleetRevision = RemnawaveFleetRevision(id, org, fleet, number, schema, hash,
      parse(content).toOption.flatMap(FleetDesiredContentCodec.decode(_).toOption)
        .getOrElse(throw new IllegalStateException("Invalid fleet revision content")), createdBy, created)
  }
  private val revisionColumns =
    fr"id,organization_id,fleet_id,number,schema_version,content_hash,content::text,created_by,created_at"
  private def revisionsWhere(where: Fragment) =
    (fr"select" ++ revisionColumns ++ fr"from remnawave_fleet_revision where" ++ where)
      .query[RevisionRow].map(_.domain)

  private case class MemberRow(id: UUID, org: UUID, fleet: UUID, integration: UUID, node: UUID, resource: UUID,
    version: Long, createdBy: UUID, created: Instant, removed: Option[Instant]) {
    def domain = RemnawaveFleetMembership(id, org, fleet, integration, node, resource, version, createdBy,
      created, removed)
  }
  private val memberColumns = fr"id,organization_id,fleet_id,integration_id,inventory_node_id,resource_id," ++
    fr"version,created_by,created_at,removed_at"
  private def membersWhere(where: Fragment) =
    (fr"select" ++ memberColumns ++ fr"from remnawave_fleet_membership where" ++ where)
      .query[MemberRow].map(_.domain)

  /** Serializes promotion and membership changes of one fleet, as the provisioning locks do. */
  def lockFleet(org: UUID, fleetId: UUID): ConnectionIO[Unit] =
    sql"""select 1 from pg_advisory_xact_lock(hashtextextended(
      cast($org as text) || ':fleet:' || cast($fleetId as text), 2))""".query[Int].unique.void

  def rolloutActive(org: UUID, fleetId: UUID): ConnectionIO[Boolean] = sql"""
    select exists(select 1 from remnawave_fleet_rollout where organization_id=$org and fleet_id=$fleetId
      and state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')) or exists(
      select 1 from remnawave_fleet_upgrade_run where organization_id=$org and fleet_id=$fleetId
        and state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))""".query[Boolean].unique

  def insertFleet(fleet: RemnawaveFleet): ConnectionIO[Boolean] = sql"""
    insert into remnawave_fleet(id,organization_id,integration_id,code,name,description,version,archived,
      created_by,created_at,updated_at)
    values(${fleet.id},${fleet.organizationId},${fleet.integrationId},${fleet.code},${fleet.name},
      ${fleet.description},1,false,${fleet.createdBy},${fleet.createdAt},${fleet.updatedAt})
    on conflict do nothing""".update.run.map(_ == 1)

  def fleet(org: UUID, integrationId: UUID, id: UUID): ConnectionIO[Option[RemnawaveFleet]] =
    fleetsWhere(fr"organization_id=$org and integration_id=$integrationId and id=$id").option

  def fleetForUpdate(org: UUID, integrationId: UUID, id: UUID): ConnectionIO[Option[RemnawaveFleet]] =
    fleetsWhere(fr"organization_id=$org and integration_id=$integrationId and id=$id for update").option

  def fleets(org: UUID, integrationId: UUID, archived: Boolean, limit: Int): ConnectionIO[List[RemnawaveFleet]] =
    fleetsWhere(fr"organization_id=$org and integration_id=$integrationId and archived=$archived" ++
      fr"order by name,id limit $limit").to[List]

  def updateFleet(fleet: RemnawaveFleet, expectedVersion: Long, now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet set name=${fleet.name},description=${fleet.description},archived=${fleet.archived},
      version=version+1,updated_at=$now
    where id=${fleet.id} and organization_id=${fleet.organizationId} and version=$expectedVersion"""
    .update.run.map(_ == 1)

  def insertRevision(revision: RemnawaveFleetRevision): ConnectionIO[Boolean] = {
    val content = revision.content
    sql"""insert into remnawave_fleet_revision(id,organization_id,fleet_id,number,schema_version,content,
        content_hash,server_profile_id,server_profile_revision_id,configuration_profile_id,
        configuration_revision_id,inventory_config_profile_id,created_by,created_at)
      values(${revision.id},${revision.organizationId},${revision.fleetId},${revision.number},
        ${revision.schemaVersion},cast(${content.json.noSpaces} as jsonb),${revision.contentHash},
        ${content.serverProfileId},${content.serverProfileRevisionId},${content.configurationProfileId},
        ${content.configRevisionId},${content.inventoryConfigProfileId},${revision.createdBy},${revision.createdAt})
      on conflict do nothing""".update.run.map(_ == 1)
  }

  def revision(org: UUID, fleetId: UUID, id: UUID): ConnectionIO[Option[RemnawaveFleetRevision]] =
    revisionsWhere(fr"organization_id=$org and fleet_id=$fleetId and id=$id").option

  def revisions(org: UUID, fleetId: UUID, limit: Int): ConnectionIO[List[RemnawaveFleetRevision]] =
    revisionsWhere(fr"organization_id=$org and fleet_id=$fleetId order by number desc limit $limit").to[List]

  def nextRevisionNumber(org: UUID, fleetId: UUID): ConnectionIO[Int] =
    sql"select coalesce(max(number),0)+1 from remnawave_fleet_revision where organization_id=$org and fleet_id=$fleetId"
      .query[Int].unique

  /** Moves the desired pointer and nothing else. The revision has to belong to this fleet, which the
    * foreign key enforces as well.
    */
  def promote(org: UUID, fleetId: UUID, revisionId: UUID, expectedVersion: Long,
    now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet set desired_revision_id=$revisionId,version=version+1,updated_at=$now
    where id=$fleetId and organization_id=$org and version=$expectedVersion
      and exists(select 1 from remnawave_fleet_revision r where r.id=$revisionId and r.fleet_id=$fleetId
        and r.organization_id=$org)""".update.run.map(_ == 1)

  def insertMembership(membership: RemnawaveFleetMembership, nextCheckAt: Instant): ConnectionIO[Boolean] = sql"""
    insert into remnawave_fleet_membership(id,organization_id,fleet_id,integration_id,inventory_node_id,
      resource_id,version,created_by,created_at,next_check_at)
    values(${membership.id},${membership.organizationId},${membership.fleetId},${membership.integrationId},
      ${membership.inventoryNodeId},${membership.resourceId},1,${membership.createdBy},${membership.createdAt},
      $nextCheckAt)
    on conflict do nothing""".update.run.map(_ == 1)

  def membership(org: UUID, fleetId: UUID, id: UUID): ConnectionIO[Option[RemnawaveFleetMembership]] =
    membersWhere(fr"organization_id=$org and fleet_id=$fleetId and id=$id").option

  def members(org: UUID, fleetId: UUID): ConnectionIO[List[RemnawaveFleetMembership]] =
    membersWhere(fr"organization_id=$org and fleet_id=$fleetId order by created_at,id").to[List]

  def activeMembershipOf(org: UUID, integrationId: UUID,
    inventoryNodeId: UUID): ConnectionIO[Option[RemnawaveFleetMembership]] =
    membersWhere(fr"organization_id=$org and integration_id=$integrationId and" ++
      fr"inventory_node_id=$inventoryNodeId and removed_at is null").option

  /** Ends the fleet's ownership. The assessment row goes with it; no remote state is touched. */
  def removeMembership(org: UUID, fleetId: UUID, id: UUID, now: Instant): ConnectionIO[Boolean] = for {
    _ <- sql"delete from remnawave_fleet_node_assessment where organization_id=$org and membership_id=$id".update.run
    changed <- sql"""update remnawave_fleet_membership set removed_at=$now,version=version+1,
      claimed_by=null,claim_token=null,claim_deadline=null
      where id=$id and fleet_id=$fleetId and organization_id=$org and removed_at is null""".update.run
  } yield changed == 1

  def markDue(org: UUID, fleetId: Option[UUID], integrationId: UUID, at: Instant): ConnectionIO[Int] =
    (fr"""update remnawave_fleet_membership set next_check_at=$at where organization_id=$org
      and integration_id=$integrationId and removed_at is null""" ++
      fleetId.fold(Fragment.empty)(id => fr"and fleet_id=$id")).update.run

  /** One member is observed by one worker at a time: the claim is a lease a stale worker loses. */
  def claimDue(owner: UUID, token: UUID, now: Instant, until: Instant,
    limit: Int): ConnectionIO[List[RemnawaveFleetMembership]] =
    (fr"""with picked as(
      select m.id from remnawave_fleet_membership m
      join remnawave_fleet f on f.id=m.fleet_id and f.organization_id=m.organization_id
      where m.removed_at is null and not f.archived and f.desired_revision_id is not null
        and m.next_check_at<=greatest($now,clock_timestamp())
        and (m.claim_deadline is null or m.claim_deadline<=greatest($now,clock_timestamp()))
      order by m.next_check_at,m.id for update of m skip locked limit $limit), changed as(
      update remnawave_fleet_membership m set claimed_by=$owner,claim_token=$token,claim_deadline=$until
      from picked where m.id=picked.id returning m.*)
      select""" ++ memberColumns ++ fr"from changed").query[MemberRow].to[List].map(_.map(_.domain))

  def renewClaim(membership: RemnawaveFleetMembership, token: UUID, now: Instant,
    until: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_membership set claim_deadline=$until
    where id=${membership.id} and organization_id=${membership.organizationId} and claim_token=$token
      and removed_at is null and claim_deadline>greatest($now,clock_timestamp())""".update.run.map(_ == 1)

  /** The verdict lands only while everything it was computed from still holds: the same claim, the
    * same membership version, the same desired revision and the same binding. Otherwise the result
    * is dropped and the member is simply due again.
    */
  def saveAssessment(assessment: RemnawaveFleetNodeAssessment, token: UUID, now: Instant,
    nextCheckAt: Instant): ConnectionIO[Boolean] = for {
    valid <- sql"""select exists(
      select 1 from remnawave_fleet_membership m
      join remnawave_fleet f on f.id=m.fleet_id and f.organization_id=m.organization_id
      where m.id=${assessment.membershipId} and m.organization_id=${assessment.organizationId}
        and m.claim_token=$token and m.claim_deadline is not null
        and m.claim_deadline>greatest($now,clock_timestamp())
        and m.removed_at is null and m.version=${assessment.membershipVersion}
        and m.inventory_node_id=${assessment.inventoryNodeId} and m.resource_id=${assessment.resourceId}
        and f.desired_revision_id=${assessment.fleetRevisionId}
        and exists(select 1 from integration_resource_binding b where b.organization_id=m.organization_id
          and b.integration_id=m.integration_id and b.inventory_object_id=m.inventory_node_id
          and b.resource_id=m.resource_id))""".query[Boolean].unique
    saved <- if (!valid) false.pure[ConnectionIO] else for {
      _ <- sql"""insert into remnawave_fleet_node_assessment(id,organization_id,fleet_id,fleet_revision_id,
          membership_id,membership_version,inventory_node_id,resource_id,compliance,health,drift_reasons,
          health_reasons,rollout_blockers,inventory_observed_at,server_observed_at,local_observed_at,
          computed_at,assessment_version)
        values(${assessment.id},${assessment.organizationId},${assessment.fleetId},${assessment.fleetRevisionId},
          ${assessment.membershipId},${assessment.membershipVersion},${assessment.inventoryNodeId},
          ${assessment.resourceId},${assessment.compliance.code},${assessment.health.code},
          ${assessment.driftReasons.map(_.code)},${assessment.healthReasons.map(_.code)},
          ${assessment.rolloutBlockers.map(_.code)},${assessment.inventoryObservedAt},
          ${assessment.serverObservedAt},${assessment.localObservedAt},${assessment.computedAt},
          ${assessment.assessmentVersion})
        on conflict(membership_id) do update set fleet_revision_id=excluded.fleet_revision_id,
          membership_version=excluded.membership_version,inventory_node_id=excluded.inventory_node_id,
          resource_id=excluded.resource_id,compliance=excluded.compliance,health=excluded.health,
          drift_reasons=excluded.drift_reasons,health_reasons=excluded.health_reasons,
          rollout_blockers=excluded.rollout_blockers,inventory_observed_at=excluded.inventory_observed_at,
          server_observed_at=excluded.server_observed_at,local_observed_at=excluded.local_observed_at,
          computed_at=excluded.computed_at,assessment_version=excluded.assessment_version""".update.run
      released <- sql"""update remnawave_fleet_membership set next_check_at=$nextCheckAt,claimed_by=null,
        claim_token=null,claim_deadline=null
        where id=${assessment.membershipId} and organization_id=${assessment.organizationId}
          and claim_token=$token and claim_deadline is not null
          and claim_deadline>greatest($now,clock_timestamp())""".update.run
    } yield released == 1
  } yield saved

  /** Fenced like the write it replaces: a worker whose lease has run out changes nothing here
    * either, so a stale attempt cannot push the next check out from under the worker that owns it.
    */
  def reschedule(membership: RemnawaveFleetMembership, token: UUID, now: Instant,
    nextCheckAt: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_membership set next_check_at=$nextCheckAt,claimed_by=null,claim_token=null,
      claim_deadline=null
    where id=${membership.id} and organization_id=${membership.organizationId} and claim_token=$token
      and claim_deadline is not null and claim_deadline>greatest($now,clock_timestamp())"""
    .update.run.map(_ == 1)

  /** Ends every active membership of a fleet and drops their current verdicts. The rows stay: the
    * fleet's history keeps who was a member, when they joined and when the fleet released them.
    * Nothing remote is touched.
    */
  def releaseMemberships(org: UUID, fleetId: UUID, now: Instant): ConnectionIO[Int] = for {
    _ <- sql"""delete from remnawave_fleet_node_assessment a
      where a.organization_id=$org and a.fleet_id=$fleetId
        and exists(select 1 from remnawave_fleet_membership m where m.id=a.membership_id
          and m.removed_at is null)""".update.run
    released <- sql"""update remnawave_fleet_membership
      set removed_at=$now,version=version+1,claimed_by=null,claim_token=null,claim_deadline=null
      where organization_id=$org and fleet_id=$fleetId and removed_at is null""".update.run
  } yield released

  def assessments(org: UUID, fleetId: UUID): ConnectionIO[List[RemnawaveFleetNodeAssessment]] =
    (fr"select" ++ PostgresRemnawaveFleetRepository.assessmentColumns ++
      fr"from remnawave_fleet_node_assessment where organization_id=$org and fleet_id=$fleetId")
      .query[PostgresRemnawaveFleetRepository.AssessmentRow].map(_.domain).to[List]
  def assessmentsBatch(org: UUID, fleetId: UUID, ids: List[UUID]): ConnectionIO[List[RemnawaveFleetNodeAssessment]] =
    (fr"select" ++ PostgresRemnawaveFleetRepository.assessmentColumns ++
      fr"from remnawave_fleet_node_assessment where organization_id=$org and fleet_id=$fleetId and membership_id=any(${ids.toArray[UUID]})")
      .query[PostgresRemnawaveFleetRepository.AssessmentRow].map(_.domain).to[List]
}

object PostgresRemnawaveFleetRepository {
  private[postgres] val assessmentColumns =
    fr"id,organization_id,fleet_id,fleet_revision_id,membership_id,membership_version,inventory_node_id," ++
      fr"resource_id,compliance,health,drift_reasons,health_reasons,rollout_blockers,inventory_observed_at," ++
      fr"server_observed_at,local_observed_at,computed_at,assessment_version"

  private[postgres] case class AssessmentRow(id: UUID, org: UUID, fleet: UUID, revision: UUID, membership: UUID,
    membershipVersion: Long, node: UUID, resource: UUID, compliance: String, health: String,
    drift: List[String], healthReasons: List[String], blockers: List[String], inventoryAt: Option[Instant],
    serverAt: Option[Instant], localAt: Option[Instant], computed: Instant, version: Int) {
    def domain: RemnawaveFleetNodeAssessment = RemnawaveFleetNodeAssessment(id, org, fleet, revision, membership,
      membershipVersion, node, resource,
      FleetCompliance.fromCode(compliance).getOrElse(FleetCompliance.Unknown),
      FleetHealth.fromCode(health).getOrElse(FleetHealth.Unknown),
      drift.flatMap(FleetDriftReason.fromCode), healthReasons.flatMap(FleetHealthReason.fromCode),
      blockers.flatMap(FleetRolloutBlocker.fromCode), inventoryAt, serverAt, localAt, computed, version)
  }
}
