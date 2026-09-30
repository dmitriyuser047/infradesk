package ru.bitec.app.ops
package persistence.postgres

import application.integration.IntegrationError
import application.port.IntegrationConfigRolloutRepository
import cats.syntax.all._
import domain.integration._
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

private object IntegrationConfigRolloutRows {
  final case class Row(id: UUID, organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID,
    bindingId: UUID, configurationProfileId: UUID, baselineRevisionId: Option[UUID],
    baselineRevisionNumber: Option[Int], baselineSha256: Option[String], targetRevisionId: UUID,
    targetRevisionNumber: Int, targetSha256: String, requestId: UUID, requestedByUserId: UUID,
    automaticRollback: Boolean, status: String, baselineSyncSessionId: Option[UUID],
    targetVerificationSyncSessionId: Option[UUID], rollbackVerificationSyncSessionId: Option[UUID],
    targetDeploymentId: Option[UUID], rollbackDeploymentId: Option[UUID],
    verificationDeadlineAt: Option[Instant], claimedBy: Option[UUID], claimToken: Option[UUID],
    claimUntil: Option[Instant], errorCode: Option[String], errorMessage: Option[String], createdAt: Instant,
    startedAt: Option[Instant], finishedAt: Option[Instant], updatedAt: Instant,
    affectedNodeCount: Int, baselineHealthyNodeCount: Int, preexistingUnhealthyNodeCount: Int) {
    def domain = IntegrationConfigRolloutStatus.fromCode(status).map(s => IntegrationConfigRollout(id,
      organizationId, integrationId, inventoryObjectId, bindingId, configurationProfileId,
      baselineRevisionId, baselineRevisionNumber, baselineSha256, targetRevisionId, targetRevisionNumber,
      targetSha256, requestId, requestedByUserId, automaticRollback, s, baselineSyncSessionId,
      targetVerificationSyncSessionId, rollbackVerificationSyncSessionId, targetDeploymentId,
      rollbackDeploymentId, verificationDeadlineAt, claimedBy, claimToken, claimUntil, errorCode,
      errorMessage, createdAt, startedAt, finishedAt, updatedAt, affectedNodeCount,
      baselineHealthyNodeCount, preexistingUnhealthyNodeCount))
  }
  val columns: Fragment = fr"""r.id, r.organization_id, r.integration_id, r.inventory_object_id,
    r.binding_id, r.configuration_profile_id, r.baseline_revision_id, r.baseline_revision_number,
    r.baseline_sha256, r.target_revision_id, r.target_revision_number, r.target_sha256,
    r.request_id, r.requested_by_user_id, r.automatic_rollback, r.status,
    r.baseline_sync_session_id, r.target_verification_sync_session_id,
    r.rollback_verification_sync_session_id, r.target_deployment_id, r.rollback_deployment_id,
    r.verification_deadline_at, r.claimed_by, r.claim_token, r.claim_until,
    r.error_code, r.error_message, r.created_at, r.started_at, r.finished_at, r.updated_at,
    r.affected_node_count, r.baseline_healthy_node_count, r.preexisting_unhealthy_node_count"""
}

final class PostgresIntegrationConfigRolloutRepository extends IntegrationConfigRolloutRepository[ConnectionIO] {
  import IntegrationConfigRolloutRows._
  private val active = fr"('PREPARING','APPLYING','VERIFYING','ROLLBACK_APPLYING','ROLLBACK_VERIFYING')"

  private def decode(rows: List[Row]): ConnectionIO[List[IntegrationConfigRollout]] =
    rows.traverse(_.domain.liftTo[ConnectionIO])

  override def findByRequest(org: UUID, requestId: UUID) =
    (fr"select" ++ columns ++ fr"from integration_config_rollout r where r.organization_id=$org and r.request_id=$requestId")
      .query[Row].option.flatMap(_.traverse(_.domain.liftTo[ConnectionIO]))

  override def insertOrFind(value: IntegrationConfigRollout) =
    sql"""insert into integration_config_rollout (id, organization_id, integration_id,
      inventory_object_id, binding_id, configuration_profile_id, target_revision_id,
      target_revision_number, target_sha256, request_id, requested_by_user_id, automatic_rollback,
      status, created_at, updated_at) values (${value.id}, ${value.organizationId},
      ${value.integrationId}, ${value.inventoryObjectId}, ${value.bindingId},
      ${value.configurationProfileId}, ${value.targetRevisionId}, ${value.targetRevisionNumber},
      ${value.targetSha256}, ${value.requestId}, ${value.requestedByUserId}, ${value.automaticRollback},
      'PREPARING', ${value.createdAt}, ${value.updatedAt}) on conflict do nothing""".update.run.flatMap {
      case 1 => (value, true).pure[ConnectionIO]
      case _ => findByRequest(value.organizationId, value.requestId).flatMap {
        case Some(existing) => (existing, false).pure[ConnectionIO]
        case None => IntegrationError("INTEGRATION_CONFIG_ROLLOUT_ALREADY_RUNNING",
          "A guarded rollout is already active").raiseError[ConnectionIO, (IntegrationConfigRollout, Boolean)]
      }
    }

  override def find(org: UUID, id: UUID) =
    (fr"select" ++ columns ++ fr"from integration_config_rollout r where r.organization_id=$org and r.id=$id")
      .query[Row].option.flatMap(_.traverse(_.domain.liftTo[ConnectionIO]))

  override def recent(org: UUID, integration: UUID, obj: UUID, limit: Int) =
    (fr"select" ++ columns ++ fr"""from integration_config_rollout r where r.organization_id=$org
      and r.integration_id=$integration and r.inventory_object_id=$obj
      order by r.created_at desc, r.id desc limit $limit""").query[Row].to[List].flatMap(decode)

  override def hasActive(org: UUID, integration: UUID, obj: Option[UUID]) = {
    val objectFilter = obj.fold(Fragment.empty)(id => fr"and inventory_object_id=$id")
    (fr"select exists(select 1 from integration_config_rollout where organization_id=$org and integration_id=$integration and status in" ++
      active ++ objectFilter ++ fr")").query[Boolean].unique
  }

  override def cancel(org: UUID, id: UUID, at: Instant) =
    sql"""update integration_config_rollout set status='CANCELLED', finished_at=$at, updated_at=$at
      where organization_id=$org and id=$id and status='PREPARING' and target_deployment_id is null
      returning id""".query[UUID].option.flatMap {
      case Some(_) => find(org, id)
      case None => find(org, id).flatMap {
        case Some(r) if r.status == IntegrationConfigRolloutStatus.Cancelled =>
          (Some(r): Option[IntegrationConfigRollout]).pure[ConnectionIO]
        case Some(_) => IntegrationError("INTEGRATION_CONFIG_ROLLOUT_CANNOT_CANCEL",
          "The rollout can no longer be cancelled").raiseError[ConnectionIO, Option[IntegrationConfigRollout]]
        case None => none[IntegrationConfigRollout].pure[ConnectionIO]
      }
    }

  override def preview(org: UUID, integration: UUID, obj: UUID, target: Int) =
    sql"""select baseline.revision_number, $target, remote.hash, target.content_sha256,
      count(n.id)::integer,
      count(n.id) filter (where (n.summary->>'isDisabled')::boolean or
        (n.summary->>'isConnected')::boolean)::integer,
      count(n.id) filter (where not (n.summary->>'isDisabled')::boolean and
        not (n.summary->>'isConnected')::boolean)::integer
      from integration_inventory_object p
      join integration_config_profile_binding b on b.inventory_object_id=p.id and b.detached_at is null
      join configuration_revision tr on tr.profile_id=b.configuration_profile_id and tr.revision_number=$target
      join configuration_revision_secure_payload target on target.revision_id=tr.id
      cross join lateral (select p.summary->>'configSha256' as hash) remote
      join configuration_revision_secure_payload bs on bs.profile_id=b.configuration_profile_id
        and bs.content_sha256=remote.hash
      join configuration_revision baseline on baseline.id=bs.revision_id
      left join integration_inventory_object n on n.organization_id=p.organization_id
        and n.integration_id=p.integration_id and n.object_type='NODE' and n.is_active
        and n.last_seen_sync_session_id=p.last_seen_sync_session_id
        and n.summary->>'activeConfigProfileUuid'=p.external_id
      where p.organization_id=$org and p.integration_id=$integration and p.id=$obj and p.is_active
      group by baseline.revision_number, remote.hash, target.content_sha256"""
      .query[(Int, Int, String, String, Int, Int, Int)].option.map(_.map {
        case (b, t, bh, th, affected, healthy, unhealthy) =>
          IntegrationConfigRolloutPreview(b, t, bh, th, affected, healthy, unhealthy)
      })

  override def recoverAndClaim(owner: UUID, token: UUID, at: Instant, until: Instant, limit: Int) =
    (fr"""with due as (select id from integration_config_rollout where status in""" ++ active ++
      fr"""and (claim_until is null or claim_until < $at) order by updated_at, id
      for update skip locked limit $limit) update integration_config_rollout r
      set claimed_by=$owner, claim_token=$token, claim_until=$until
      from due where r.id=due.id returning""" ++ columns).query[Row].to[List].flatMap(decode)

  override def prepare(value: IntegrationConfigRollout, token: UUID, deploymentId: UUID,
    deploymentRequestId: UUID, at: Instant): ConnectionIO[Boolean] = {
    val owns = sql"""select 1 from integration_config_rollout where id=${value.id}
      and status='PREPARING' and claim_token=$token for update""".query[Int].option.map(_.nonEmpty)
    val fresh = sql"""select s.id, p.external_id, p.summary->>'configSha256'
      from integration_sync_session s join integration_inventory_object p
        on p.organization_id=s.organization_id and p.integration_id=s.integration_id
        and p.last_seen_sync_session_id=s.id and p.id=${value.inventoryObjectId}
      where s.organization_id=${value.organizationId} and s.integration_id=${value.integrationId}
        and s.status='COMPLETED' and s.started_at >= ${value.createdAt} and p.is_active
      order by s.finished_at desc limit 1""".query[(UUID, String, Option[String])].option
    owns.flatMap { case false => false.pure[ConnectionIO]; case true => fresh.flatMap {
      case None => false.pure[ConnectionIO]
      case Some((_, _, None)) => terminal(value, token, at, "FAILED", "INTEGRATION_CONFIG_REMOTE_DRIFT",
        "Remote configuration has no verifiable hash", None)
      case Some((session, externalId, Some(hash))) =>
        sql"""select r.id, r.revision_number from configuration_revision r
          join configuration_revision_secure_payload s on s.revision_id=r.id
          where r.organization_id=${value.organizationId} and r.profile_id=${value.configurationProfileId}
            and s.content_sha256=$hash order by r.revision_number desc limit 1""".query[(UUID, Int)].option.flatMap {
          case None => terminal(value, token, at, "FAILED", "INTEGRATION_CONFIG_REMOTE_DRIFT",
            "Remote configuration does not match a local revision", Some(session))
          case Some(_) if hash == value.targetSha256 => terminal(value, token, at, "FAILED",
            "INTEGRATION_CONFIG_ALREADY_APPLIED", "Target revision is already applied", Some(session))
          case Some((baselineId, baselineNumber)) => for {
            _ <- sql"""insert into integration_config_rollout_node_baseline
              (rollout_id, organization_id, integration_id, inventory_object_id, external_id_snapshot,
               display_name_snapshot, was_disabled, was_connected, was_connecting,
               active_config_profile_uuid_snapshot, observed_at)
              select ${value.id}, n.organization_id, n.integration_id, n.id, n.external_id, n.display_name,
                (n.summary->>'isDisabled')::boolean, (n.summary->>'isConnected')::boolean,
                (n.summary->>'isConnecting')::boolean, n.summary->>'activeConfigProfileUuid', n.last_seen_at
              from integration_inventory_object n where n.organization_id=${value.organizationId}
                and n.integration_id=${value.integrationId} and n.object_type='NODE' and n.is_active
                and n.last_seen_sync_session_id=$session
                and n.summary->>'activeConfigProfileUuid'=$externalId on conflict do nothing""".update.run
            inserted <- sql"""insert into integration_config_deployment
              (id, organization_id, integration_id, inventory_object_id, binding_id,
               configuration_profile_id, configuration_revision_id, revision_number, request_id,
               requested_by_user_id, status, expected_remote_sha256, desired_sha256, created_at,
               source, rollout_id) values ($deploymentId, ${value.organizationId}, ${value.integrationId},
               ${value.inventoryObjectId}, ${value.bindingId}, ${value.configurationProfileId},
               ${value.targetRevisionId}, ${value.targetRevisionNumber}, $deploymentRequestId,
               ${value.requestedByUserId}, 'QUEUED', $hash, ${value.targetSha256}, $at,
               'ROLLOUT_TARGET', ${value.id}) on conflict do nothing""".update.run
            moved <- if (inserted == 1) sql"""update integration_config_rollout set status='APPLYING',
              baseline_revision_id=$baselineId, baseline_revision_number=$baselineNumber,
              baseline_sha256=$hash, baseline_sync_session_id=$session, target_deployment_id=$deploymentId,
              affected_node_count=(select count(*) from integration_config_rollout_node_baseline where rollout_id=${value.id}),
              baseline_healthy_node_count=(select count(*) from integration_config_rollout_node_baseline
                where rollout_id=${value.id} and (was_disabled or was_connected)),
              preexisting_unhealthy_node_count=(select count(*) from integration_config_rollout_node_baseline
                where rollout_id=${value.id} and not was_disabled and not was_connected),
              started_at=coalesce(started_at,$at), updated_at=$at, claimed_by=null, claim_token=null, claim_until=null
              where id=${value.id} and status='PREPARING' and claim_token=$token""".update.run.map(_ == 1)
            else false.pure[ConnectionIO]
          } yield moved
        }
    }}
  }

  override def childDeployment(org: UUID, id: UUID) = {
    import IntegrationConfigDeploymentRows._
    (fr"select" ++ IntegrationConfigDeploymentRows.columns ++
      fr"from integration_config_deployment d where d.organization_id=$org and d.id=$id")
      .query[IntegrationConfigDeploymentRows.Row].option.flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
  }

  override def beginVerification(value: IntegrationConfigRollout, token: UUID, at: Instant,
    deadline: Instant, rollback: Boolean) = {
    val expected = if (rollback) "ROLLBACK_APPLYING" else "APPLYING"
    val next = if (rollback) "ROLLBACK_VERIFYING" else "VERIFYING"
    sql"""update integration_config_rollout set status=$next, verification_deadline_at=$deadline,
      updated_at=$at, claimed_by=null, claim_token=null, claim_until=null
      where id=${value.id} and status=$expected and claim_token=$token""".update.run.map(_ == 1)
  }

  private final case class InspectRow(sessionId: UUID, hash: Option[String], added: Int,
    externalId: Option[String], name: Option[String], wasDisabled: Option[Boolean],
    wasConnected: Option[Boolean], wasConnecting: Option[Boolean], oldProfile: Option[String],
    active: Option[Boolean], disabled: Option[Boolean], connected: Option[Boolean],
    connecting: Option[Boolean], newProfile: Option[String])

  override def inspect(value: IntegrationConfigRollout, after: Instant) = {
    sql"""with session as (select id from integration_sync_session where organization_id=${value.organizationId}
        and integration_id=${value.integrationId} and status='COMPLETED' and started_at > $after
        order by finished_at desc limit 1), profile as (
      select s.id session_id, p.external_id, p.summary->>'configSha256' hash from session s
      join integration_inventory_object p on p.id=${value.inventoryObjectId}
        and p.last_seen_sync_session_id=s.id and p.is_active), added as (
      select count(*)::integer n from profile p join integration_inventory_object n
        on n.organization_id=${value.organizationId} and n.integration_id=${value.integrationId}
        and n.object_type='NODE' and n.is_active and n.last_seen_sync_session_id=p.session_id
        and n.summary->>'activeConfigProfileUuid'=p.external_id
      left join integration_config_rollout_node_baseline b on b.rollout_id=${value.id}
        and b.inventory_object_id=n.id where b.inventory_object_id is null)
      select p.session_id, p.hash, a.n, b.external_id_snapshot, b.display_name_snapshot,
        b.was_disabled, b.was_connected, b.was_connecting, b.active_config_profile_uuid_snapshot,
        n.is_active, (n.summary->>'isDisabled')::boolean, (n.summary->>'isConnected')::boolean,
        (n.summary->>'isConnecting')::boolean, n.summary->>'activeConfigProfileUuid'
      from profile p cross join added a left join integration_config_rollout_node_baseline b
        on b.rollout_id=${value.id} left join integration_inventory_object n
        on n.id=b.inventory_object_id and n.last_seen_sync_session_id=p.session_id"""
      .query[InspectRow].to[List].map { rows => rows.headOption.map { head =>
        val baseline = rows.filter(_.externalId.nonEmpty)
        val details = baseline.map { row =>
          val before = if (row.wasDisabled.contains(true)) "Disabled"
            else if (row.wasConnected.contains(true)) "Connected" else "Disconnected"
          val after = if (row.active.isEmpty) None else if (row.disabled.contains(true)) Some("Disabled")
            else if (row.connected.contains(true)) Some("Connected")
            else if (row.connecting.contains(true)) Some("Connecting") else Some("Disconnected")
          val preexisting = row.wasDisabled.contains(false) && row.wasConnected.contains(false)
          val healthy = if (row.wasDisabled.contains(true)) row.disabled.contains(true)
            else row.connected.contains(true) && row.connecting.contains(false)
          val assignmentStable = row.newProfile == row.oldProfile
          val result = if (preexisting && assignmentStable) "PREEXISTING_UNHEALTHY"
            else if (healthy && assignmentStable) "HEALTHY" else "REGRESSION"
          IntegrationConfigRolloutNodeHealth(row.externalId.get, row.name.get, before, after, result)
        }
        val preexisting = details.count(_.result == "PREEXISTING_UNHEALTHY")
        val regressions = details.count(_.result == "REGRESSION")
        val assignmentDrift = baseline.exists(row => row.newProfile != row.oldProfile)
        IntegrationConfigRolloutInspection(head.sessionId, head.hash, details.size,
          details.count(_.result == "HEALTHY"), preexisting, regressions,
          head.added > 0 || assignmentDrift, details)
      }}
  }

  override def createRollback(value: IntegrationConfigRollout, token: UUID, deploymentId: UUID,
    requestId: UUID, verificationSessionId: UUID, at: Instant) = value.baselineRevisionId match {
    case None => false.pure[ConnectionIO]
    case Some(revisionId) => sql"""select 1 from integration_config_rollout
      where id=${value.id} and status='VERIFYING' and claim_token=$token for update"""
      .query[Int].option.map(_.nonEmpty).flatMap {
      case false => false.pure[ConnectionIO]
      case true => for {
      inserted <- sql"""insert into integration_config_deployment
        (id, organization_id, integration_id, inventory_object_id, binding_id,
         configuration_profile_id, configuration_revision_id, revision_number, request_id,
         requested_by_user_id, status, expected_remote_sha256, desired_sha256, created_at,
         source, rollout_id) values ($deploymentId, ${value.organizationId}, ${value.integrationId},
         ${value.inventoryObjectId}, ${value.bindingId}, ${value.configurationProfileId}, $revisionId,
         ${value.baselineRevisionNumber.get}, $requestId, ${value.requestedByUserId}, 'QUEUED',
         ${value.targetSha256}, ${value.baselineSha256.get}, $at, 'ROLLOUT_ROLLBACK', ${value.id})
         on conflict do nothing""".update.run
      moved <- if (inserted == 1) sql"""update integration_config_rollout
        set status='ROLLBACK_APPLYING', rollback_deployment_id=$deploymentId,
          target_verification_sync_session_id=$verificationSessionId,
          verification_deadline_at=null, updated_at=$at, claimed_by=null, claim_token=null, claim_until=null
        where id=${value.id} and status='VERIFYING' and claim_token=$token""".update.run.map(_ == 1)
      else false.pure[ConnectionIO]
      } yield moved
    }
  }

  override def finish(value: IntegrationConfigRollout, token: UUID, at: Instant, status: String,
    sessionId: Option[UUID], code: Option[String], message: Option[String]) = {
    val sessionColumn = value.status match {
      case IntegrationConfigRolloutStatus.Verifying => fr"target_verification_sync_session_id=$sessionId,"
      case IntegrationConfigRolloutStatus.RollbackVerifying => fr"rollback_verification_sync_session_id=$sessionId,"
      case _ => Fragment.empty
    }
    (fr"update integration_config_rollout set status=$status," ++ sessionColumn ++
      fr"""error_code=$code, error_message=$message, finished_at=$at, updated_at=$at,
      claimed_by=null, claim_token=null, claim_until=null where id=${value.id}
      and status=${value.status.code} and claim_token=$token""").update.run.map(_ == 1)
  }

  private def terminal(value: IntegrationConfigRollout, token: UUID, at: Instant, status: String,
    code: String, message: String, session: Option[UUID]) =
    sql"""update integration_config_rollout set status=$status, baseline_sync_session_id=$session,
      error_code=$code, error_message=$message, finished_at=$at, updated_at=$at,
      claimed_by=null, claim_token=null, claim_until=null
      where id=${value.id} and status=${value.status.code} and claim_token=$token""".update.run.map(_ == 1)

  override def release(value: IntegrationConfigRollout, token: UUID, at: Instant) =
    sql"""update integration_config_rollout set claimed_by=null, claim_token=null, claim_until=null,
      updated_at=$at where id=${value.id} and status=${value.status.code} and claim_token=$token"""
      .update.run.map(_ == 1)

  override def nodeHealth(org: UUID, rolloutId: UUID) =
    sql"""select b.external_id_snapshot, b.display_name_snapshot,
      case when b.was_disabled then 'Disabled' when b.was_connected then 'Connected' else 'Disconnected' end,
      case when n.id is null then null when (n.summary->>'isDisabled')::boolean then 'Disabled'
        when (n.summary->>'isConnected')::boolean then 'Connected'
        when (n.summary->>'isConnecting')::boolean then 'Connecting' else 'Disconnected' end,
      case when n.id is null then 'REGRESSION'
        when n.summary->>'activeConfigProfileUuid' is distinct from b.active_config_profile_uuid_snapshot
          then 'REGRESSION'
        when not b.was_disabled and not b.was_connected then 'PREEXISTING_UNHEALTHY'
        when b.was_disabled and (n.summary->>'isDisabled')::boolean then 'HEALTHY'
        when not b.was_disabled and b.was_connected and (n.summary->>'isConnected')::boolean
          and not (n.summary->>'isConnecting')::boolean then 'HEALTHY' else 'REGRESSION' end
      from integration_config_rollout_node_baseline b
      left join integration_config_rollout r on r.id=b.rollout_id
      left join integration_inventory_object n on n.id=b.inventory_object_id and
        n.last_seen_sync_session_id=coalesce(r.rollback_verification_sync_session_id,
          r.target_verification_sync_session_id)
      where b.organization_id=$org and b.rollout_id=$rolloutId order by b.display_name_snapshot"""
      .query[IntegrationConfigRolloutNodeHealth].to[List]
}
