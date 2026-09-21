package ru.bitec.app.ops
package persistence.postgres

import application.port.ConnectionScheduleRepository
import domain.connection.ConnectionSchedule

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresConnectionScheduleRepository extends ConnectionScheduleRepository[ConnectionIO] {

  override def findDue(now: Instant, limit: Int): ConnectionIO[List[ConnectionSchedule]] =
    sql"""
      select
        organization_id,
        connection_id,
        enabled,
        interval_seconds,
        next_run_at
      from connection_schedule
      where enabled
        and next_run_at <= $now
      order by next_run_at
      limit $limit
    """
      .query[ConnectionSchedule]
      .to[List]

  override def scheduleNext(
                             organizationId: UUID,
                             connectionId: UUID,
                             nextRunAt: Instant
                           ): ConnectionIO[Unit] =
    sql"""
      update connection_schedule
      set next_run_at = $nextRunAt
      where organization_id = $organizationId
        and connection_id = $connectionId
    """
      .update
      .run
      .flatMap {
        case 1 =>
          ().pure[ConnectionIO]
        case rows =>
          new IllegalStateException(
            s"Expected to schedule 1 connection row, affected: $rows"
          ).raiseError[ConnectionIO, Unit]
      }
}
