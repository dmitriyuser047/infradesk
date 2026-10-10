package ru.bitec.app.ops
package application.resource

import application.port.MetricObservationRepository
import domain.metric.{MetricCode, MetricObservation}
import domain.resource.{Resource, ResourceData}
import domain.resource.node.{NodeDefinition, NodeStatus}
import domain.resource.container.{ContainerDefinition, ContainerStatus}

import cats.MonadThrow
import cats.syntax.all._

import java.time.Instant
import java.util.UUID

final case class PendingMetricObservation(
                                           id: UUID,
                                           metricCode: MetricCode
                                         )

final class RecordResourceObservations[Tx[_]: MonadThrow](
                                                           metricObservationRepository: MetricObservationRepository[Tx]
                                                         ) {

  def metricCodesFor(resourceTypeCode: String, data: ResourceData): List[MetricCode] =
    metricValuesFor(resourceTypeCode, data).map(_._1)


  def execute(
               resource: Resource,
               pendingObservations: List[PendingMetricObservation],
               observedAt: Instant
             ): Tx[Unit] = {
    val observations = observationsFor(resource, pendingObservations, observedAt)

    observations match {
      case Right(values) =>
        metricObservationRepository.insertAll(values)

      case Left(error) =>
        error.raiseError[Tx, Unit]
    }
  }

  private def observationsFor(
                               resource: Resource,
                               pendingObservations: List[PendingMetricObservation],
                               observedAt: Instant
                             ): Either[IllegalArgumentException, List[MetricObservation]] = {
    val metricValues = valuesFor(resource)
    val pendingCodes = pendingObservations.map(_.metricCode)
    val metricCodes = metricValues.map(_._1)

    if (pendingCodes != metricCodes) {
      Left(new IllegalArgumentException(
        s"Prepared metric observation codes $pendingCodes do not match resource ${resource.id} metrics $metricCodes"
      ))
    } else {
      Right(
        pendingObservations.zip(metricValues).map {
          case (pending, (_, value)) =>
            MetricObservation(
              id = pending.id,
              organizationId = resource.organizationId,
              resourceId = resource.id,
              metricCode = pending.metricCode,
              value = value,
              observedAt = observedAt
            )
        }
      )
    }
  }

  private def valuesFor(resource: Resource): List[(MetricCode, BigDecimal)] =
    metricValuesFor(resource.resourceTypeCode, resource.data)

  private def telemetryValues(value: domain.metric.ResourceTelemetry): List[(MetricCode, BigDecimal)] =
    MetricCode.All.flatMap(code => value.metrics.get(code.code).map(code -> _))

  private def metricValuesFor(resourceTypeCode: String, data: ResourceData): List[(MetricCode, BigDecimal)] =
    (resourceTypeCode, data.status) match {
      case (code, Some(status: NodeStatus)) if code == NodeDefinition.code =>
        List(
          status.cpuUsagePercent.map(MetricCode.CpuUsagePercent -> _),
          status.memoryUsagePercent.map(MetricCode.MemoryUsagePercent -> _)
        ).flatten ++ telemetryValues(status.telemetry).filterNot(v =>
          v._1 == MetricCode.CpuUsagePercent || v._1 == MetricCode.MemoryUsagePercent)
      case (code, Some(status: ContainerStatus)) if code == ContainerDefinition.code =>
        telemetryValues(status.telemetry)
      case _ => List.empty
    }
}
