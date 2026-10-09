package ru.bitec.app.ops
package persistence.postgres

import application.port.{ConnectionDeletion, ConnectionLifecycleRepository}
import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Deletion runs in a fixed number of statements, however many servers the connection observed. */
final class PostgresConnectionLifecycleRepository extends ConnectionLifecycleRepository[ConnectionIO] {

  override def lockOrganization(organizationId: UUID): ConnectionIO[Unit] =
    sql"select id from organization where id = $organizationId for update".query[UUID].option.void

  override def delete(org: UUID, id: UUID, now: Instant): ConnectionIO[ConnectionDeletion] =
    sql"""select secret_ref from connection
      where organization_id = $org and id = $id and deleted_at is null
      for update""".query[Option[String]].option.flatMap {
      case None => (ConnectionDeletion.NotFound: ConnectionDeletion).pure[ConnectionIO]
      case Some(secretRef) => busy(org, id).flatMap {
        case true => (ConnectionDeletion.Busy: ConnectionDeletion).pure[ConnectionIO]
        case false => for {
          _ <- sql"""update connection set is_active = false, deleted_at = $now, updated_at = $now
            where organization_id = $org and id = $id""".update.run
          _ <- sql"""update connection_schedule set enabled = false
            where organization_id = $org and connection_id = $id""".update.run
          deactivated <- deactivateObservedOnlyHere(org, id, now)
        } yield ConnectionDeletion.Deleted(secretRef, deactivated)
      }
    }

  /**
   * Work that would lose its target: a running sync or operation, a queued or running deployment
   * or provisioning run, a rollout item still to deploy, a queued or running onboarding of one of
   * its servers. Finished runs, including UNKNOWN ones, stay as history and do not block.
   */
  private def busy(org: UUID, id: UUID): ConnectionIO[Boolean] =
    sql"""select
        exists (select 1 from sync_session
          where organization_id = $org and connection_id = $id and status = 'RUNNING')
        or exists (select 1 from operation_execution
          where organization_id = $org and target_connection_id = $id and status = 'RUNNING')
        or exists (select 1 from configuration_deployment
          where organization_id = $org and connection_id = $id and state in ('QUEUED','RUNNING'))
        or exists (select 1 from provisioning_run
          where organization_id = $org and connection_id = $id and status in ('QUEUED','RUNNING'))
        or exists (select 1 from configuration_rollout_item i
          join configuration_rollout r on r.id = i.rollout_id and r.organization_id = i.organization_id
          where i.organization_id = $org and i.connection_id = $id and i.state in ('PENDING','DEPLOYING')
            and r.state in ('QUEUED','RUNNING','PAUSED','ROLLING_BACK'))
        or exists (select 1 from remnawave_node_onboarding o
          join external_ref er on er.organization_id = o.organization_id and er.resource_id = o.resource_id
          where o.organization_id = $org and er.connection_id = $id and o.state in ('QUEUED','RUNNING'))
    """.query[Boolean].unique

  /**
   * Servers this connection observed and no other live connection does, and everything beneath
   * them: a server another connection still sees keeps its place.
   */
  private def deactivateObservedOnlyHere(org: UUID, id: UUID, now: Instant): ConnectionIO[Int] =
    sql"""with recursive seen_elsewhere as (
        select er.resource_id from external_ref er
        join connection c on c.id = er.connection_id and c.organization_id = er.organization_id
        where er.organization_id = $org and er.connection_id <> $id and c.deleted_at is null
      ), doomed as (
        select r.id from resource r
        where r.organization_id = $org and r.is_active
          and exists (select 1 from external_ref er
            where er.organization_id = r.organization_id and er.resource_id = r.id and er.connection_id = $id)
          and r.id not in (select resource_id from seen_elsewhere)
        union
        select child.id from resource child
        join doomed parent on child.parent_resource_id = parent.id
        where child.organization_id = $org and child.is_active
          and child.id not in (select resource_id from seen_elsewhere)
      )
      update resource set is_active = false, updated_at = $now
      where organization_id = $org and id in (select id from doomed)""".update.run
}
