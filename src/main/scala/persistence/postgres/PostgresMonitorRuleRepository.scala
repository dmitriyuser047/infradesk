package ru.bitec.app.ops
package persistence.postgres

import application.port.MonitorRuleRepository
import domain.metric.MetricCode
import domain.monitor.{MonitorOperator, MonitorRule}

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresMonitorRuleRepository extends MonitorRuleRepository[ConnectionIO] {

  private final case class MonitorRuleRow(
                                           id: UUID,
                                           organizationId: UUID,
                                           resourceId: UUID,
                                           metricCode: String,
                                           operator: String,
                                           threshold: BigDecimal,
                                           forSeconds: Long,
                                           noDataSeconds: Long,
                                           enabled: Boolean,
                                           createdAt: Instant,
                                           updatedAt: Instant
                                         ) {
    def toDomain: Either[IllegalArgumentException, MonitorRule] =
      for {
        typedMetricCode <- MetricCode.fromCode(metricCode)
        typedOperator <- MonitorOperator.fromCode(operator)
      } yield MonitorRule(
        id = id,
        organizationId = organizationId,
        resourceId = resourceId,
        metricCode = typedMetricCode,
        operator = typedOperator,
        threshold = threshold,
        forSeconds = forSeconds,
        noDataSeconds = noDataSeconds,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt
      )
  }

  override def findById(organizationId: UUID, id: UUID): ConnectionIO[Option[MonitorRule]] =
    sql"""
      select
        id,
        organization_id,
        resource_id,
        metric_code,
        operator,
        threshold,
        for_seconds,
        no_data_seconds,
        enabled,
        created_at,
        updated_at
      from monitor_rule
      where organization_id = $organizationId
        and id = $id
    """
      .query[MonitorRuleRow]
      .option
      .flatMap(toDomainOption)

  override def findEnabledByResource(
                                      organizationId: UUID,
                                      resourceId: UUID
                                    ): ConnectionIO[List[MonitorRule]] =
    sql"""
      select
        id,
        organization_id,
        resource_id,
        metric_code,
        operator,
        threshold,
        for_seconds,
        no_data_seconds,
        enabled,
        created_at,
        updated_at
      from monitor_rule
      where organization_id = $organizationId
        and resource_id = $resourceId
        and enabled
      order by id
    """
      .query[MonitorRuleRow]
      .to[List]
      .flatMap(_.traverse(row => row.toDomain.liftTo[ConnectionIO]))

  override def findByResource(organizationId: UUID, resourceId: UUID): ConnectionIO[List[MonitorRule]] =
    sql"""select id, organization_id, resource_id, metric_code, operator, threshold, for_seconds, no_data_seconds, enabled, created_at, updated_at from monitor_rule where organization_id=$organizationId and resource_id=$resourceId order by created_at asc, id asc""".query[MonitorRuleRow].to[List].flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  override def save(rule: MonitorRule): ConnectionIO[Unit] =
    sql"""
      insert into monitor_rule (
        id,
        organization_id,
        resource_id,
        metric_code,
        operator,
        threshold,
        for_seconds,
        no_data_seconds,
        enabled,
        created_at,
        updated_at
      )
      values (
        ${rule.id},
        ${rule.organizationId},
        ${rule.resourceId},
        ${rule.metricCode.code},
        ${rule.operator.code},
        ${rule.threshold},
        ${rule.forSeconds},
        ${rule.noDataSeconds},
        ${rule.enabled},
        ${rule.createdAt},
        ${rule.updatedAt}
      )
      on conflict (id)
      do update set
        resource_id = excluded.resource_id,
        metric_code = excluded.metric_code,
        operator = excluded.operator,
        threshold = excluded.threshold,
        for_seconds = excluded.for_seconds,
        no_data_seconds = excluded.no_data_seconds,
        enabled = excluded.enabled,
        updated_at = excluded.updated_at
      where monitor_rule.organization_id = excluded.organization_id
    """
      .update
      .run
      .flatMap {
        case 1 =>
          ().pure[ConnectionIO]
        case rows =>
          new IllegalStateException(
            s"Expected to save 1 monitor_rule row, affected: ${rows}"
          ).raiseError[ConnectionIO, Unit]
      }

  private def toDomainOption(row: Option[MonitorRuleRow]): ConnectionIO[Option[MonitorRule]] =
    row match {
      case Some(value) =>
        value.toDomain.map(rule => Option(rule)).liftTo[ConnectionIO]
      case None =>
        none[MonitorRule].pure[ConnectionIO]
    }
}
