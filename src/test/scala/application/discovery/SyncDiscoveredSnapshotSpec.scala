package ru.bitec.app.ops
package application.discovery

import application.port.{ExternalRefRepository, MetricObservationRepository, ResourceRepository, ResourceTypeRepository, SyncSessionRepository}
import application.resource.{PendingMetricObservation, PersistExternalResource, RecordResourceObservations}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.externalref.ExternalRef
import domain.resource.{Resource, ResourceType}
import domain.resource.ResourceData
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeSpec, NodeStatus}
import domain.metric.MetricCode
import domain.sync.{SyncSession, SyncSessionStatus}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class SyncDiscoveredSnapshotSpec extends FunSuite {

  test("replaces a missing container before creating its replacement") {
    val result = runSnapshot(initialState, failNewResourceSave = false)
    val state = result.toOption.getOrElse(fail("Snapshot should succeed"))

    assertEquals(state.resources.get(oldResourceId).map(_.isActive), Some(false))
    assertEquals(state.resources.get(newResourceId).map(_.isActive), Some(true))
    assertEquals(state.resources.get(newResourceId).map(_.code), Some("backend"))
    assertEquals(
      state.resources.get(newResourceId).map(_.data),
      Some(
        ResourceData(
          Some(ContainerSpec(Some("backend:2.0"))),
          Some(ContainerStatus(Some("running")))
        )
      )
    )
    assertEquals(state.externalRefs.map(_.externalId).toSet, Set("aaa", "bbb"))
    assertEquals(state.sessions.get(syncSessionId).map(_.status), Some(SyncSessionStatus.Completed))
  }

  test("rolls back missing deactivation when replacement creation fails") {
    val result = runSnapshot(initialState, failNewResourceSave = true)

    assert(result.isLeft)

    val rolledBackState = result.fold(_ => initialState, identity)

    assertEquals(rolledBackState.resources.get(oldResourceId).map(_.isActive), Some(true))
    assertEquals(rolledBackState.resources.contains(newResourceId), false)
    assertEquals(rolledBackState.sessions.get(syncSessionId).map(_.status), Some(SyncSessionStatus.Running))
  }

  test("does not deactivate containers omitted by a node-only partial discovery") {
    val result = runSnapshot(
      initialState,
      failNewResourceSave = false,
      completeExternalTypes = Set("NODE"),
      discoveredResources = List.empty
    )
    val state = result.toOption.getOrElse(fail("Snapshot should succeed"))

    assertEquals(state.resources.get(oldResourceId).map(_.isActive), Some(true))
    assertEquals(state.sessions.get(syncSessionId).map(_.status), Some(SyncSessionStatus.Completed))
  }

  test("fails snapshot after node reconcile when observation insert fails") {
    val result = runSnapshot(
      nodeInitialState,
      failNewResourceSave = false,
      completeExternalTypes = Set("NODE"),
      discoveredResources = List(nodeReconciliation),
      metricObservationRepository = new FailingMetricObservationRepository
    )

    assert(result.isLeft)

    val rolledBackState = result.fold(_ => nodeInitialState, identity)
    assertEquals(rolledBackState.resources.get(oldResourceId).map(_.code), Some("node-before"))
    assertEquals(
      rolledBackState.resources.get(oldResourceId).flatMap(_.data.status),
      Some(NodeStatus(true, None, None, Some(1)))
    )
    assertEquals(rolledBackState.sessions.get(syncSessionId).map(_.status), Some(SyncSessionStatus.Running))
  }

  private def runSnapshot(
                          initial: SnapshotState,
                          failNewResourceSave: Boolean,
                          completeExternalTypes: Set[String] = Set("CONTAINER"),
                          discoveredResources: List[PendingDiscoveredResource] = List(replacement),
                          metricObservationRepository: MetricObservationRepository[IO] =
                            new NoopMetricObservationRepository
                        ): Either[Throwable, SnapshotState] = {
    var workingState = initial

    val resourceRepository = new SnapshotResourceRepository(
      () => workingState,
      state => workingState = state,
      failNewResourceSave
    )
    val externalRefRepository = new SnapshotExternalRefRepository(
      () => workingState,
      state => workingState = state
    )
    val syncSessionRepository = new SnapshotSyncSessionRepository(
      () => workingState,
      state => workingState = state
    )
    val resourceTypeRepository = new FixedResourceTypeRepository
    val persistExternalResource = new PersistExternalResource[IO](
      resourceRepository,
      externalRefRepository
    )
    val create = new CreateDiscoveredResource[IO](
      resourceTypeRepository,
      externalRefRepository,
      persistExternalResource
    )
    val reconcile = new ReconcileDiscoveredResource[IO](
      resourceRepository,
      externalRefRepository
    )
    val recordResourceObservations = new RecordResourceObservations[IO](
      metricObservationRepository
    )
    val snapshot = new SyncDiscoveredSnapshot[IO](
      create,
      reconcile,
      externalRefRepository,
      resourceRepository,
      syncSessionRepository,
      recordResourceObservations
    )

    snapshot
      .execute(
        connection,
        runningSession,
        discoveredResources,
        completeExternalTypes,
        completedAt
      )
      .attempt
      .unsafeRunSync()
      .map(_ => workingState)
  }

  private val organizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")
  private val environmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val connectionId = UUID.fromString("60000000-0000-0000-0000-000000000003")
  private val oldResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val newResourceId = UUID.fromString("70000000-0000-0000-0000-000000000002")
  private val oldExternalRefId = UUID.fromString("80000000-0000-0000-0000-000000000001")
  private val newExternalRefId = UUID.fromString("80000000-0000-0000-0000-000000000002")
  private val syncSessionId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val resourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val completedAt = Instant.parse("2026-09-21T10:00:00Z")

  private val connection = Connection(
    id = connectionId,
    organizationId = organizationId,
    scope = ConnectionScope.Environment(projectId, environmentId),
    connectorType = "SSH",
    code = "TEST",
    name = "Test connection",
    config = ConnectionConfig(Map.empty),
    secretRef = None,
    isActive = true,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )

  private val oldResource = Resource(
    id = oldResourceId,
    organizationId = organizationId,
    environmentId = environmentId,
    resourceTypeId = resourceTypeId,
    parentResourceId = None,
    code = "backend",
    name = "backend",
    isActive = true,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )

  private val oldNodeResource = oldResource.copy(
    code = "node-before",
    name = "node-before",
    resourceTypeCode = "NODE",
    data = ResourceData(
      Some(NodeSpec("node-before", None, None, None, None)),
      Some(NodeStatus(true, None, None, Some(1)))
    )
  )

  private val oldExternalRef = ExternalRef(
    id = oldExternalRefId,
    organizationId = organizationId,
    connectionId = connectionId,
    externalType = "CONTAINER",
    externalId = "aaa",
    resourceId = oldResourceId,
    firstSeenAt = Instant.EPOCH,
    lastSeenAt = Instant.EPOCH,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )

  private val oldNodeExternalRef = oldExternalRef.copy(
    externalType = "NODE",
    externalId = "SELF"
  )

  private val runningSession = SyncSession(
    id = syncSessionId,
    organizationId = organizationId,
    connectionId = connectionId,
    startedAt = Instant.EPOCH,
    finishedAt = None,
    status = SyncSessionStatus.Running
  )

  private val replacement = PendingDiscoveredResource(
    DiscoveredResource(
      "CONTAINER",
      "bbb",
      "CONTAINER",
      "backend",
      "backend",
      data = ResourceData(
        Some(ContainerSpec(Some("backend:2.0"))),
        Some(ContainerStatus(Some("running")))
      )
    ),
    newResourceId,
    newExternalRefId
  )

  private val nodeReconciliation = PendingDiscoveredResource(
    DiscoveredResource(
      "NODE",
      "SELF",
      "NODE",
      "TEST",
      "node-after",
      data = ResourceData(
        Some(NodeSpec("node-after", Some("Linux"), Some("x86_64"), Some(4), Some(8192))),
        Some(NodeStatus(true, Some(BigDecimal("12.5")), Some(BigDecimal("37.5")), Some(2)))
      )
    ),
    newResourceId,
    newExternalRefId,
    List(
      PendingMetricObservation(
        UUID.fromString("a0000000-0000-0000-0000-000000000001"),
        MetricCode.CpuUsagePercent
      ),
      PendingMetricObservation(
        UUID.fromString("a0000000-0000-0000-0000-000000000002"),
        MetricCode.MemoryUsagePercent
      )
    )
  )

  private val initialState = SnapshotState(
    resources = Map(oldResourceId -> oldResource),
    externalRefs = List(oldExternalRef),
    sessions = Map(syncSessionId -> runningSession)
  )

  private val nodeInitialState = SnapshotState(
    resources = Map(oldResourceId -> oldNodeResource),
    externalRefs = List(oldNodeExternalRef),
    sessions = Map(syncSessionId -> runningSession)
  )

  private final case class SnapshotState(
                                           resources: Map[UUID, Resource],
                                           externalRefs: List[ExternalRef],
                                           sessions: Map[UUID, SyncSession]
                                         )

  private final class SnapshotResourceRepository(
                                                    state: () => SnapshotState,
                                                    update: SnapshotState => Unit,
                                                    failNewResourceSave: Boolean
                                                  ) extends ResourceRepository[IO] {

    override def findById(organizationId: UUID, id: UUID): IO[Option[Resource]] =
      IO.pure(state().resources.get(id))

    override def save(resource: Resource): IO[Unit] =
      if (failNewResourceSave && resource.id == newResourceId)
        IO.raiseError(new IllegalStateException("Simulated replacement insert failure"))
      else
        IO(update(state().copy(resources = state().resources.updated(resource.id, resource))))

    override def deactivateIfExclusiveToConnection(
                                                     organizationId: UUID,
                                                     id: UUID,
                                                     connectionId: UUID,
                                                     now: Instant
                                                   ): IO[Unit] =
      IO {
        state().resources.get(id).foreach { resource =>
          update(state().copy(resources = state().resources.updated(
            id,
            resource.copy(isActive = false, updatedAt = now)
          )))
        }
      }
  }

  private final class SnapshotExternalRefRepository(
                                                       state: () => SnapshotState,
                                                       update: SnapshotState => Unit
                                                     ) extends ExternalRefRepository[IO] {

    override def findByExternalIdentity(
                                         organizationId: UUID,
                                         connectionId: UUID,
                                         externalType: String,
                                         externalId: String
                                       ): IO[Option[ExternalRef]] =
      IO.pure(state().externalRefs.find { ref =>
        ref.organizationId == organizationId &&
          ref.connectionId == connectionId &&
          ref.externalType == externalType &&
          ref.externalId == externalId
      })

    override def findByConnection(
                                  organizationId: UUID,
                                  connectionId: UUID
                                ): IO[List[ExternalRef]] =
      IO.pure(state().externalRefs.filter { ref =>
        ref.organizationId == organizationId && ref.connectionId == connectionId
      })

    override def save(externalRef: ExternalRef): IO[Unit] =
      IO {
        val withoutCurrent = state().externalRefs.filterNot(_.id == externalRef.id)
        update(state().copy(externalRefs = withoutCurrent :+ externalRef))
      }
  }

  private final class SnapshotSyncSessionRepository(
                                                      state: () => SnapshotState,
                                                      update: SnapshotState => Unit
                                                    ) extends SyncSessionRepository[IO] {

    override def create(session: SyncSession): IO[Unit] =
      IO(update(state().copy(sessions = state().sessions.updated(session.id, session))))

    override def complete(organizationId: UUID, id: UUID, finishedAt: Instant): IO[Unit] =
      IO {
        state().sessions.get(id).foreach { session =>
          update(state().copy(sessions = state().sessions.updated(
            id,
            session.copy(status = SyncSessionStatus.Completed, finishedAt = Some(finishedAt))
          )))
        }
      }

    override def fail(organizationId: UUID, id: UUID, finishedAt: Instant): IO[Unit] =
      IO.unit
  }

  private final class FixedResourceTypeRepository extends ResourceTypeRepository[IO] {

    private val containerType = ResourceType(
      resourceTypeId,
      "CONTAINER",
      "Container",
      1,
      Set.empty,
      isActive = true,
      Instant.EPOCH,
      Instant.EPOCH
    )

    override def findById(id: UUID): IO[Option[ResourceType]] =
      IO.pure(Some(containerType))

    override def findByCode(code: String): IO[Option[ResourceType]] =
      IO.pure(Some(containerType))
  }

  private final class NoopMetricObservationRepository extends MetricObservationRepository[IO] {
    override def insertAll(observations: List[domain.metric.MetricObservation]): IO[Unit] =
      IO.unit
  }

  private final class FailingMetricObservationRepository extends MetricObservationRepository[IO] {
    override def insertAll(observations: List[domain.metric.MetricObservation]): IO[Unit] =
      IO.raiseError(new IllegalStateException("Simulated metric observation insert failure"))
  }
}
