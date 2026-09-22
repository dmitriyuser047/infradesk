package ru.bitec.app.ops
package persistence.postgres

import application.discovery.{CreateDiscoveredResource, DiscoveredResource, PendingDiscoveredResource, ReconcileDiscoveredResource, SyncDiscoveredSnapshot}
import application.port.{MetricObservationRepository, TransactionRunner}
import application.resource.{PendingMetricObservation, PersistExternalResource, RecordResourceObservations}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.externalref.ExternalRef
import domain.metric.{MetricCode, MetricObservation}
import domain.resource.{Resource, ResourceData}
import domain.resource.node.{NodeSpec, NodeStatus}
import domain.sync.{SyncSession, SyncSessionStatus}
import infrastructure.database.{Database, DatabaseConfig, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class SyncDiscoveredSnapshotRollbackIntegrationSpec extends FunSuite {

  test("rolls back node reconcile and inserted observations when observation recording fails") {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    val config = DatabaseConfig.load.unsafeRunSync()
    val ids = TestIds.random()

    Database.transactor(config).use { xa =>
      val transactionRunner: TransactionRunner[IO, ConnectionIO] =
        new DoobieTransactionRunner(xa)
      val resourceRepository = new PostgresResourceRepository
      val resourceTypeRepository = new PostgresResourceTypeRepository
      val externalRefRepository = new PostgresExternalRefRepository
      val syncSessionRepository = new PostgresSyncSessionRepository
      val metricObservationRepository = new PostgresMetricObservationRepository
      val recorder = new RecordResourceObservations[ConnectionIO](
        new FailingAfterInsertMetricObservationRepository(metricObservationRepository)
      )
      val persistExternalResource = new PersistExternalResource[ConnectionIO](
        resourceRepository,
        externalRefRepository
      )
      val create = new CreateDiscoveredResource[ConnectionIO](
        resourceTypeRepository,
        externalRefRepository,
        persistExternalResource
      )
      val reconcile = new ReconcileDiscoveredResource[ConnectionIO](
        resourceRepository,
        externalRefRepository
      )
      val snapshot = new SyncDiscoveredSnapshot[ConnectionIO](
        create,
        reconcile,
        externalRefRepository,
        resourceRepository,
        syncSessionRepository,
        recorder
      )

      val before = nodeResource(ids, "node-before", NodeStatus(true, None, None, Some(1)))
      val externalRef = ExternalRef(
        id = ids.externalRefId,
        organizationId = OrganizationId,
        connectionId = ConnectionId,
        externalType = "NODE",
        externalId = ids.resourceCode,
        resourceId = ids.resourceId,
        firstSeenAt = ObservedAt,
        lastSeenAt = ObservedAt,
        createdAt = ObservedAt,
        updatedAt = ObservedAt,
        lastSeenSyncSessionId = None
      )
      val session = SyncSession(
        id = ids.sessionId,
        organizationId = OrganizationId,
        connectionId = ConnectionId,
        startedAt = ObservedAt,
        finishedAt = None,
        status = SyncSessionStatus.Running
      )
      val setup = for {
        _ <- resourceRepository.save(before)
        _ <- externalRefRepository.save(externalRef)
        _ <- syncSessionRepository.create(session)
      } yield ()

      val discovered = DiscoveredResource(
        externalType = "NODE",
        externalId = ids.resourceCode,
        resourceTypeCode = "NODE",
        code = before.code,
        name = "node-after",
        data = ResourceData(
          Some(NodeSpec("node-after", Some("Linux"), Some("x86_64"), Some(4), Some(8192))),
          Some(NodeStatus(true, Some(BigDecimal("12.5")), Some(BigDecimal("37.5")), Some(2)))
        )
      )
      val pending = PendingDiscoveredResource(
        discovered,
        UUID.randomUUID(),
        UUID.randomUUID(),
        List(
          PendingMetricObservation(UUID.randomUUID(), MetricCode.CpuUsagePercent),
          PendingMetricObservation(UUID.randomUUID(), MetricCode.MemoryUsagePercent)
        )
      )

      val program = for {
        _ <- transactionRunner.run(setup)
        result <- transactionRunner
          .run(snapshot.execute(connection, session, List(pending), Set("NODE"), ObservedAt))
          .attempt
        resourceAfter <- transactionRunner.run(
          resourceRepository.findById(OrganizationId, ids.resourceId)
        )
        observationCount <- transactionRunner.run(
          sql"""
            select count(*)
            from metric_observation
            where organization_id = $OrganizationId
              and resource_id = ${ids.resourceId}
          """.query[Long].unique
        )
        sessionStatus <- transactionRunner.run(
          sql"""
            select status
            from sync_session
            where organization_id = $OrganizationId
              and id = ${ids.sessionId}
          """.query[String].unique
        )
        _ <- transactionRunner.run(cleanup(ids))
      } yield (result, resourceAfter, observationCount, sessionStatus)

      program.guarantee(transactionRunner.run(cleanup(ids)).attempt.void).flatMap {
        case (result, resourceAfter, observationCount, sessionStatus) =>
          IO {
            assert(result.isLeft)
            assertEquals(resourceAfter, Some(before))
            assertEquals(observationCount, 0L)
            assertEquals(sessionStatus, SyncSessionStatus.Running.code)
          }
      }
    }.unsafeRunSync()
  }

  private def nodeResource(ids: TestIds, name: String, status: NodeStatus): Resource =
    Resource(
      id = ids.resourceId,
      organizationId = OrganizationId,
      environmentId = EnvironmentId,
      resourceTypeId = NodeResourceTypeId,
      parentResourceId = None,
      code = ids.resourceCode,
      name = name,
      isActive = true,
      createdAt = ObservedAt,
      updatedAt = ObservedAt,
      resourceTypeCode = "NODE",
      data = ResourceData(
        Some(NodeSpec(name, None, None, None, None)),
        Some(status)
      )
    )

  private def cleanup(ids: TestIds): ConnectionIO[Unit] =
    for {
      _ <- sql"""
        delete from metric_observation
        where organization_id = $OrganizationId
          and resource_id = ${ids.resourceId}
      """.update.run
      _ <- sql"""
        delete from external_ref
        where organization_id = $OrganizationId
          and id = ${ids.externalRefId}
      """.update.run
      _ <- sql"""
        delete from sync_session
        where organization_id = $OrganizationId
          and id = ${ids.sessionId}
      """.update.run
      _ <- sql"""
        delete from resource
        where organization_id = $OrganizationId
          and id = ${ids.resourceId}
      """.update.run
    } yield ()

  private final class FailingAfterInsertMetricObservationRepository(
                                                                      delegate: MetricObservationRepository[ConnectionIO]
                                                                    ) extends MetricObservationRepository[ConnectionIO] {
    override def insertAll(observations: List[MetricObservation]): ConnectionIO[Unit] =
      delegate.insertAll(observations) *>
        new IllegalStateException("Simulated metric observation failure after insert")
          .raiseError[ConnectionIO, Unit]

    override def findLatestAtOrAfter(
                                      organizationId: UUID,
                                      resourceId: UUID,
                                      metricCode: domain.metric.MetricCode,
                                      observedAt: Instant
                                    ): ConnectionIO[Option[MetricObservation]] =
      delegate.findLatestAtOrAfter(organizationId, resourceId, metricCode, observedAt)
  }

  private final case class TestIds(
                                    resourceId: UUID,
                                    externalRefId: UUID,
                                    sessionId: UUID,
                                    resourceCode: String
                                  )

  private object TestIds {
    def random(): TestIds = {
      val suffix = UUID.randomUUID().toString.replace("-", "")

      TestIds(
        resourceId = UUID.randomUUID(),
        externalRefId = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        resourceCode = s"rollback-$suffix"
      )
    }
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val ConnectionId = UUID.fromString("60000000-0000-0000-0000-000000000003")
  private val NodeResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ObservedAt = Instant.parse("2026-09-21T10:00:00Z")

  private val connection = Connection(
    id = ConnectionId,
    organizationId = OrganizationId,
    scope = ConnectionScope.Environment(
      UUID.fromString("30000000-0000-0000-0000-000000000001"),
      EnvironmentId
    ),
    connectorType = "SSH",
    code = "TEST",
    name = "Test connection",
    config = ConnectionConfig(Map.empty),
    secretRef = None,
    isActive = true,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )
}
