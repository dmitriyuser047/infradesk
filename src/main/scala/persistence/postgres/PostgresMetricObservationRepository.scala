package ru.bitec.app.ops
package persistence.postgres

import application.port.MetricObservationRepository
import domain.metric.{MetricCode, MetricObservation}

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresMetricObservationRepository extends MetricObservationRepository[ConnectionIO] {

  private type MetricObservationRow = (UUID, UUID, UUID, String, BigDecimal, Instant)

  private def toDomain(row: MetricObservationRow): Either[IllegalArgumentException, MetricObservation] = {
    val (id, organizationId, resourceId, metricCode, value, observedAt) = row
    MetricCode.fromCode(metricCode).map(MetricObservation(id, organizationId, resourceId, _, value, observedAt))
  }

  override def findLatestAtOrAfter(
                                    organizationId: UUID,
                                    resourceId: UUID,
                                    metricCode: MetricCode,
                                    observedAt: Instant
                                  ): ConnectionIO[Option[MetricObservation]] =
    sql"""
      select
        id,
        organization_id,
        resource_id,
        metric_code,
        value,
        observed_at
      from metric_observation
      where organization_id = $organizationId
        and resource_id = $resourceId
        and metric_code = ${metricCode.code}
        and observed_at >= $observedAt
      order by observed_at desc
      limit 1
    """
      .query[MetricObservationRow]
      .option
      .flatMap {
        case Some(row) => toDomain(row).map(Option(_)).liftTo[ConnectionIO]
        case None =>
          none[MetricObservation].pure[ConnectionIO]
      }

  override def findByResourceAndPeriod(organizationId: UUID, resourceId: UUID, from: Instant, to: Instant): ConnectionIO[List[MetricObservation]] =
    sql"""select id, organization_id, resource_id, metric_code, value, observed_at
      from metric_observation
      where organization_id = $organizationId and resource_id = $resourceId
        and observed_at >= $from and observed_at < $to
      order by observed_at asc, metric_code asc, id asc"""
      .query[MetricObservationRow].to[List]
      .flatMap(_.traverse(row => toDomain(row).liftTo[ConnectionIO]))

  override def insertAll(observations: List[MetricObservation]): ConnectionIO[Unit] =
    observations.traverse_ { observation =>
      sql"""
        insert into metric_observation (
          id,
          organization_id,
          resource_id,
          metric_code,
          value,
          observed_at
        )
        values (
          ${observation.id},
          ${observation.organizationId},
          ${observation.resourceId},
          ${observation.metricCode.code},
          ${observation.value},
          ${observation.observedAt}
        )
      """
        .update
        .run
        .flatMap {
          case 1 => ().pure[ConnectionIO]
          case rows =>
            new IllegalStateException(
              s"Expected to insert 1 metric_observation row, affected: ${rows}"
            ).raiseError[ConnectionIO, Unit]
        }
    }
}
