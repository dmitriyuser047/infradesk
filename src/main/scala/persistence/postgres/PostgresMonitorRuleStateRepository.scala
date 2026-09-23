package ru.bitec.app.ops
package persistence.postgres

import application.port.MonitorRuleStateRepository
import domain.monitor.{MonitorRuleState, MonitorRuleStatus}

import cats.syntax.all._
import org.typelevel.doobie.{ConnectionIO, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresMonitorRuleStateRepository extends MonitorRuleStateRepository[ConnectionIO] {

  private final case class MonitorRuleStateRow(
                                                organizationId: UUID,
                                                monitorRuleId: UUID,
                                                status: String,
                                                pendingSince: Option[Instant],
                                                updatedAt: Instant
                                              ) {
    def toDomain: Either[IllegalArgumentException, MonitorRuleState] =
      MonitorRuleStatus.fromCode(status).map { typedStatus =>
        MonitorRuleState(
          organizationId = organizationId,
          monitorRuleId = monitorRuleId,
          status = typedStatus,
          pendingSince = pendingSince,
          updatedAt = updatedAt
        )
      }
  }

  override def findByRuleId(
                             organizationId: UUID,
                             monitorRuleId: UUID
                           ): ConnectionIO[Option[MonitorRuleState]] =
    sql"""
      select
        organization_id,
        monitor_rule_id,
        status,
        pending_since,
        updated_at
      from monitor_rule_state
      where organization_id = $organizationId
        and monitor_rule_id = $monitorRuleId
    """
      .query[MonitorRuleStateRow]
      .option
      .flatMap {
        case Some(row) =>
          row.toDomain.map(state => Option(state)).liftTo[ConnectionIO]
        case None =>
          none[MonitorRuleState].pure[ConnectionIO]
      }

  override def save(state: MonitorRuleState): ConnectionIO[Unit] =
    saveAll(List(state))

  override def saveAll(states: List[MonitorRuleState]): ConnectionIO[Unit] =
    if (states.isEmpty) ().pure[ConnectionIO]
    else Update[(UUID, UUID, String, Option[Instant], Instant)]("""
      insert into monitor_rule_state (
        organization_id,
        monitor_rule_id,
        status,
        pending_since,
        updated_at
      )
      values (?, ?, ?, ?, ?)
      on conflict (organization_id, monitor_rule_id)
      do update set
        status = excluded.status,
        pending_since = excluded.pending_since,
        updated_at = excluded.updated_at
    """).updateMany(states.map(state =>
      (state.organizationId, state.monitorRuleId, state.status.code, state.pendingSince, state.updatedAt)
    )).flatMap { rows =>
      if (rows == states.size) ().pure[ConnectionIO]
      else new IllegalStateException(
        s"Expected to save ${states.size} monitor_rule_state rows, affected: $rows"
      ).raiseError[ConnectionIO, Unit]
    }
}
