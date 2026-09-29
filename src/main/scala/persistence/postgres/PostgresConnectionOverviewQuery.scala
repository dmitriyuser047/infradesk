package ru.bitec.app.ops
package persistence.postgres

import application.connection.{ConnectionOverview, ConnectionOverviewQuery}
import application.port.ConnectionRepository
import domain.connection.ConnectionSchedule
import PostgresSyncSessionRepository.SyncSessionRow

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

/** The connection list in three statements whatever its length: the connections, the latest
  * synchronization of each (one index lookup per connection inside a single LATERAL query, never
  * a scan of the whole history), and their schedules.
  */
final class PostgresConnectionOverviewQuery(connections: ConnectionRepository[ConnectionIO])
  extends ConnectionOverviewQuery[ConnectionIO] {

  override def listByOrganization(organizationId: UUID): ConnectionIO[List[ConnectionOverview]] =
    connections.findByOrganization(organizationId).flatMap { list =>
      if (list.isEmpty) List.empty[ConnectionOverview].pure[ConnectionIO]
      else {
        val ids = list.map(_.id).toArray
        (latestSessions(organizationId, ids), schedules(organizationId, ids)).mapN { (sessions, schedules) =>
          list.map(connection => ConnectionOverview(connection, schedules.get(connection.id), sessions.get(connection.id)))
        }
      }
    }

  private def latestSessions(organizationId: UUID, ids: Array[UUID]) =
    sql"""
      select s.id, s.organization_id, s.connection_id, s.started_at, s.recover_after_at, s.finished_at,
             s.status, s.error_code, s.error_message
      from unnest($ids::uuid[]) as c(id)
      cross join lateral (
        select * from sync_session
        where organization_id = $organizationId and connection_id = c.id
        order by started_at desc, id desc
        limit 1
      ) s
    """.query[SyncSessionRow].to[List]
      .flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
      .map(_.map(session => session.connectionId -> session).toMap)

  private def schedules(organizationId: UUID, ids: Array[UUID]) =
    sql"""
      select organization_id, connection_id, enabled, interval_seconds, next_run_at, consecutive_failures
      from connection_schedule
      where organization_id = $organizationId and connection_id = any($ids)
    """.query[ConnectionSchedule].to[List]
      .map(_.map(schedule => schedule.connectionId -> schedule).toMap)
}
