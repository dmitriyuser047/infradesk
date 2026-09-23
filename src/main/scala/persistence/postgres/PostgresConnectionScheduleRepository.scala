package ru.bitec.app.ops
package persistence.postgres

import application.port.ConnectionScheduleRepository
import application.scheduler.ClaimedConnectionSchedule
import domain.connection.ConnectionSchedule

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresConnectionScheduleRepository extends ConnectionScheduleRepository[ConnectionIO] {

  override def save(schedule: ConnectionSchedule): ConnectionIO[Unit] =
    sql"""insert into connection_schedule (organization_id, connection_id, enabled, interval_seconds, next_run_at, consecutive_failures)
           values (${schedule.organizationId}, ${schedule.connectionId}, ${schedule.enabled}, ${schedule.intervalSeconds}, ${schedule.nextRunAt}, ${schedule.consecutiveFailures})
           on conflict (organization_id, connection_id) do update set
             enabled = excluded.enabled, interval_seconds = excluded.interval_seconds,
             next_run_at = excluded.next_run_at, consecutive_failures = excluded.consecutive_failures"""
      .update.run.void

  override def findByConnection(
    organizationId: UUID,
    connectionId: UUID
  ): ConnectionIO[Option[ConnectionSchedule]] =
    sql"""
      select
        organization_id,
        connection_id,
        enabled,
        interval_seconds,
        next_run_at,
        consecutive_failures
      from connection_schedule
      where organization_id = $organizationId
        and connection_id = $connectionId
    """
      .query[ConnectionSchedule]
      .option

  override def findDue(now: Instant, limit: Int): ConnectionIO[List[ConnectionSchedule]] =
    sql"""
      select
        organization_id,
        connection_id,
        enabled,
        interval_seconds,
        next_run_at,
        consecutive_failures
      from connection_schedule
      where enabled
        and next_run_at <= $now
      order by next_run_at
      limit $limit
    """
      .query[ConnectionSchedule]
      .to[List]

  override def updateAfterRun(
                               organizationId: UUID,
                               connectionId: UUID,
                               nextRunAt: Instant,
                               consecutiveFailures: Long
                             ): ConnectionIO[Unit] =
    sql"""
      update connection_schedule
      set next_run_at = $nextRunAt,
          consecutive_failures = $consecutiveFailures
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

  override def claimDue(claimedBy: UUID, limit: Int, leaseSeconds: Long): ConnectionIO[List[ClaimedConnectionSchedule]] =
    sql"""
      with due as (
        select organization_id, connection_id
        from connection_schedule
        where enabled
          and next_run_at <= current_timestamp
          and (claimed_until is null or claimed_until <= current_timestamp)
        order by next_run_at, organization_id, connection_id
        for update skip locked
        limit $limit
      )
      update connection_schedule cs
      set claimed_by = $claimedBy,
          claimed_until = current_timestamp + ($leaseSeconds * interval '1 second')
      from due
      where cs.organization_id = due.organization_id
        and cs.connection_id = due.connection_id
      returning cs.organization_id, cs.connection_id, cs.enabled, cs.interval_seconds,
                cs.next_run_at, cs.consecutive_failures, cs.claimed_by, cs.claimed_until
    """
      .query[(UUID, UUID, Boolean, Long, Instant, Long, UUID, Instant)]
      .to[List]
      .map(_.map { case (organizationId, connectionId, enabled, intervalSeconds, nextRunAt, failures, owner, until) =>
        ClaimedConnectionSchedule(ConnectionSchedule(organizationId, connectionId, enabled, intervalSeconds, nextRunAt, failures), owner, until)
      })

  override def completeClaimedRun(
                                   organizationId: UUID,
                                   connectionId: UUID,
                                   claimedBy: UUID,
                                   nextRunAt: Instant,
                                   consecutiveFailures: Long
                                 ): ConnectionIO[Boolean] =
    sql"""
      update connection_schedule
      set next_run_at = $nextRunAt,
          consecutive_failures = $consecutiveFailures,
          claimed_by = null,
          claimed_until = null
      where organization_id = $organizationId
        and connection_id = $connectionId
        and claimed_by = $claimedBy
    """.update.run.map(_ == 1)
}
