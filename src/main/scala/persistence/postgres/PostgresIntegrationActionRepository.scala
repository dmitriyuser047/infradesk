package ru.bitec.app.ops
package persistence.postgres

import application.port.IntegrationActionRepository
import cats.syntax.all._
import domain.integration._
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresIntegrationActionRepository extends IntegrationActionRepository[ConnectionIO] {
  private final case class Row(id: UUID, org: UUID, integration: UUID, obj: UUID, request: UUID,
    action: String, externalId: String, name: String, user: UUID, status: String, created: Instant,
    started: Option[Instant], recover: Option[Instant], finished: Option[Instant], owner: Option[UUID],
    token: Option[UUID], error: Option[String], message: Option[String], updated: Instant) {
    def domain: ConnectionIO[IntegrationActionExecution] =
      (IntegrationActionCode.fromCode(action), IntegrationActionStatus.fromCode(status)) match {
        case (Some(a), Some(s)) => IntegrationActionExecution(id, org, integration,
          IntegrationActionTarget(obj, IntegrationObjectType.Node, externalId, name), request, a, user,
          s, created, started, recover, finished, owner, token, error, message, updated).pure[ConnectionIO]
        case _ => new IllegalStateException("Invalid integration action row").raiseError[ConnectionIO, IntegrationActionExecution]
      }
  }
  private val columns: Fragment = fr"""id, organization_id, integration_id, inventory_object_id, request_id,
    action_code, external_id_snapshot, display_name_snapshot, requested_by_user_id, status, created_at,
    started_at, recover_after_at, finished_at, claimed_by, claim_token, error_code, error_message, updated_at"""

  override def insertOrFind(value: IntegrationActionExecution): ConnectionIO[(IntegrationActionExecution, Boolean)] = {
    val target = value.target
    sql"""insert into integration_action_execution (id, organization_id, integration_id, inventory_object_id,
      request_id, action_code, external_id_snapshot, display_name_snapshot, requested_by_user_id, status,
      created_at, updated_at) values (${value.id}, ${value.organizationId}, ${value.integrationId},
      ${target.inventoryObjectId}, ${value.requestId}, ${value.action.code}, ${target.externalId},
      ${target.displayName}, ${value.requestedByUserId}, 'QUEUED', ${value.createdAt}, ${value.updatedAt})
      on conflict do nothing""".update.run.flatMap {
      case 1 => (value, true).pure[ConnectionIO]
      case _ => (fr"select" ++ columns ++ fr"from integration_action_execution where organization_id = ${value.organizationId} and request_id = ${value.requestId}")
        .query[Row].option.flatMap {
          case Some(row) => row.domain.map(_ -> false)
          case None => application.integration.IntegrationError("INTEGRATION_ACTION_ALREADY_RUNNING",
            "An action is already active on this node").raiseError[ConnectionIO, (IntegrationActionExecution, Boolean)]
        }
    }
  }

  override def find(org: UUID, integration: UUID, id: UUID): ConnectionIO[Option[IntegrationActionExecution]] =
    (fr"select" ++ columns ++ fr"from integration_action_execution where organization_id = $org and integration_id = $integration and id = $id")
      .query[Row].option.flatMap(_.traverse(_.domain))

  override def findByRequest(org: UUID, requestId: UUID): ConnectionIO[Option[IntegrationActionExecution]] =
    (fr"select" ++ columns ++ fr"from integration_action_execution where organization_id = $org and request_id = $requestId")
      .query[Row].option.flatMap(_.traverse(_.domain))

  override def recent(org: UUID, integration: UUID, limit: Int): ConnectionIO[List[IntegrationActionExecution]] =
    (fr"select" ++ columns ++ fr""", (select u.display_name from user_account u where u.id = requested_by_user_id)
      from integration_action_execution where organization_id = $org and integration_id = $integration
      order by created_at desc, id desc limit $limit""")
      .query[(Row, Option[String])].to[List].flatMap(_.traverse { case (row, name) =>
        row.domain.map(_.copy(requestedByName = name)) })

  override def latestUnknownFinishedAt(org: UUID, integration: UUID, obj: UUID): ConnectionIO[Option[Instant]] =
    sql"""select max(finished_at) from integration_action_execution where organization_id = $org
      and integration_id = $integration and inventory_object_id = $obj and status = 'UNKNOWN'"""
      .query[Option[Instant]].unique

  override def hasActive(org: UUID, integration: UUID): ConnectionIO[Boolean] =
    sql"""select exists(select 1 from integration_action_execution where organization_id = $org
      and integration_id = $integration and status in ('QUEUED', 'RUNNING'))""".query[Boolean].unique

  override def recoverAndClaim(owner: UUID, token: UUID, at: Instant, recoverAfter: Instant,
    limit: Int): ConnectionIO[(Int, List[IntegrationActionExecution])] = for {
    // Lock the same inventory row that a new request locks for validation. Otherwise a request
    // could check the UNKNOWN refresh gate while this transaction retires RUNNING, then insert
    // after the active slot has been released without observing UNKNOWN.
    recovered <- sql"""with stale as (
        select a.id from integration_action_execution a
        join integration_inventory_object o on o.id = a.inventory_object_id
          and o.integration_id = a.integration_id and o.organization_id = a.organization_id
        where a.status = 'RUNNING' and a.recover_after_at < $at
        order by a.recover_after_at, a.id for update of a, o skip locked limit $limit
      ), recovered as (
        update integration_action_execution a set status = 'UNKNOWN', finished_at = $at,
          error_code = 'INTEGRATION_ACTION_RESULT_UNKNOWN', error_message = 'Remote result is unknown',
          updated_at = $at from stale where a.id = stale.id
          returning a.organization_id, a.integration_id
      ), nudged as (
        update integration_sync_state s set next_run_at = least(s.next_run_at, $at),
          action_nudge_at = least(coalesce(s.action_nudge_at, $at), $at), updated_at = $at
        from integration i where s.integration_id = i.id and s.organization_id = i.organization_id
          and i.enabled = true and exists (select 1 from recovered r where r.organization_id = s.organization_id
            and r.integration_id = s.integration_id)
        returning s.integration_id
      ) select count(*) from recovered""".query[Long].unique.map(_.toInt)
    rows <- (fr"""with selected as (select id from integration_action_execution where status = 'QUEUED'
      order by created_at, id for update skip locked limit $limit)
      update integration_action_execution a set status = 'RUNNING', claimed_by = $owner,
      claim_token = $token, started_at = $at, recover_after_at = $recoverAfter, updated_at = $at
      from selected where a.id = selected.id returning a.*""").query[Row].to[List]
    claimed <- rows.traverse(_.domain)
  } yield (recovered, claimed)

  override def complete(value: IntegrationActionExecution, token: UUID, at: Instant,
    status: String, errorCode: Option[String], errorMessage: Option[String]): ConnectionIO[Boolean] = for {
    // Serializes UNKNOWN completion with request validation on this object.
    _ <- sql"""select id from integration_inventory_object where id = ${value.target.inventoryObjectId}
      and integration_id = ${value.integrationId} and organization_id = ${value.organizationId}
      for update""".query[UUID].option.void
    count <- sql"""update integration_action_execution set status = $status, finished_at = $at,
      error_code = $errorCode, error_message = $errorMessage, updated_at = $at
      where id = ${value.id} and organization_id = ${value.organizationId} and status = 'RUNNING'
        and claim_token = $token""".update.run
    _ <- if (count == 1 && (status == "SUCCEEDED" || status == "UNKNOWN"))
      sql"""update integration_sync_state s set next_run_at = least(s.next_run_at, $at),
        action_nudge_at = least(coalesce(s.action_nudge_at, $at), $at), updated_at = $at
        from integration i where s.integration_id = i.id and s.organization_id = i.organization_id
        and i.organization_id = ${value.organizationId} and i.id = ${value.integrationId}
        and i.enabled = true""".update.run.void else ().pure[ConnectionIO]
  } yield count == 1
}
