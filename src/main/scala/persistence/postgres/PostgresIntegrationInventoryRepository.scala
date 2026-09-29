package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.integration._
import io.circe.parser.parse
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import serialization.integration.IntegrationSummaryJson

import java.time.Instant
import java.util.UUID

private[postgres] object IntegrationInventoryRows {
  final case class SessionRow(id: UUID, organizationId: UUID, integrationId: UUID, trigger: String,
    requestedByUserId: Option[UUID], startedAt: Instant, recoverAfterAt: Instant, finishedAt: Option[Instant],
    status: String, errorCode: Option[String], errorMessage: Option[String], nodes: Option[Int], hosts: Option[Int],
    configProfiles: Option[Int], deactivated: Option[Int]) {
    def toDomain: Either[Throwable, IntegrationSyncSession] = for {
      t <- IntegrationSyncTrigger.fromCode(trigger)
      s <- IntegrationSyncStatus.fromCode(status)
    } yield IntegrationSyncSession(id, organizationId, integrationId, t, requestedByUserId, startedAt, recoverAfterAt,
      finishedAt, s, errorCode, errorMessage,
      (nodes, hosts, configProfiles, deactivated).mapN(IntegrationSyncCounts.apply))
  }

  val sessionColumns: Fragment = fr"""s.id, s.organization_id, s.integration_id, s.trigger, s.requested_by_user_id,
    s.started_at, s.recover_after_at, s.finished_at, s.status, s.error_code, s.error_message, s.nodes_count,
    s.hosts_count, s.config_profiles_count, s.deactivated_count"""

  final case class ObjectRow(id: UUID, organizationId: UUID, integrationId: UUID, objectType: String,
    externalId: String, displayName: String, summary: String, isActive: Boolean, firstSeenAt: Instant,
    lastSeenAt: Instant, lastSeenSyncSessionId: UUID) {
    def toDomain: Either[Throwable, IntegrationInventoryObject] = for {
      t <- IntegrationObjectType.fromCode(objectType)
      json <- parse(summary)
      s <- IntegrationSummaryJson.decode(t, json)
    } yield IntegrationInventoryObject(id, organizationId, integrationId, t, externalId, displayName, s, isActive,
      firstSeenAt, lastSeenAt, lastSeenSyncSessionId)
  }

  val objectColumns: Fragment = fr"""o.id, o.organization_id, o.integration_id, o.object_type, o.external_id,
    o.display_name, o.summary::text, o.is_active, o.first_seen_at, o.last_seen_at, o.last_seen_sync_session_id"""
}

final class PostgresIntegrationSyncSessionRepository extends IntegrationSyncSessionRepository[ConnectionIO] {
  import IntegrationInventoryRows._

  override def recoverStaleAndTryCreate(session: IntegrationSyncSession, at: Instant, errorCode: String,
    errorMessage: String): ConnectionIO[IntegrationSyncClaim] =
    // Each abandoned session is judged by its own deadline; the slot is claimed afterwards, in the
    // same transaction, and the partial unique index decides who gets it.
    (fr"""update integration_sync_session s
          set finished_at = $at, status = 'FAILED', error_code = $errorCode, error_message = $errorMessage
          where s.organization_id = ${session.organizationId} and s.integration_id = ${session.integrationId}
            and s.status = 'RUNNING' and s.recover_after_at < $at
          returning""" ++ sessionColumns).query[SessionRow].to[List]
      .flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
      .flatMap { recovered =>
        sql"""insert into integration_sync_session (id, organization_id, integration_id, trigger,
                requested_by_user_id, started_at, recover_after_at, status)
              values (${session.id}, ${session.organizationId}, ${session.integrationId}, ${session.trigger.code},
                ${session.requestedByUserId}, ${session.startedAt}, ${session.recoverAfterAt}, 'RUNNING')
              on conflict (organization_id, integration_id) where status = 'RUNNING' do nothing"""
          .update.run.map(rows => IntegrationSyncClaim(rows == 1, recovered))
      }

  override def complete(organizationId: UUID, id: UUID, finishedAt: Instant,
    counts: IntegrationSyncCounts): ConnectionIO[Boolean] =
    sql"""update integration_sync_session
          set status = 'COMPLETED', finished_at = $finishedAt, nodes_count = ${counts.nodes},
            hosts_count = ${counts.hosts}, config_profiles_count = ${counts.configProfiles},
            deactivated_count = ${counts.deactivated}
          where organization_id = $organizationId and id = $id and status = 'RUNNING'"""
      .update.run.map(_ == 1)

  override def fail(organizationId: UUID, id: UUID, finishedAt: Instant, errorCode: String,
    errorMessage: String): ConnectionIO[Boolean] =
    sql"""update integration_sync_session
          set status = 'FAILED', finished_at = $finishedAt, error_code = $errorCode, error_message = $errorMessage
          where organization_id = $organizationId and id = $id and status = 'RUNNING'"""
      .update.run.map(_ == 1)

  override def recent(organizationId: UUID, integrationId: UUID, limit: Int): ConnectionIO[List[IntegrationSyncSession]] =
    (fr"select" ++ sessionColumns ++ fr"""from integration_sync_session s
       where s.organization_id = $organizationId and s.integration_id = $integrationId
       order by s.started_at desc, s.id desc limit $limit""").query[SessionRow].to[List]
      .flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[IntegrationSyncSession]] =
    (fr"select" ++ sessionColumns ++ fr"from integration_sync_session s where s.organization_id = $organizationId and s.id = $id")
      .query[SessionRow].option.flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
}

final class PostgresIntegrationInventoryRepository extends IntegrationInventoryRepository[ConnectionIO] {
  import IntegrationInventoryRows._

  override def applySnapshot(organizationId: UUID, integrationId: UUID, sessionId: UUID,
    observation: IntegrationObservation, at: Instant): ConnectionIO[Int] = {
    val objects = observation.objects
    val types = objects.map(_.objectType.code).toArray
    val externalIds = objects.map(_.externalId).toArray
    val names = objects.map(_.displayName).toArray
    val summaries = objects.map(value => IntegrationSummaryJson.encode(value.summary).noSpaces).toArray
    val complete = observation.completeObjectTypes.toList.map(_.code).toArray
    // One statement writes every observed object whatever their number. An unchanged object keeps
    // its update time; only when it was last seen moves.
    val upsert =
      if (objects.isEmpty) 0.pure[ConnectionIO]
      else sql"""insert into integration_inventory_object as o (id, organization_id, integration_id, object_type,
                  external_id, display_name, summary_version, summary, is_active, first_seen_at, last_seen_at,
                  last_seen_sync_session_id, created_at, updated_at)
                select gen_random_uuid(), $organizationId, $integrationId, i.object_type, i.external_id,
                  i.display_name, ${IntegrationSummaryJson.Version}, i.summary::jsonb, true, $at, $at, $sessionId, $at, $at
                from unnest($types::text[], $externalIds::text[], $names::text[], $summaries::text[])
                  as i(object_type, external_id, display_name, summary)
                on conflict (organization_id, integration_id, object_type, external_id) do update set
                  display_name = excluded.display_name,
                  summary_version = excluded.summary_version,
                  summary = excluded.summary,
                  is_active = true,
                  last_seen_at = excluded.last_seen_at,
                  last_seen_sync_session_id = excluded.last_seen_sync_session_id,
                  updated_at = case
                    when o.display_name is distinct from excluded.display_name
                      or o.summary is distinct from excluded.summary
                      or o.summary_version is distinct from excluded.summary_version
                      or not o.is_active then excluded.updated_at
                    else o.updated_at end""".update.run
    // Whatever a complete listing no longer contains is gone from the provider: inactive, never deleted.
    val deactivate =
      sql"""update integration_inventory_object
            set is_active = false, updated_at = $at
            where organization_id = $organizationId and integration_id = $integrationId and is_active
              and object_type = any($complete::text[]) and last_seen_sync_session_id <> $sessionId""".update.run
    upsert *> deactivate
  }

  override def findObject(organizationId: UUID, integrationId: UUID, objectId: UUID,
    forUpdate: Boolean): ConnectionIO[Option[IntegrationInventoryObject]] =
    (fr"select" ++ objectColumns ++ fr"""from integration_inventory_object o
       where o.organization_id = $organizationId and o.integration_id = $integrationId and o.id = $objectId""" ++
      (if (forUpdate) fr"for update" else Fragment.empty)).query[ObjectRow].option
      .flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
}

final class PostgresIntegrationSyncStateRepository extends IntegrationSyncStateRepository[ConnectionIO] {
  override def ensure(organizationId: UUID, integrationId: UUID, nextRunAt: Instant): ConnectionIO[Unit] =
    sql"""insert into integration_sync_state (organization_id, integration_id, next_run_at, updated_at)
          values ($organizationId, $integrationId, $nextRunAt, $nextRunAt)
          on conflict (organization_id, integration_id) do nothing""".update.run.void

  override def scheduleAt(organizationId: UUID, integrationId: UUID, nextRunAt: Instant): ConnectionIO[Unit] =
    sql"""insert into integration_sync_state (organization_id, integration_id, next_run_at, updated_at)
          values ($organizationId, $integrationId, $nextRunAt, $nextRunAt)
          on conflict (organization_id, integration_id) do update set
            next_run_at = excluded.next_run_at, consecutive_failures = 0, updated_at = excluded.updated_at"""
      .update.run.void

  override def claimDue(owner: UUID, limit: Int, leaseSeconds: Long,
    now: Instant): ConnectionIO[List[ClaimedIntegrationSync]] =
    sql"""with due as (
            select s.organization_id, s.integration_id
            from integration_sync_state s
            join integration i on i.id = s.integration_id and i.organization_id = s.organization_id
            where i.enabled and s.next_run_at <= $now and (s.claim_until is null or s.claim_until <= $now)
            order by s.next_run_at, s.organization_id, s.integration_id
            for update of s skip locked
            limit $limit)
          update integration_sync_state s
          set claim_token = gen_random_uuid(), claimed_by = $owner,
            claim_until = $now + ($leaseSeconds * interval '1 second'), updated_at = $now
          from due
          where s.organization_id = due.organization_id and s.integration_id = due.integration_id
          returning s.organization_id, s.integration_id, s.consecutive_failures, s.claim_token"""
      .query[(UUID, UUID, Long, UUID)].to[List]
      .map(_.map((ClaimedIntegrationSync.apply _).tupled))

  override def completeClaimedRun(claim: ClaimedIntegrationSync, nextRunAt: Instant, consecutiveFailures: Long,
    now: Instant): ConnectionIO[Boolean] =
    sql"""update integration_sync_state
          set next_run_at = least($nextRunAt, coalesce(action_nudge_at, $nextRunAt)),
            action_nudge_at = null, consecutive_failures = $consecutiveFailures,
            claim_token = null, claimed_by = null, claim_until = null, updated_at = $now
          where organization_id = ${claim.organizationId} and integration_id = ${claim.integrationId}
            and claim_token = ${claim.token}""".update.run.map(_ == 1)
}

final class PostgresIntegrationBindingRepository extends IntegrationBindingRepository[ConnectionIO] {
  override def find(organizationId: UUID, inventoryObjectId: UUID): ConnectionIO[Option[IntegrationResourceBinding]] =
    sql"""select id, organization_id, integration_id, inventory_object_id, resource_id, created_by_user_id,
            created_at, updated_at
          from integration_resource_binding
          where organization_id = $organizationId and inventory_object_id = $inventoryObjectId"""
      .query[IntegrationResourceBinding].option

  override def resource(organizationId: UUID, resourceId: UUID): ConnectionIO[Option[BindableResource]] =
    sql"""select r.id, rt.code, r.is_active
          from resource r join resource_type rt on rt.id = r.resource_type_id
          where r.organization_id = $organizationId and r.id = $resourceId"""
      .query[BindableResource].option

  override def upsert(binding: IntegrationResourceBinding): ConnectionIO[Unit] =
    sql"""insert into integration_resource_binding as b (id, organization_id, integration_id, inventory_object_id,
            resource_id, created_by_user_id, created_at, updated_at)
          values (${binding.id}, ${binding.organizationId}, ${binding.integrationId}, ${binding.inventoryObjectId},
            ${binding.resourceId}, ${binding.createdByUserId}, ${binding.createdAt}, ${binding.updatedAt})
          on conflict (inventory_object_id) do update set resource_id = excluded.resource_id,
            created_by_user_id = excluded.created_by_user_id, updated_at = excluded.updated_at
          where b.organization_id = excluded.organization_id""".update.run.flatMap {
      case 1 => ().pure[ConnectionIO]
      case _ => new IllegalStateException("Integration binding was not written").raiseError[ConnectionIO, Unit]
    }

  override def delete(organizationId: UUID, inventoryObjectId: UUID): ConnectionIO[Int] =
    sql"""delete from integration_resource_binding
          where organization_id = $organizationId and inventory_object_id = $inventoryObjectId""".update.run
}

final class PostgresIntegrationInventoryQuery extends IntegrationInventoryQuery[ConnectionIO] {
  import IntegrationInventoryRows._
  import PostgresIntegrationInventoryQuery._

  override def list(organizationId: UUID, integrationId: UUID, objectType: IntegrationObjectType,
    filter: InventoryFilter): ConnectionIO[InventoryPage[InventoryItem]] = {
    val conditions = List(
      Some(fr"o.organization_id = $organizationId and o.integration_id = $integrationId and o.object_type = ${objectType.code}"),
      filter.active.map(value => fr"o.is_active = $value"),
      filter.search.map(likePattern).map(pattern =>
        fr"""(o.display_name ilike $pattern escape '\' or o.external_id ilike $pattern escape '\'
              or o.summary ->> 'address' ilike $pattern escape '\')"""),
      filter.state.map(value => fr"o.summary ->> 'state' = ${value.code}")
    ).flatten
    val where = fr"where" ++ conditions.reduce(_ ++ fr"and" ++ _)
    val items = (fr"select" ++ objectColumns ++ fr""", r.id, r.code, r.name, e.id, e.name, p.id, p.name
       from integration_inventory_object o
       left join integration_resource_binding b on b.inventory_object_id = o.id and b.organization_id = o.organization_id
       left join resource r on r.id = b.resource_id and r.organization_id = b.organization_id
       left join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
       left join project p on p.id = e.project_id and p.organization_id = e.organization_id""" ++ where ++
      fr"order by o.display_name, o.id limit ${filter.limit} offset ${filter.offset}")
      .query[(ObjectRow, BoundRow)].to[List]
      .flatMap(_.traverse { case (row, bound) => row.toDomain.liftTo[ConnectionIO].map(InventoryItem(_, bound.toView)) })
    val total = (fr"select count(*) from integration_inventory_object o" ++ where).query[Long].unique
    (items, total).mapN(InventoryPage(_, _))
  }

  override def overviews(organizationId: UUID): ConnectionIO[Map[UUID, IntegrationOverview]] =
    (fr"""select i.id,""" ++ sessionColumns ++ fr""",
           (select max(c.finished_at) from integration_sync_session c
              where c.organization_id = i.organization_id and c.integration_id = i.id and c.status = 'COMPLETED'),
           st.next_run_at,
           coalesce(n.nodes_active, 0), coalesce(n.nodes_inactive, 0), coalesce(n.hosts_active, 0),
           coalesce(n.hosts_inactive, 0), coalesce(n.profiles_active, 0), coalesce(n.profiles_inactive, 0)
         from integration i
         left join integration_sync_state st on st.organization_id = i.organization_id and st.integration_id = i.id
         left join lateral (
           select * from integration_sync_session x
           where x.organization_id = i.organization_id and x.integration_id = i.id
           order by x.started_at desc, x.id desc limit 1) s on true
         left join lateral (
           select count(*) filter (where o.object_type = 'NODE' and o.is_active) as nodes_active,
                  count(*) filter (where o.object_type = 'NODE' and not o.is_active) as nodes_inactive,
                  count(*) filter (where o.object_type = 'HOST' and o.is_active) as hosts_active,
                  count(*) filter (where o.object_type = 'HOST' and not o.is_active) as hosts_inactive,
                  count(*) filter (where o.object_type = 'CONFIG_PROFILE' and o.is_active) as profiles_active,
                  count(*) filter (where o.object_type = 'CONFIG_PROFILE' and not o.is_active) as profiles_inactive
           from integration_inventory_object o
           where o.organization_id = i.organization_id and o.integration_id = i.id) n on true
         where i.organization_id = $organizationId""")
      .query[OverviewRow].to[List]
      .flatMap(_.traverse(row => row.session.toSession.traverse(_.toDomain).liftTo[ConnectionIO].map { last =>
        row.integrationId -> IntegrationOverview(row.integrationId, last, row.lastSuccessfulSyncAt, row.nextRunAt,
          IntegrationInventorySummary(InventoryTypeCounts(row.counts.nodesActive, row.counts.nodesInactive),
            InventoryTypeCounts(row.counts.hostsActive, row.counts.hostsInactive),
            InventoryTypeCounts(row.counts.profilesActive, row.counts.profilesInactive)))
      }))
      .map(_.toMap)

  override def bindingCandidates(organizationId: UUID, search: Option[String],
    limit: Int): ConnectionIO[List[BindingCandidate]] =
    (fr"""select r.id, r.code, r.name, e.id, e.name, p.id, p.name
          from resource r
          join resource_type rt on rt.id = r.resource_type_id
          join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
          join project p on p.id = e.project_id and p.organization_id = e.organization_id
          where r.organization_id = $organizationId and r.is_active and rt.code = 'NODE'""" ++
      search.map(likePattern).fold(Fragment.empty)(pattern =>
        fr"and (r.name ilike $pattern escape '\' or r.code ilike $pattern escape '\')") ++
      fr"order by r.name, r.id limit $limit").query[BindingCandidate].to[List]

  override def resourceContexts(organizationId: UUID, resourceId: UUID): ConnectionIO[List[ResourceIntegrationContext]] =
    (fr"select i.id, i.name, i.provider_type," ++ objectColumns ++ fr"""
       from integration_resource_binding b
       join integration_inventory_object o on o.id = b.inventory_object_id and o.organization_id = b.organization_id
       join integration i on i.id = b.integration_id and i.organization_id = b.organization_id
       where b.organization_id = $organizationId and b.resource_id = $resourceId
       order by i.name, i.id, o.display_name, o.id""").query[(UUID, String, String, ObjectRow)].to[List]
      .flatMap(_.traverse { case (id, name, provider, row) =>
        (for { p <- IntegrationProviderType.fromCode(provider); o <- row.toDomain }
          yield ResourceIntegrationContext(id, name, p, o)).liftTo[ConnectionIO]
      })
}

object PostgresIntegrationInventoryQuery {
  import IntegrationInventoryRows.SessionRow

  /** A case-insensitive substring pattern; the user's `%`, `_` and `\` match themselves. */
  private[postgres] def likePattern(value: String): String =
    "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

  private[postgres] final case class BoundRow(id: Option[UUID], code: Option[String], name: Option[String],
    environmentId: Option[UUID], environmentName: Option[String], projectId: Option[UUID], projectName: Option[String]) {
    def toView: Option[BoundResourceView] =
      (id, code, name, environmentId, environmentName, projectId, projectName).mapN(BoundResourceView.apply)
  }

  private[postgres] final case class OptionalSession(id: Option[UUID], organizationId: Option[UUID],
    integrationId: Option[UUID], trigger: Option[String], requestedByUserId: Option[UUID], startedAt: Option[Instant],
    recoverAfterAt: Option[Instant], finishedAt: Option[Instant], status: Option[String], errorCode: Option[String],
    errorMessage: Option[String], nodes: Option[Int], hosts: Option[Int], configProfiles: Option[Int],
    deactivated: Option[Int]) {
    def toSession: Option[SessionRow] = (id, organizationId, integrationId, trigger, startedAt, recoverAfterAt, status)
      .mapN((i, o, g, t, s, r, st) => SessionRow(i, o, g, t, requestedByUserId, s, r, finishedAt, st, errorCode,
        errorMessage, nodes, hosts, configProfiles, deactivated))
  }

  private[postgres] final case class OverviewCounts(nodesActive: Long, nodesInactive: Long, hostsActive: Long,
    hostsInactive: Long, profilesActive: Long, profilesInactive: Long)

  private[postgres] final case class OverviewRow(integrationId: UUID, session: OptionalSession,
    lastSuccessfulSyncAt: Option[Instant], nextRunAt: Option[Instant], counts: OverviewCounts)
}
