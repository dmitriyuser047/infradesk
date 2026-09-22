package ru.bitec.app.ops
package application.resource

import application.port.{MetricObservationRepository, ResourceRepository}
import domain.metric.MetricObservation

import java.time.Instant
import java.util.UUID
import cats.MonadThrow
import cats.syntax.all._

final class InvalidMetricPeriodException extends IllegalArgumentException("from must be before to")

final case class GetResourceMetricHistory[Tx[_]: MonadThrow](resourceRepository: ResourceRepository[Tx], metricObservationRepository: MetricObservationRepository[Tx]) {
  def execute(organizationId: UUID, resourceId: UUID, from: Instant, to: Instant): Tx[Option[List[MetricObservation]]] =
    if (!from.isBefore(to)) new InvalidMetricPeriodException().raiseError[Tx, Option[List[MetricObservation]]]
    else resourceRepository.findById(organizationId, resourceId).flatMap {
      case Some(_) => metricObservationRepository.findByResourceAndPeriod(organizationId, resourceId, from, to).map(Some(_))
      case None => none[List[MetricObservation]].pure[Tx]
    }
}
