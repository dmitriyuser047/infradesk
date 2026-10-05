package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.integration._
import domain.integration.NodeReleaseJson._
import io.circe.{Decoder, Encoder}
import io.circe.parser.parse
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class PostgresRemnawaveFleetUpgradeRepository extends RemnawaveFleetUpgradeRepository[ConnectionIO] {
  private def json[A: Encoder](value: A): String = implicitly[Encoder[A]].apply(value).noSpaces
  private def decode[A: Decoder](value: String): A = parse(value).flatMap(_.as[A])
    .fold(_ => throw new IllegalStateException("Invalid persisted node release record"), identity)
  private case class ReleaseRow(id: UUID, org: UUID, integration: UUID, fleet: UUID, number: Int,
    release: String, panel: String, hash: String, by: UUID, at: Instant) {
    def domain: FleetNodeReleaseRevision = {
      val value = FleetNodeReleaseRevision(id, org, integration, fleet, number, decode[NodeRelease](release),
        decode[NodeApiCompatibility](panel), by, at)
      require(value.hash == hash, "Invalid persisted release revision hash")
      value
    }
  }
  private val releaseColumns = fr"r.id,r.organization_id,r.integration_id,r.fleet_id,r.revision_number,r.release_snapshot::text,r.panel_evidence::text,r.content_hash::text,r.created_by,r.created_at"
  def releaseTarget(org: UUID, fleet: UUID): ConnectionIO[Option[FleetNodeReleaseRevision]] =
    (fr"select" ++ releaseColumns ++ fr"""from remnawave_fleet_release_revision r join remnawave_fleet_release_policy p
      on p.desired_revision_id=r.id and p.organization_id=r.organization_id and p.fleet_id=r.fleet_id
      where p.organization_id=$org and p.fleet_id=$fleet""").query[ReleaseRow].option.map(_.map(_.domain))
  def releaseRevision(org: UUID, fleet: UUID, id: UUID): ConnectionIO[Option[FleetNodeReleaseRevision]] =
    (fr"select" ++ releaseColumns ++ fr"from remnawave_fleet_release_revision r where r.organization_id=$org and r.fleet_id=$fleet and r.id=$id")
      .query[ReleaseRow].option.map(_.map(_.domain))
  def nextRevisionNumber(org: UUID, fleet: UUID): ConnectionIO[Int] = sql"""
    select coalesce(max(revision_number),0)+1 from remnawave_fleet_release_revision where organization_id=$org and fleet_id=$fleet"""
    .query[Int].unique
  def promote(r: FleetNodeReleaseRevision): ConnectionIO[Unit] = for {
    _ <- sql"""insert into remnawave_fleet_release_revision(id,organization_id,integration_id,fleet_id,revision_number,
      release_id,node_version,image_repository,image_digest,catalog_version,release_snapshot,panel_evidence,content_hash,created_by,created_at)
      values(${r.id},${r.organizationId},${r.integrationId},${r.fleetId},${r.number},${r.release.releaseId},${r.release.nodeVersion},
        ${r.release.imageRepository},${r.release.manifestDigest},${r.release.catalogVersion},cast(${json(r.release)} as jsonb),
        cast(${json(r.panel)} as jsonb),${r.hash},${r.createdBy},${r.createdAt})""".update.run
    _ <- sql"""insert into remnawave_fleet_release_policy(fleet_id,organization_id,desired_revision_id,updated_at)
      values(${r.fleetId},${r.organizationId},${r.id},${r.createdAt}) on conflict(fleet_id) do update
      set desired_revision_id=excluded.desired_revision_id,version=remnawave_fleet_release_policy.version+1,
        updated_at=excluded.updated_at where remnawave_fleet_release_policy.organization_id=excluded.organization_id""".update.run
  } yield ()

  private case class ObservationRow(org: UUID, fleet: UUID, member: UUID, version: Long, node: UUID,
    resource: UUID, onboarding: UUID, source: UUID, sourceAt: Instant, observation: String, at: Instant) {
    def domain = FleetNodeImageObservation(org, fleet, member, version, node, resource, onboarding, source, sourceAt,
      decode[NodeImageObservation](observation), at)
  }
  def observations(org: UUID, fleet: UUID): ConnectionIO[List[FleetNodeImageObservation]] = sql"""
    select organization_id,fleet_id,membership_id,membership_version,inventory_node_id,resource_id,onboarding_id,
      source_connection_id,source_updated_at,observation::text,observed_at from remnawave_node_image_observation
    where organization_id=$org and fleet_id=$fleet""".query[ObservationRow].to[List].map(_.map(_.domain))
  def observationsBatch(org: UUID, fleetId: UUID, ids: List[UUID]): ConnectionIO[List[FleetNodeImageObservation]] = sql"""
    select organization_id,fleet_id,membership_id,membership_version,inventory_node_id,resource_id,onboarding_id,
      source_connection_id,source_updated_at,observation::text,observed_at from remnawave_node_image_observation
    where organization_id=$org and fleet_id=$fleetId and membership_id=any(${ids.toArray[UUID]})"""
    .query[ObservationRow].to[List].map(_.map(_.domain))
  def saveObservation(v: FleetNodeImageObservation): ConnectionIO[Unit] = sql"""
    insert into remnawave_node_image_observation(membership_id,organization_id,integration_id,fleet_id,membership_version,inventory_node_id,
      resource_id,onboarding_id,source_connection_id,source_updated_at,observation,observed_at)
    select ${v.membershipId},${v.organizationId},f.integration_id,${v.fleetId},${v.membershipVersion},${v.inventoryNodeId},${v.resourceId},
      ${v.onboardingId},${v.sourceConnectionId},${v.sourceUpdatedAt},cast(${json(v.observation)} as jsonb),${v.observedAt}
      from remnawave_fleet f where f.id=${v.fleetId} and f.organization_id=${v.organizationId}
    on conflict(membership_id) do update set membership_version=excluded.membership_version,
      onboarding_id=excluded.onboarding_id,source_connection_id=excluded.source_connection_id,
      source_updated_at=excluded.source_updated_at,observation=excluded.observation,observed_at=excluded.observed_at
    where remnawave_node_image_observation.organization_id=excluded.organization_id and
      remnawave_node_image_observation.observed_at<=excluded.observed_at""".update.run.void

  private case class Ids(id: UUID, org: UUID, integration: UUID, fleet: UUID, release: UUID, request: Option[UUID])
  private case class Progress(state: String, phase: String, snapshot: String, hash: String, wave: Int, waves: Int)
  private case class Life(by: UUID, created: Instant, expires: Instant, started: Option[Instant], finished: Option[Instant], code: Option[String])
  private case class Control(reason: Option[String], paused: Option[Instant], pauseRequest: Option[Instant],
    rollbackRequest: Option[Instant], scope: String, incomplete: Boolean, next: Instant, phaseAt: Instant,
    token: Option[UUID], version: Long, updated: Instant)
  private case class RunRow(ids: Ids, progress: Progress, life: Life, control: Control) {
    def domain: RemnawaveFleetUpgradeRun = {
      val snapshot = decode[NodeUpgradeSnapshot](progress.snapshot)
      require(snapshot.hash == progress.hash, "Invalid persisted upgrade snapshot hash")
      RemnawaveFleetUpgradeRun(ids.id, ids.org, ids.integration, ids.fleet, ids.release, ids.request,
        FleetRolloutState.fromCode(progress.state).get, NodeUpgradePhase.fromCode(progress.phase).get, snapshot,
        progress.hash, progress.wave, progress.waves, life.by, life.created, life.expires, life.started, life.finished,
        life.code, control.reason, control.paused, control.pauseRequest, control.rollbackRequest,
        FleetRollbackScope.fromCode(control.scope).get, control.incomplete, control.next, control.phaseAt,
        control.token, control.version, control.updated)
    }
  }
  private val columns = fr"""id,organization_id,integration_id,fleet_id,release_revision_id,request_id,state,phase,
    input_snapshot::text,snapshot_hash::text,current_wave,wave_count,created_by,created_at,expires_at,started_at,
    finished_at,failure_code,pause_reason,paused_at,pause_requested_at,rollback_requested_at,rollback_scope,
    rollback_incomplete,next_run_at,phase_started_at,claim_token,version,updated_at"""
  private def select(condition: Fragment) = (fr"select" ++ columns ++ fr"from remnawave_fleet_upgrade_run where" ++ condition)
    .query[RunRow].map(_.domain)
  def run(org: UUID, fleet: UUID, id: UUID, lock: Boolean): ConnectionIO[Option[RemnawaveFleetUpgradeRun]] =
    select(fr"organization_id=$org and fleet_id=$fleet and id=$id" ++ (if (lock) fr"for update" else Fragment.empty)).option
  def byId(id: UUID): ConnectionIO[Option[RemnawaveFleetUpgradeRun]] = select(fr"id=$id").option
  def byRequest(org: UUID, request: UUID): ConnectionIO[Option[RemnawaveFleetUpgradeRun]] =
    select(fr"organization_id=$org and request_id=$request").option
  def active(org: UUID, fleet: UUID): ConnectionIO[Option[RemnawaveFleetUpgradeRun]] =
    select(fr"organization_id=$org and fleet_id=$fleet and state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')").option
  def history(org: UUID, fleet: UUID): ConnectionIO[List[RemnawaveFleetUpgradeRun]] =
    select(fr"organization_id=$org and fleet_id=$fleet order by created_at desc,id limit 50").to[List]

  private case class MemberRow(id: UUID, org: UUID, upgrade: UUID, membership: UUID, resource: UUID, wave: Int,
    position: Int, state: String, code: Option[String], local: Option[Instant], panel: Option[Instant],
    observation: Option[String], started: Option[Instant], finished: Option[Instant]) {
    def domain = RemnawaveFleetUpgradeMember(id, org, upgrade, membership, resource, wave, position,
      FleetRolloutMemberState.fromCode(state).get, code, local, panel, observation.map(decode[NodeImageObservation]), started, finished)
  }
  def members(id: UUID): ConnectionIO[List[RemnawaveFleetUpgradeMember]] = sql"""
    select id,organization_id,upgrade_id,membership_id,resource_id,wave,position,state,failure_code,local_verified_at,
      panel_verified_at,last_observation::text,started_at,finished_at from remnawave_fleet_upgrade_member
    where upgrade_id=$id order by position""".query[MemberRow].to[List].map(_.map(_.domain))
  private case class ActionRow(id: UUID, org: UUID, upgrade: UUID, member: UUID, kind: String, rollback: Boolean,
    state: String, reference: String, code: Option[String], started: Instant, finished: Option[Instant]) {
    def domain = RemnawaveFleetUpgradeAction(id, org, upgrade, member, kind, rollback,
      FleetActionState.fromCode(state).get, reference, code, started, finished)
  }
  def actions(id: UUID): ConnectionIO[List[RemnawaveFleetUpgradeAction]] = sql"""
    select id,organization_id,upgrade_id,member_id,kind,rollback,state,target_reference,failure_code,started_at,finished_at
    from remnawave_fleet_upgrade_action where upgrade_id=$id order by started_at,id""".query[ActionRow].to[List].map(_.map(_.domain))

  def insertPlan(r: RemnawaveFleetUpgradeRun, values: List[RemnawaveFleetUpgradeMember]): ConnectionIO[Unit] = for {
    _ <- sql"""insert into remnawave_fleet_upgrade_run(id,organization_id,integration_id,fleet_id,release_revision_id,
      state,phase,input_snapshot,snapshot_hash,current_wave,wave_count,created_by,created_at,expires_at,
      rollback_scope,next_run_at,phase_started_at,updated_at)
      values(${r.id},${r.organizationId},${r.integrationId},${r.fleetId},${r.releaseRevisionId},'PLANNED',${r.phase.code},
        cast(${json(r.snapshot)} as jsonb),${r.snapshotHash},0,${r.waveCount},${r.createdBy},${r.createdAt},${r.expiresAt},
        ${r.rollbackScope.code},${r.nextRunAt},${r.phaseStartedAt},${r.updatedAt})""".update.run
    _ <- values.traverse_(m => sql"""insert into remnawave_fleet_upgrade_member(id,organization_id,upgrade_id,fleet_id,
      membership_id,resource_id,wave,position,state) values(${m.id},${m.organizationId},${m.upgradeId},${r.fleetId},
        ${m.membershipId},${m.resourceId},${m.wave},${m.position},${m.state.code})""".update.run)
  } yield ()
  def start(org: UUID, id: UUID, request: UUID, now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_upgrade_run set state='QUEUED',request_id=$request,next_run_at=$now,updated_at=$now,version=version+1
    where organization_id=$org and id=$id and state='PLANNED' and expires_at>$now""".update.run.map(_ == 1)
  def requestPause(org: UUID, id: UUID, now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_upgrade_run set pause_requested_at=$now,updated_at=$now,version=version+1
    where organization_id=$org and id=$id and state in ('QUEUED','RUNNING') and pause_requested_at is null
      and rollback_requested_at is null""".update.run.map(_ == 1)
  def resume(org: UUID, id: UUID, now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_upgrade_run set state='QUEUED',paused_at=null,pause_reason=null,failure_code=null,
      pause_requested_at=null,next_run_at=$now,updated_at=$now,version=version+1
    where organization_id=$org and id=$id and state='PAUSED' and rollback_requested_at is null""".update.run.map(_ == 1)
  def requestRollback(org: UUID, id: UUID, scope: FleetRollbackScope, now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_upgrade_run set rollback_requested_at=$now,rollback_scope=${scope.code},next_run_at=$now,
      updated_at=$now,version=version+1 where organization_id=$org and id=$id and state in ('QUEUED','RUNNING','PAUSED')
      and rollback_requested_at is null""".update.run.map(_ == 1)
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): ConnectionIO[List[RemnawaveFleetUpgradeRun]] = for {
    ids <- sql"""with due as(select id from remnawave_fleet_upgrade_run
      where (state in ('QUEUED','RUNNING','ROLLING_BACK') or (state='PAUSED' and rollback_requested_at is not null))
        and next_run_at<=$now and (claim_deadline is null or claim_deadline<=$now)
      order by next_run_at,id limit $limit for update skip locked)
      update remnawave_fleet_upgrade_run r set claim_owner=$owner,claim_token=$token,claim_deadline=$until
      from due where r.id=due.id returning r.id""".query[UUID].to[List]
    values <- ids.traverse(id => byId(id).map(_.get))
  } yield values
  def renew(id: UUID, token: UUID, now: Instant, until: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_upgrade_run set claim_deadline=$until where id=$id and claim_token=$token
      and claim_deadline>$now and claim_deadline>clock_timestamp() and state in ('QUEUED','RUNNING','ROLLING_BACK','PAUSED')""".update.run.map(_ == 1)
  private def fence(id: UUID, token: UUID, now: Instant) = fr"""exists(select 1 from remnawave_fleet_upgrade_run p
    where p.id=$id and p.claim_token=$token and p.claim_deadline>$now and p.claim_deadline>clock_timestamp()
      and p.state in ('QUEUED','RUNNING','ROLLING_BACK','PAUSED'))"""
  def owns(id: UUID, token: UUID, now: Instant): ConnectionIO[Boolean] = (fr"select" ++ fence(id, token, now)).query[Boolean].unique
  def save(r: RemnawaveFleetUpgradeRun, token: UUID, now: Instant, release: Boolean): ConnectionIO[Boolean] =
    (fr"""update remnawave_fleet_upgrade_run set state=${r.state.code},phase=${r.phase.code},current_wave=${r.currentWave},
      started_at=${r.startedAt},finished_at=${r.finishedAt},failure_code=${r.failureCode},pause_reason=${r.pauseReason},
      paused_at=${r.pausedAt},
      pause_requested_at=case when ${r.state.code} in ('PAUSED','ROLLING_BACK') then null else pause_requested_at end,
      rollback_requested_at=case when ${r.state.code}='ROLLING_BACK' then null else rollback_requested_at end,
      rollback_scope=case when rollback_requested_at is null then ${r.rollbackScope.code} else rollback_scope end,
      rollback_incomplete=${r.rollbackIncomplete},next_run_at=${r.nextRunAt},
      phase_started_at=${r.phaseStartedAt},claim_owner=case when $release then null else claim_owner end,
      claim_token=case when $release then null else claim_token end,claim_deadline=case when $release then null else claim_deadline end,
      version=version+1,updated_at=$now where id=${r.id} and""" ++ fence(r.id, token, now)).update.run.map(_ == 1)
  def saveMember(m: RemnawaveFleetUpgradeMember, token: UUID, now: Instant): ConnectionIO[Boolean] =
    (fr"""update remnawave_fleet_upgrade_member set state=${m.state.code},failure_code=${m.failureCode},
      local_verified_at=${m.localVerifiedAt},panel_verified_at=${m.panelVerifiedAt},
      last_observation=cast(${m.lastObservation.map(json[NodeImageObservation])} as jsonb),started_at=${m.startedAt},finished_at=${m.finishedAt}
      where id=${m.id} and upgrade_id=${m.upgradeId} and organization_id=${m.organizationId} and""" ++ fence(m.upgradeId, token, now))
      .update.run.map(_ == 1)
  def saveAction(a: RemnawaveFleetUpgradeAction, token: UUID, now: Instant): ConnectionIO[Boolean] =
    (fr"""insert into remnawave_fleet_upgrade_action(id,organization_id,upgrade_id,member_id,kind,rollback,state,
      target_reference,failure_code,started_at,finished_at) select ${a.id},${a.organizationId},${a.upgradeId},${a.memberId},
        ${a.kind},${a.rollback},${a.state.code},${a.targetReference},${a.failureCode},${a.startedAt},${a.finishedAt} where""" ++
      fence(a.upgradeId, token, now) ++ fr"""on conflict(id) do update set state=excluded.state,failure_code=excluded.failure_code,
        finished_at=excluded.finished_at where remnawave_fleet_upgrade_action.upgrade_id=excluded.upgrade_id""").update.run.map(_ == 1)
}
