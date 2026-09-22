package ru.bitec.app.ops
package application.resource

import application.port.MetricObservationRepository
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.metric.{MetricCode, MetricObservation}
import domain.resource.{Resource, ResourceData}
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeSpec, NodeStatus}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class RecordResourceObservationsSpec extends FunSuite {

  test("records CPU and memory observations for a reconciled node resource") {
    val repository = new RecordingMetricObservationRepository
    val recorder = new RecordResourceObservations[IO](repository)
    val resource = nodeResource(Some(BigDecimal("12.5")), Some(BigDecimal("37.5")))
    val pending = pendingFor(recorder, resource)

    recorder.execute(resource, pending, observedAt).unsafeRunSync()

    assertEquals(repository.inserted.map(_.metricCode), List(
      MetricCode.CpuUsagePercent,
      MetricCode.MemoryUsagePercent
    ))
    assertEquals(repository.inserted.map(_.value), List(BigDecimal("12.5"), BigDecimal("37.5")))
    assertEquals(repository.inserted.map(_.resourceId), List(resource.id, resource.id))
  }

  test("records only CPU when node memory usage is unavailable") {
    val repository = new RecordingMetricObservationRepository
    val recorder = new RecordResourceObservations[IO](repository)
    val resource = nodeResource(Some(BigDecimal("12.5")), None)

    recorder.execute(resource, pendingFor(recorder, resource), observedAt).unsafeRunSync()

    assertEquals(repository.inserted.map(_.metricCode), List(MetricCode.CpuUsagePercent))
  }

  test("records no observations when node metrics are unavailable") {
    val repository = new RecordingMetricObservationRepository
    val recorder = new RecordResourceObservations[IO](repository)
    val resource = nodeResource(None, None)

    recorder.execute(resource, pendingFor(recorder, resource), observedAt).unsafeRunSync()

    assertEquals(repository.inserted, List.empty)
  }

  test("records no observations for a container") {
    val repository = new RecordingMetricObservationRepository
    val recorder = new RecordResourceObservations[IO](repository)
    val resource = Resource(
      id = resourceId,
      organizationId = organizationId,
      environmentId = environmentId,
      resourceTypeId = resourceTypeId,
      parentResourceId = None,
      code = "container",
      name = "container",
      isActive = true,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
      resourceTypeCode = "CONTAINER",
      data = ResourceData(
        Some(ContainerSpec(Some("backend:1.0"))),
        Some(ContainerStatus(Some("running")))
      )
    )

    recorder.execute(resource, pendingFor(recorder, resource), observedAt).unsafeRunSync()

    assertEquals(repository.inserted, List.empty)
  }

  private def pendingFor(
                          recorder: RecordResourceObservations[IO],
                          resource: Resource
                        ): List[PendingMetricObservation] =
    recorder
      .metricCodesFor(resource.resourceTypeCode, resource.data)
      .zipWithIndex
      .map { case (metricCode, index) =>
        PendingMetricObservation(
          UUID.fromString(f"90000000-0000-0000-0000-${index + 1}%012d"),
          metricCode
        )
      }

  private def nodeResource(
                            cpuUsagePercent: Option[BigDecimal],
                            memoryUsagePercent: Option[BigDecimal]
                          ): Resource =
    Resource(
      id = resourceId,
      organizationId = organizationId,
      environmentId = environmentId,
      resourceTypeId = resourceTypeId,
      parentResourceId = None,
      code = "node",
      name = "node",
      isActive = true,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
      resourceTypeCode = "NODE",
      data = ResourceData(
        Some(NodeSpec("node", Some("Linux"), Some("x86_64"), Some(4), Some(8192))),
        Some(NodeStatus(true, cpuUsagePercent, memoryUsagePercent, Some(123)))
      )
    )

  private val organizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val environmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val resourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val resourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val observedAt = Instant.parse("2026-09-21T10:00:00Z")

  private final class RecordingMetricObservationRepository extends MetricObservationRepository[IO] {
    var inserted: List[MetricObservation] = List.empty

    override def insertAll(observations: List[MetricObservation]): IO[Unit] =
      IO {
        inserted = inserted ++ observations
      }

    override def findLatest(
                             organizationId: UUID,
                             resourceId: UUID,
                             metricCode: MetricCode
                           ): IO[Option[MetricObservation]] =
      IO.pure(None)
  }
}
