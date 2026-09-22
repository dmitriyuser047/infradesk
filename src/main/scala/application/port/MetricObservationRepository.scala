package ru.bitec.app.ops
package application.port

import domain.metric.{MetricCode, MetricObservation}

import java.util.UUID

trait MetricObservationRepository[F[_]] {
  def insertAll(observations: List[MetricObservation]): F[Unit]

  def findLatest(
                  organizationId: UUID,
                  resourceId: UUID,
                  metricCode: MetricCode
                ): F[Option[MetricObservation]]
}
