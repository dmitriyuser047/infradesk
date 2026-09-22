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
      .query[(java.util.UUID, java.util.UUID, java.util.UUID, String, BigDecimal, java.time.Instant)]
      .option
      .flatMap {
        case Some((id, observationOrganizationId, observationResourceId, persistedMetricCode, value, observedAt)) =>
          MetricCode
            .fromCode(persistedMetricCode)
            .map { typedMetricCode =>
              Option(
                MetricObservation(
                  id,
                  observationOrganizationId,
                  observationResourceId,
                  typedMetricCode,
                  value,
                  observedAt
                )
              )
            }
            .liftTo[ConnectionIO]
        case None =>
          none[MetricObservation].pure[ConnectionIO]
      }

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
