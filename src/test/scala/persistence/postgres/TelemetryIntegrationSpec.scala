package ru.bitec.app.ops
package persistence.postgres

import application.resource.{PendingMetricObservation, RecordResourceObservations}
import cats.effect.unsafe.implicits.global
import domain.metric.{MetricCode, ResourceTelemetry}
import domain.resource.{Resource, ResourceData}
import domain.resource.container.{ContainerSpec, ContainerStatus}
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class TelemetryIntegrationSpec extends FunSuite {
  test("container telemetry is durable, queryable in history and isolated by tenant") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"))
    val org = UUID.fromString("20000000-0000-0000-0000-000000000001")
    val env = UUID.fromString("40000000-0000-0000-0000-000000000001")
    val now = Instant.parse("2026-10-09T12:00:00Z")
    val id = UUID.randomUUID()
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val resources = ProductionResourceCodec.resourceRepository
      val observations = new PostgresMetricObservationRepository
      val recorder = new RecordResourceObservations[ConnectionIO](observations)
      val telemetry = ResourceTelemetry(MetricCode.All.map(code => code.code ->
        (if (code.isPercentage) BigDecimal(42) else BigDecimal(4096))).toMap)
      val program = for {
        resourceType <- sql"select id from resource_type where code = 'CONTAINER'".query[UUID].unique
        resource = Resource(id, org, env, resourceType, None, "telemetry-" + id, "Telemetry",
          true, now, now, "CONTAINER", ResourceData(Some(ContainerSpec(Some("test:1"))),
            Some(ContainerStatus(Some("running"), telemetry))))
        _ <- resources.save(resource)
        pending = recorder.metricCodesFor(resource.resourceTypeCode, resource.data)
          .map(code => PendingMetricObservation(UUID.randomUUID(), code))
        _ <- recorder.execute(resource, pending, now)
        stored <- resources.findById(org, id)
        history <- observations.findByResourceAndPeriod(org, id, now.minusSeconds(1), now.plusSeconds(1))
        foreign <- observations.findByResourceAndPeriod(UUID.randomUUID(), id, now.minusSeconds(1), now.plusSeconds(1))
        latest <- observations.findLatestAtOrAfter(org, id, MetricCode.DiskFreeBytes, now)
      } yield {
        assertEquals(stored.map(_.data), Some(resource.data))
        assertEquals(history.map(v => v.metricCode.code -> v.value).toMap, telemetry.metrics)
        assertEquals(foreign, Nil)
        assertEquals(latest.map(_.value), Some(BigDecimal(4096)))
      }
      runner.run(program)
    }.unsafeRunSync()
  }
}
