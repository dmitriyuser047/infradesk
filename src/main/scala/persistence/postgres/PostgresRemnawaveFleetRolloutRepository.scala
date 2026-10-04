package ru.bitec.app.ops
package persistence.postgres

import application.port._
import application.integration.IntegrationError
import cats.syntax.all._
import domain.integration._
import io.circe.parser.parse
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID
import java.sql.SQLException

final class PostgresRemnawaveFleetRolloutRepository extends RemnawaveFleetRolloutRepository[ConnectionIO] {
  private case class Ids(id: UUID, org: UUID, integration: UUID, fleet: UUID, revision: UUID, request: Option[UUID])
  private case class Progress(state: String, phase: String, snapshot: String, hash: String, wave: Int,
    waveCount: Int, pauseAfterCanary: Boolean, automatic: Boolean, scope: String)
  private case class Life(createdBy: UUID, created: Instant, expires: Instant, started: Option[Instant],
    finished: Option[Instant], code: Option[String], message: Option[String], incomplete: Boolean)
  private case class Control(pauseReason: Option[String], pauseRequested: Option[Instant], paused: Option[Instant],
    rollbackAt: Option[Instant], rollbackBy: Option[UUID], rollbackScope: Option[String], next: Instant,
    phaseStarted: Instant, token: Option[UUID], version: Long, updated: Instant)
  private case class Row(ids: Ids, progress: Progress, life: Life, control: Control) {
    def domain: RemnawaveFleetRollout = {
      val snapshot = parse(progress.snapshot).toOption.flatMap(FleetRolloutSnapshotCodec.decode(_).toOption)
        .getOrElse(throw new IllegalStateException("Invalid fleet rollout snapshot"))
      RemnawaveFleetRollout(ids.id, ids.org, ids.integration, ids.fleet, ids.revision, ids.request,
        FleetRolloutState.fromCode(progress.state).get, FleetRolloutPhase.fromCode(progress.phase).get,
        snapshot, progress.hash, progress.wave, progress.waveCount, progress.pauseAfterCanary,
        progress.automatic, FleetRollbackScope.fromCode(progress.scope).get, life.createdBy, life.created,
        life.expires, life.started, life.finished, life.code, life.message, life.incomplete,
        control.pauseReason, control.pauseRequested, control.paused, control.rollbackAt, control.rollbackBy,
        control.rollbackScope.flatMap(FleetRollbackScope.fromCode), control.next, control.phaseStarted,
        control.token, control.version, control.updated)
    }
  }
  private val columns = fr"""id,organization_id,integration_id,fleet_id,fleet_revision_id,request_id,
    state,phase,input_snapshot::text,snapshot_hash::text,current_wave,wave_count,pause_after_canary,
    automatic_rollback,rollback_scope,created_by,created_at,expires_at,started_at,finished_at,failure_code,
    safe_message,rollback_incomplete,pause_reason,pause_requested_at,paused_at,rollback_requested_at,
    rollback_requested_by,rollback_requested_scope,next_run_at,phase_started_at,claim_token,version,updated_at"""
  private def where(condition: Fragment) =
    (fr"select" ++ columns ++ fr"from remnawave_fleet_rollout where" ++ condition).query[Row].map(_.domain)

  private case class MemberRow(id: UUID, org: UUID, rollout: UUID, fleet: UUID, membership: UUID,
    membershipVersion: Long, node: UUID, resource: UUID, external: Option[String], wave: Int, position: Int,
    state: String, skip: Option[String], planned: List[String], code: Option[String], message: Option[String],
    rollbackCode: Option[String], started: Option[Instant], finished: Option[Instant], version: Long,
    updated: Instant) {
    def domain = RemnawaveFleetRolloutMember(id, org, rollout, fleet, membership, membershipVersion, node,
      resource, external.getOrElse(""), wave, position, FleetRolloutMemberState.fromCode(state).get, skip,
      planned.flatMap(FleetActionKind.fromCode), code, message, rollbackCode, started, finished, version, updated)
  }
  private val memberColumns = fr"""id,organization_id,rollout_id,fleet_id,membership_id,membership_version,
    inventory_node_id,resource_id,external_node_id,wave,position,state,skip_reason,planned_actions,failure_code,
    safe_message,rollback_failure_code,started_at,finished_at,version,updated_at"""

  private case class ActionRow(id: UUID, org: UUID, rollout: UUID, member: Option[UUID], direction: String,
    kind: String, sequence: Int, state: String, request: UUID, plan: Option[UUID], run: Option[UUID],
    config: Option[UUID], intent: Option[String], code: Option[String], message: Option[String],
    started: Option[Instant], finished: Option[Instant], version: Long, updated: Instant, desired: Option[UUID]) {
    def domain = RemnawaveFleetRolloutAction(id, org, rollout, member, direction == "ROLLBACK",
      FleetActionKind.fromCode(kind).get, sequence, FleetActionState.fromCode(state).get, request, plan, run,
      config, intent.flatMap(parse(_).toOption), code, message, started, finished, version, updated, desired)
  }
  private val actionColumns = fr"""id,organization_id,rollout_id,member_id,direction,kind,sequence,state,
    child_request_id,server_profile_plan_id,server_profile_run_id,config_rollout_id,intent::text,failure_code,
    safe_message,started_at,finished_at,version,updated_at,desired_state_action_id"""

  def insertPlan(r: RemnawaveFleetRollout, members: List[RemnawaveFleetRolloutMember]): ConnectionIO[Unit] = for {
    _ <- sql"""insert into remnawave_fleet_rollout(id,organization_id,integration_id,fleet_id,fleet_revision_id,
        request_id,state,phase,input_snapshot,snapshot_hash,current_wave,wave_count,pause_after_canary,
        automatic_rollback,rollback_scope,created_by,created_at,expires_at,next_run_at,phase_started_at,version,
        updated_at)
      values(${r.id},${r.organizationId},${r.integrationId},${r.fleetId},${r.fleetRevisionId},null,'PLANNED',
        ${r.phase.code},cast(${r.snapshot.json.noSpaces} as jsonb),${r.snapshotHash},0,${r.waveCount},
        ${r.pauseAfterCanary},${r.automaticRollback},${r.rollbackScope.code},${r.createdBy},${r.createdAt},
        ${r.expiresAt},${r.nextRunAt},${r.phaseStartedAt},1,${r.updatedAt})""".update.run
    _ <- members.traverse_ { m =>
      val planned = m.plannedActions.map(_.code)
      sql"""insert into remnawave_fleet_rollout_member(id,organization_id,rollout_id,fleet_id,membership_id,
          membership_version,inventory_node_id,resource_id,external_node_id,wave,position,state,skip_reason,
          planned_actions,version,updated_at)
        values(${m.id},${m.organizationId},${m.rolloutId},${m.fleetId},${m.membershipId},${m.membershipVersion},
          ${m.inventoryNodeId},${m.resourceId},${m.externalNodeId},${m.wave},${m.position},${m.state.code},
          ${m.skipReason},$planned::text[],1,${m.updatedAt})""".update.run
    }
  } yield ()

  def rollout(org: UUID, fleetId: UUID, id: UUID): ConnectionIO[Option[RemnawaveFleetRollout]] =
    where(fr"organization_id=$org and fleet_id=$fleetId and id=$id").option
  def rolloutForUpdate(org: UUID, fleetId: UUID, id: UUID): ConnectionIO[Option[RemnawaveFleetRollout]] =
    where(fr"organization_id=$org and fleet_id=$fleetId and id=$id for update").option
  def rolloutById(id: UUID): ConnectionIO[Option[RemnawaveFleetRollout]] = where(fr"id=$id").option
  def byRequest(org: UUID, requestId: UUID): ConnectionIO[Option[RemnawaveFleetRollout]] =
    where(fr"organization_id=$org and request_id=$requestId").option
  def history(org: UUID, fleetId: UUID, limit: Int): ConnectionIO[List[RemnawaveFleetRollout]] =
    where(fr"organization_id=$org and fleet_id=$fleetId order by created_at desc,id limit $limit").to[List]
  def activeOf(org: UUID, fleetId: UUID): ConnectionIO[Option[RemnawaveFleetRollout]] =
    where(fr"""organization_id=$org and fleet_id=$fleetId
      and state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK')""").option

  def start(org: UUID, id: UUID, requestId: UUID, now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_rollout set state='QUEUED',request_id=$requestId,phase='VALIDATE',next_run_at=$now,
      phase_started_at=$now,version=version+1,updated_at=$now
    where organization_id=$org and id=$id and state='PLANNED' and expires_at>$now and not exists(
      select 1 from remnawave_fleet_rollout o where o.fleet_id=remnawave_fleet_rollout.fleet_id
        and o.state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))""".update.run.map(_ == 1).adaptError {
      case e: SQLException if e.getSQLState == "23514" && e.getMessage.contains("REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED") =>
        IntegrationError("REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED", "The preview changed; preview again")
      case e: SQLException if e.getSQLState == "23514" =>
        IntegrationError("REMNAWAVE_FLEET_ROLLOUT_RESOURCE_BUSY", "A conflicting workflow is active on a target")
      case e: SQLException if e.getSQLState == "23505" =>
        IntegrationError("REMNAWAVE_FLEET_ROLLOUT_REQUEST_REUSED", "Request ID belongs to another rollout")
    }

  def members(rolloutId: UUID): ConnectionIO[List[RemnawaveFleetRolloutMember]] =
    (fr"select" ++ memberColumns ++
      fr"from remnawave_fleet_rollout_member where rollout_id=$rolloutId order by wave,position,id")
      .query[MemberRow].map(_.domain).to[List]

  def actions(rolloutId: UUID): ConnectionIO[List[RemnawaveFleetRolloutAction]] =
    (fr"select" ++ actionColumns ++
      fr"from remnawave_fleet_rollout_action where rollout_id=$rolloutId order by sequence,id")
      .query[ActionRow].map(_.domain).to[List]

  def requestPause(org: UUID, id: UUID, by: UUID, now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_rollout set
      state=case when state='QUEUED' and claim_token is null then 'PAUSED' else state end,
      paused_at=case when state='QUEUED' and claim_token is null then $now else paused_at end,
      pause_reason=case when state='QUEUED' and claim_token is null then 'OPERATOR' else pause_reason end,
      pause_requested_at=case when state='QUEUED' and claim_token is null then null
        else coalesce(pause_requested_at,$now) end,
      pause_requested_by=coalesce(pause_requested_by,$by),version=version+1,updated_at=$now
    where organization_id=$org and id=$id and state in ('QUEUED','RUNNING')""".update.run.map(_ == 1)

  def resume(org: UUID, id: UUID, now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_rollout set state='QUEUED',paused_at=null,pause_reason=null,pause_requested_at=null,
      next_run_at=$now,version=version+1,updated_at=$now
    where organization_id=$org and id=$id and state='PAUSED' and rollback_requested_at is null"""
    .update.run.map(_ == 1)

  def requestRollback(org: UUID, id: UUID, by: UUID, scope: FleetRollbackScope,
    now: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_rollout set rollback_requested_at=$now,rollback_requested_by=$by,
      rollback_requested_scope=${scope.code},
      state=case when state='PAUSED' then 'QUEUED' else state end,
      paused_at=null,pause_reason=null,pause_requested_at=null,next_run_at=$now,version=version+1,updated_at=$now
    where organization_id=$org and id=$id and state in ('QUEUED','RUNNING','PAUSED')
      and rollback_requested_at is null""".update.run.map(_ == 1)

  def claimDue(owner: UUID, token: UUID, now: Instant, until: Instant,
    limit: Int): ConnectionIO[List[RemnawaveFleetRollout]] =
    (fr"""with picked as(
      select id from remnawave_fleet_rollout
      where state in ('QUEUED','RUNNING','ROLLING_BACK') and next_run_at<=greatest($now,clock_timestamp())
        and (claim_deadline is null or claim_deadline<=greatest($now,clock_timestamp()))
      order by next_run_at,id for update skip locked limit $limit), changed as(
      update remnawave_fleet_rollout r set claim_owner=$owner,claim_token=$token,claim_deadline=$until
      from picked where r.id=picked.id returning r.*)
      select""" ++ columns ++ fr"from changed").query[Row].map(_.domain).to[List]

  def renewClaim(id: UUID, token: UUID, now: Instant, until: Instant): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_rollout set claim_deadline=$until
    where id=$id and claim_token=$token and claim_deadline>greatest($now,clock_timestamp())"""
    .update.run.map(_ == 1)

  def save(r: RemnawaveFleetRollout, token: UUID, now: Instant, clearPauseRequest: Boolean,
    release: Boolean): ConnectionIO[Boolean] = sql"""
    update remnawave_fleet_rollout set state=${r.state.code},phase=${r.phase.code},current_wave=${r.currentWave},
      started_at=${r.startedAt},finished_at=${r.finishedAt},failure_code=${r.failureCode},
      safe_message=${r.safeMessage},rollback_incomplete=${r.rollbackIncomplete},pause_reason=${r.pauseReason},
      paused_at=${r.pausedAt},
      rollback_scope=${r.rollbackScope.code},
      pause_requested_at=case when $clearPauseRequest then null else pause_requested_at end,
      next_run_at=${r.nextRunAt},phase_started_at=${r.phaseStartedAt},
      claim_owner=case when $release then null else claim_owner end,
      claim_token=case when $release then null else claim_token end,
      claim_deadline=case when $release then null else claim_deadline end,
      version=version+1,updated_at=$now
    where id=${r.id} and claim_token=$token and claim_deadline>greatest($now,clock_timestamp())"""
    .update.run.map(_ == 1)

  private def fenced(rolloutId: UUID, token: UUID, now: Instant) = fr"""exists(select 1 from
    remnawave_fleet_rollout r where r.id=$rolloutId and r.claim_token=$token
    and r.claim_deadline>greatest($now,clock_timestamp()) for update)"""

  def saveMember(m: RemnawaveFleetRolloutMember, token: UUID, now: Instant): ConnectionIO[Boolean] =
    (fr"""update remnawave_fleet_rollout_member set state=${m.state.code},failure_code=${m.failureCode},
      safe_message=${m.safeMessage},rollback_failure_code=${m.rollbackFailureCode},started_at=${m.startedAt},
      finished_at=${m.finishedAt},version=version+1,updated_at=$now
      where id=${m.id} and rollout_id=${m.rolloutId} and """ ++ fenced(m.rolloutId, token, now))
      .update.run.map(_ == 1)

  def saveAction(a: RemnawaveFleetRolloutAction, token: UUID, now: Instant): ConnectionIO[Boolean] = {
    val direction = if (a.rollback) "ROLLBACK" else "FORWARD"
    val intent = a.intent.map(_.noSpaces)
    (fr"""insert into remnawave_fleet_rollout_action(id,organization_id,rollout_id,member_id,direction,kind,
        sequence,state,child_request_id,server_profile_plan_id,server_profile_run_id,config_rollout_id,intent,
        failure_code,safe_message,started_at,finished_at,version,updated_at)
      select ${a.id},${a.organizationId},${a.rolloutId},${a.memberId},$direction,${a.kind.code},${a.sequence},
        ${a.state.code},${a.childRequestId},${a.serverProfilePlanId},${a.serverProfileRunId},${a.configRolloutId},
        cast($intent as jsonb),${a.failureCode},${a.safeMessage},${a.startedAt},${a.finishedAt},1,$now
      where """ ++ fenced(a.rolloutId, token, now) ++ fr"""
      on conflict (id) do update set state=excluded.state,server_profile_plan_id=excluded.server_profile_plan_id,
        server_profile_run_id=excluded.server_profile_run_id,config_rollout_id=excluded.config_rollout_id,
        intent=excluded.intent,failure_code=excluded.failure_code,safe_message=excluded.safe_message,
        started_at=excluded.started_at,finished_at=excluded.finished_at,
        version=remnawave_fleet_rollout_action.version+1,updated_at=excluded.updated_at
      where remnawave_fleet_rollout_action.rollout_id=excluded.rollout_id""").update.run.map(_ == 1)
  }

  def purgeExpired(now: Instant, limit: Int): ConnectionIO[Int] = sql"""
    delete from remnawave_fleet_rollout where id in(
      select id from remnawave_fleet_rollout where state='PLANNED' and expires_at<=$now
      order by expires_at limit $limit for update skip locked)""".update.run
}
