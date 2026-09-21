package ru.bitec.app.ops
package application.port

import domain.metric.MetricObservation

trait MetricObservationRepository[F[_]] {
  def insertAll(observations: List[MetricObservation]): F[Unit]
}
