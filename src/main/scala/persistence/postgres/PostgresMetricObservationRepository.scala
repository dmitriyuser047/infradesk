package ru.bitec.app.ops
package persistence.postgres

import application.port.MetricObservationRepository
import domain.metric.MetricObservation

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

final class PostgresMetricObservationRepository extends MetricObservationRepository[ConnectionIO] {

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
