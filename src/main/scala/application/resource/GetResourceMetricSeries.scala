package ru.bitec.app.ops
package application.resource

import application.port.{MetricSeriesQuery, ResourceRepository}
import cats.MonadThrow
import cats.syntax.all._
import domain.metric.{MetricCode, MetricResolution, MetricSeriesPoint}

import java.time.{Duration, Instant}
import java.util.UUID

final class InvalidMetricSeriesPeriodException
  extends IllegalArgumentException(s"from must be before to, at most ${MetricResolution.MaxPeriod.toDays} days apart")

final case class MetricSeries(resolution: MetricResolution, from: Instant, to: Instant, points: List[MetricSeriesPoint])

/**
 * Metric history of one resource at the resolution its period calls for. Long periods read the
 * rollups that outlive raw observations; the resource must belong to the organization.
 */
final case class GetResourceMetricSeries[Tx[_]: MonadThrow](
  resources: ResourceRepository[Tx],
  series: MetricSeriesQuery[Tx]
) {
  def execute(organizationId: UUID, resourceId: UUID, from: Instant, to: Instant): Tx[Option[MetricSeries]] =
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(MetricResolution.MaxPeriod) > 0)
      new InvalidMetricSeriesPeriodException().raiseError[Tx, Option[MetricSeries]]
    else resources.findById(organizationId, resourceId).flatMap {
      case None => none[MetricSeries].pure[Tx]
      case Some(_) =>
        val resolution = MetricResolution.forPeriod(from, to)
        // Only metrics this version understands; rows of other codes stay stored but are not drawn.
        series.series(organizationId, resourceId, resolution, from, to, MetricCode.All.map(_.code))
          .map(points => Some(MetricSeries(resolution, from, to, points)))
    }
}
