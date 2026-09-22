package ru.bitec.app.ops
package application.port

import domain.metric.{MetricCode, MetricObservation}

import java.time.Instant
import java.util.UUID

trait MetricObservationRepository[F[_]] {
  def insertAll(observations: List[MetricObservation]): F[Unit]

  def findLatestAtOrAfter(
                           organizationId: UUID,
                           resourceId: UUID,
                           metricCode: MetricCode,
                           observedAt: Instant
                         ): F[Option[MetricObservation]]
}
