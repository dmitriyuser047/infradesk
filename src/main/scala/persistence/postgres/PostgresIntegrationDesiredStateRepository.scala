package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.integration._
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

object IntegrationDesiredStateSql {
  /** The derived status, for aliases `d` (desired state), `o` (inventory object) and `la` (the
    * intent's latest action, left-joined). The same rule as `IntegrationDesiredStateStatus.derive`;
    * a test holds the two to each other.
    */
  val status: Fragment = fr"""case
      when not o.is_active then 'UNAVAILABLE'
      when la.status in ('QUEUED', 'RUNNING') then 'APPLYING'
      when la.status in ('SUCCEEDED', 'UNKNOWN') and o.last_seen_at <= la.finished_at then 'WAITING_REFRESH'
      when la.status = 'FAILED' and o.last_seen_at <= la.finished_at then 'REMEDIATION_FAILED'
      when coalesce((o.summary ->> 'isDisabled')::boolean, false) = (d.desired_state = 'DISABLED') then 'COMPLIANT'
      else 'DRIFTED' end"""

  private[postgres] final case class ViewRow(id: Option[UUID], state: Option[String], version: Option[Long],
    status: Option[String], lastActionExecutionId: Option[UUID], updatedAt: Option[Instant]) {
    def toView: Option[DesiredStateView] = for {
      i <- id
      s <- state.flatMap(IntegrationDesiredNodeState.fromCode)
      v <- version
      st <- status.flatMap(IntegrationDesiredStateStatus.fromCode)
      u <- updatedAt
    } yield DesiredStateView(i, s, v, st, lastActionExecutionId, u)
  }

  /** Columns of a desired-state view for a query that has `d`, `o` and `la` in scope. */
  val viewColumns: Fragment = fr"d.id, d.desired_state, d.version," ++ status ++
    fr", d.last_action_execution_id, d.updated_at"

  /** The joins that bring `d` and `la` next to an inventory object `o`. */
  val viewJoins: Fragment = fr"""
    left join integration_desired_state d on d.inventory_object_id = o.id and d.integration_id = o.integration_id
      and d.organization_id = o.organization_id
    left join integration_action_execution la on la.id = d.last_action_execution_id
      and la.organization_id = d.organization_id"""
}

final class PostgresIntegrationDesiredStateRepository extends IntegrationDesiredStateRepository[ConnectionIO] {
  import IntegrationDesiredStateSql._

  private type Row = (UUID, UUID, UUID, UUID, String, Long, UUID, Instant, Instant, Option[Instant], Option[UUID])

  override def find(organizationId: UUID, integrationId: UUID,
    inventoryObjectId: UUID): ConnectionIO[Option[IntegrationDesiredState]] =
    sql"""select id, organization_id, integration_id, inventory_object_id, desired_state, version, set_by_user_id,
            created_at, updated_at, last_attempt_observation_at, last_action_execution_id
          from integration_desired_state
          where organization_id = $organizationId and integration_id = $integrationId
            and inventory_object_id = $inventoryObjectId for update"""
      .query[Row].option.flatMap(_.traverse { case (id, org, integration, obj, state, version, user, created, updated,
        attempt, action) => IntegrationDesiredNodeState.fromCode(state).toRight(
          new IllegalStateException("Invalid desired state row")).map(IntegrationDesiredState(id, org, integration, obj, _,
          version, user, created, updated, attempt, action)).liftTo[ConnectionIO] })

  override def save(value: IntegrationDesiredState, at: Instant): ConnectionIO[Unit] =
    // A claim in progress is dropped with the old version: whoever holds it can no longer write.
    sql"""insert into integration_desired_state as d (id, organization_id, integration_id, inventory_object_id,
            desired_state, version, set_by_user_id, created_at, updated_at, next_reconcile_at,
            last_attempt_observation_at, last_action_execution_id)
          values (${value.id}, ${value.organizationId}, ${value.integrationId}, ${value.inventoryObjectId},
            ${value.state.code}, ${value.version}, ${value.setByUserId}, ${value.createdAt}, ${value.updatedAt}, $at,
            ${value.lastAttemptObservationAt}, ${value.lastActionExecutionId})
          on conflict (organization_id, integration_id, inventory_object_id) do update set
            desired_state = excluded.desired_state, version = excluded.version,
            set_by_user_id = excluded.set_by_user_id, updated_at = excluded.updated_at,
            next_reconcile_at = excluded.next_reconcile_at,
            last_attempt_observation_at = excluded.last_attempt_observation_at,
            last_action_execution_id = excluded.last_action_execution_id,
            claimed_by = null, claim_token = null, claim_until = null""".update.run.flatMap {
      case 1 => ().pure[ConnectionIO]
      case _ => new IllegalStateException("Desired state was not written").raiseError[ConnectionIO, Unit]
    }

  override def delete(organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID): ConnectionIO[Int] =
    sql"""delete from integration_desired_state where organization_id = $organizationId
            and integration_id = $integrationId and inventory_object_id = $inventoryObjectId""".update.run

  override def deleteAll(organizationId: UUID, integrationId: UUID): ConnectionIO[Int] =
    sql"""delete from integration_desired_state where organization_id = $organizationId
            and integration_id = $integrationId""".update.run

  override def activeActionExists(organizationId: UUID, integrationId: UUID,
    inventoryObjectId: Option[UUID]): ConnectionIO[Boolean] =
    (fr"""select exists(select 1 from integration_action_execution a
            where a.organization_id = $organizationId and a.integration_id = $integrationId
              and a.status in ('QUEUED', 'RUNNING')""" ++ (inventoryObjectId match {
      case Some(objectId) => fr"and a.inventory_object_id = $objectId"
      case None => fr"""and exists (select 1 from integration_desired_state d
        where d.organization_id = a.organization_id and d.integration_id = a.integration_id
          and d.inventory_object_id = a.inventory_object_id)"""
    }) ++ fr")").query[Boolean].unique

  override def view(organizationId: UUID, integrationId: UUID,
    inventoryObjectId: UUID): ConnectionIO[Option[DesiredStateView]] =
    (fr"select" ++ viewColumns ++ fr"from integration_inventory_object o" ++ viewJoins ++
      fr"""where o.organization_id = $organizationId and o.integration_id = $integrationId
             and o.id = $inventoryObjectId""").query[ViewRow].option.map(_.flatMap(_.toView))

  override def nudge(organizationId: UUID, integrationId: UUID, at: Instant): ConnectionIO[Int] =
    sql"""update integration_desired_state set next_reconcile_at = $at
          where organization_id = $organizationId and integration_id = $integrationId""".update.run

  override def claim(owner: UUID, token: UUID, now: Instant, until: Instant,
    limit: Int): ConnectionIO[List[DesiredStateCandidate]] =
    sql"""with due as (
            select d.id from integration_desired_state d
            join integration i on i.id = d.integration_id and i.organization_id = d.organization_id
            where i.enabled and i.deleted_at is null and i.management_mode = 'MANAGED_SELECTED'
              and d.next_reconcile_at <= $now
              and (d.claim_until is null or d.claim_until <= $now)
            order by d.next_reconcile_at, d.id
            for share of i for update of d skip locked
            limit $limit),
          claimed as (
            update integration_desired_state d
            set claimed_by = $owner, claim_token = $token, claim_until = $until
            from due where d.id = due.id
            returning d.id, d.organization_id, d.integration_id, d.inventory_object_id, d.desired_state, d.version,
              d.last_attempt_observation_at, d.last_action_execution_id)
          select c.id, c.organization_id, c.integration_id, c.inventory_object_id, c.desired_state, c.version,
            c.last_attempt_observation_at, o.is_active, coalesce((o.summary ->> 'isDisabled')::boolean, false),
            o.last_seen_at,
            exists (select 1 from integration_action_execution a where a.organization_id = c.organization_id
              and a.integration_id = c.integration_id and a.inventory_object_id = c.inventory_object_id
              and a.status in ('QUEUED', 'RUNNING')),
            la.status, la.finished_at,
            (select max(u.finished_at) from integration_action_execution u where u.organization_id = c.organization_id
              and u.integration_id = c.integration_id and u.inventory_object_id = c.inventory_object_id
              and u.status = 'UNKNOWN')
          from claimed c
          join integration_inventory_object o on o.id = c.inventory_object_id and o.integration_id = c.integration_id
            and o.organization_id = c.organization_id
          left join integration_action_execution la on la.id = c.last_action_execution_id
            and la.organization_id = c.organization_id"""
      .query[(UUID, UUID, UUID, UUID, String, Long, Option[Instant], Boolean, Boolean, Instant, Boolean,
        Option[String], Option[Instant], Option[Instant])].to[List].flatMap(_.traverse {
        case (id, org, integration, obj, state, version, attempt, active, disabled, seen, activeAction, lastStatus,
          lastFinished, unknown) =>
          IntegrationDesiredNodeState.fromCode(state).toRight(new IllegalStateException("Invalid desired state row"))
            .map(DesiredStateCandidate(id, org, integration, obj, _, version, attempt, active, disabled, seen,
              activeAction, lastStatus.flatMap(IntegrationActionStatus.fromCode).map(_ -> lastFinished), unknown))
            .liftTo[ConnectionIO]
      })

  override def createActions(token: UUID, now: Instant,
    intents: List[DesiredStateIntent]): ConnectionIO[List[CreatedDesiredAction]] =
    if (intents.isEmpty) List.empty[CreatedDesiredAction].pure[ConnectionIO]
    else {
      val ids = intents.map(_.desiredStateId).toArray
      val versions = intents.map(_.version).toArray
      val actions = intents.map(_.action.code).toArray
      val observed = intents.map(_.observedAt.toString).toArray
      for {
        // The same first lock as every intent change, action request, edit and snapshot, so none of
        // them can interleave with this batch. Admission triggers take this same exclusive lock;
        // acquiring it before desired/inventory rows avoids a shared-to-exclusive upgrade deadlock.
        _ <- sql"""select i.id from integration i
                   where i.deleted_at is null
                     and i.id in (select d.integration_id from integration_desired_state d where d.id = any($ids))
                   order by i.id for update""".query[UUID].to[List]
        created <- sql"""with input as (
              select * from unnest($ids::uuid[], $versions::bigint[], $actions::text[], $observed::timestamptz[])
                as c(id, version, action, observed_at)),
            eligible as (
              select d.id, d.organization_id, d.integration_id, d.inventory_object_id, d.version, d.set_by_user_id,
                c.action, o.external_id, o.display_name, o.last_seen_at
              from input c
              -- Fencing: this claim, this version of the intent.
              join integration_desired_state d on d.id = c.id and d.claim_token = $token and d.version = c.version
              join integration i on i.id = d.integration_id and i.organization_id = d.organization_id
                and i.enabled and i.deleted_at is null and i.management_mode = 'MANAGED_SELECTED'
              -- The observation the decision was made on, and not one it was already attempted on.
              join integration_inventory_object o on o.id = d.inventory_object_id
                and o.integration_id = d.integration_id and o.organization_id = d.organization_id
                and o.is_active and o.last_seen_at = c.observed_at
              where (d.last_attempt_observation_at is null or d.last_attempt_observation_at < o.last_seen_at)
                and not exists (select 1 from integration_action_execution a
                  where a.organization_id = d.organization_id and a.integration_id = d.integration_id
                    and a.inventory_object_id = d.inventory_object_id and a.status in ('QUEUED', 'RUNNING'))
              for update of d, o),
            created as (
              insert into integration_action_execution (id, organization_id, integration_id, inventory_object_id,
                request_id, action_code, external_id_snapshot, display_name_snapshot, requested_by_user_id, status,
                created_at, updated_at, source, desired_state_id_snapshot, desired_state_version_snapshot)
              select gen_random_uuid(), e.organization_id, e.integration_id, e.inventory_object_id, gen_random_uuid(),
                e.action, e.external_id, e.display_name, e.set_by_user_id, 'QUEUED', $now, $now, 'DESIRED_STATE',
                e.id, e.version
              from eligible e
              on conflict do nothing
              returning id, desired_state_id_snapshot, desired_state_version_snapshot, organization_id,
                integration_id, inventory_object_id, action_code),
            marked as (
              update integration_desired_state d
              set last_attempt_observation_at = e.last_seen_at, last_action_execution_id = c.id
              from created c join eligible e on e.id = c.desired_state_id_snapshot
              where d.id = c.desired_state_id_snapshot
              returning d.id)
            select c.id, c.desired_state_id_snapshot, c.desired_state_version_snapshot, c.organization_id,
              c.integration_id, c.inventory_object_id, c.action_code
            from created c where exists (select 1 from marked m where m.id = c.desired_state_id_snapshot)"""
          .query[(UUID, UUID, Long, UUID, UUID, UUID, String)].to[List]
        result <- created.traverse { case (id, desiredId, version, org, integration, obj, action) =>
          IntegrationActionCode.fromCode(action).toRight(new IllegalStateException("Invalid action code"))
            .map(CreatedDesiredAction(id, desiredId, version, org, integration, obj, _)).liftTo[ConnectionIO]
        }
      } yield result
    }

  override def release(token: UUID, claimedAt: Instant, nextReconcileAt: Instant): ConnectionIO[Int] =
    // An intent nudged after it was claimed (a newer observation arrived) stays due.
    sql"""update integration_desired_state
          set claimed_by = null, claim_token = null, claim_until = null,
            next_reconcile_at = case when next_reconcile_at > $claimedAt then next_reconcile_at else $nextReconcileAt end
          where claim_token = $token""".update.run
}
