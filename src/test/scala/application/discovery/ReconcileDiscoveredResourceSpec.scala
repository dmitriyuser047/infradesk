package ru.bitec.app.ops
package application.discovery

import application.port.{ExternalRefRepository, ResourceRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.externalref.ExternalRef
import domain.resource.Resource
import domain.resource.ResourceData
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeSpec, NodeStatus}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class ReconcileDiscoveredResourceSpec extends FunSuite {

  test("updates a discovered container without changing its stable fields") {
    val resourceRepository = new RecordingResourceRepository(existingResource)
    val externalRefRepository = new RecordingExternalRefRepository(parentExternalRef)
    val reconcile = new ReconcileDiscoveredResource[IO](
      resourceRepository,
      externalRefRepository
    )

    val reconciled = reconcile.execute(
      connection,
      DiscoveredResource(
        externalType = "CONTAINER",
        externalId = "container-id",
        resourceTypeCode = "CONTAINER",
        code = "api",
        name = "api",
        parentExternalIdentity = Some(DiscoveredExternalIdentity("NODE", "SELF")),
        data = ResourceData(
          Some(ContainerSpec(Some("api:2.0"))),
          Some(ContainerStatus(Some("running")))
        )
      ),
      containerExternalRef,
      syncSessionId,
      reconciledAt
    ).unsafeRunSync()

    assertEquals(reconciled.id, existingResource.id)
    assertEquals(reconciled.organizationId, existingResource.organizationId)
    assertEquals(reconciled.environmentId, existingResource.environmentId)
    assertEquals(reconciled.createdAt, existingResource.createdAt)
    assertEquals(reconciled.code, "api")
    assertEquals(reconciled.name, "api")
    assertEquals(reconciled.parentResourceId, Some(parentResourceId))
    assertEquals(reconciled.isActive, true)
    assertEquals(reconciled.updatedAt, reconciledAt)
    assertEquals(
      reconciled.data,
      ResourceData(
        Some(ContainerSpec(Some("api:2.0"))),
        Some(ContainerStatus(Some("running")))
      )
    )
    assertEquals(resourceRepository.saved, Some(reconciled))
    assertEquals(
      externalRefRepository.saved,
      Some(
        containerExternalRef.copy(
          lastSeenAt = reconciledAt,
          updatedAt = reconciledAt,
          lastSeenSyncSessionId = Some(syncSessionId)
        )
      )
    )
  }

  test("updates NODE SELF inventory fields on the existing resource identity") {
    val existingNode = existingResource.copy(
      code = "node-before",
      name = "node-before",
      resourceTypeCode = "NODE",
      data = ResourceData(
        Some(NodeSpec("node-before", Some("Linux"), None, Some(2), Some(4096))),
        Some(NodeStatus(true, None, None, Some(100)))
      )
    )
    val nodeRef = containerExternalRef.copy(externalType = "NODE", externalId = "SELF")
    val resourceRepository = new RecordingResourceRepository(existingNode)
    val externalRefRepository = new RecordingExternalRefRepository(parentExternalRef)
    val reconcile = new ReconcileDiscoveredResource[IO](resourceRepository, externalRefRepository)

    val reconciled = reconcile.execute(
      connection,
      DiscoveredResource(
        externalType = "NODE",
        externalId = "SELF",
        resourceTypeCode = "NODE",
        code = "node-after",
        name = "node-after",
        data = ResourceData(
          Some(NodeSpec(
            hostname = "node-after", operatingSystem = Some("Linux"),
            distribution = Some("Ubuntu 24.04 LTS"), kernelVersion = Some("6.8.0"),
            architecture = Some("x86_64"), cpuModel = Some("AMD EPYC"),
            cpuCores = Some(4), memoryMb = Some(8192)
          )),
          Some(NodeStatus(true, Some(BigDecimal("12.5")), Some(BigDecimal("37.5")), Some(200)))
        )
      ),
      nodeRef,
      syncSessionId,
      reconciledAt
    ).unsafeRunSync()

    assertEquals(reconciled.id, existingNode.id)
    assertEquals(reconciled.createdAt, existingNode.createdAt)
    assertEquals(reconciled.code, "node-after")
    assertEquals(reconciled.name, "node-after")
    assertEquals(reconciled.data.spec.collect { case spec: NodeSpec => spec.distribution },
      Some(Some("Ubuntu 24.04 LTS")))
    assertEquals(resourceRepository.saved.map(_.id), Some(existingNode.id))
    assertEquals(externalRefRepository.saved.map(_.externalId), Some("SELF"))
  }

  private val organizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")
  private val environmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val connectionId = UUID.fromString("60000000-0000-0000-0000-000000000003")
  private val resourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val parentResourceId = UUID.fromString("70000000-0000-0000-0000-000000000002")
  private val syncSessionId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val resourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val reconciledAt = Instant.parse("2026-09-21T10:00:00Z")

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

  private val existingResource = Resource(
    id = resourceId,
    organizationId = organizationId,
    environmentId = environmentId,
    resourceTypeId = resourceTypeId,
    parentResourceId = None,
    code = "backend",
    name = "backend",
    isActive = false,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )

  private val containerExternalRef = ExternalRef(
    id = UUID.fromString("80000000-0000-0000-0000-000000000001"),
    organizationId = organizationId,
    connectionId = connectionId,
    externalType = "CONTAINER",
    externalId = "container-id",
    resourceId = resourceId,
    firstSeenAt = Instant.EPOCH,
    lastSeenAt = Instant.EPOCH,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )

  private val parentExternalRef = ExternalRef(
    id = UUID.fromString("80000000-0000-0000-0000-000000000002"),
    organizationId = organizationId,
    connectionId = connectionId,
    externalType = "NODE",
    externalId = "SELF",
    resourceId = parentResourceId,
    firstSeenAt = Instant.EPOCH,
    lastSeenAt = Instant.EPOCH,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )

  private final class RecordingResourceRepository(existing: Resource)
    extends ResourceRepository[IO] {

    var saved: Option[Resource] = None

    override def findById(organizationId: UUID, id: UUID): IO[Option[Resource]] =
      IO.pure(Some(existing))

    override def findActiveByEnvironment(
                                           organizationId: UUID,
                                           environmentId: UUID
                                         ): IO[List[Resource]] =
      IO.pure(List.empty)

    override def save(resource: Resource): IO[Unit] =
      IO { saved = Some(resource) }

    override def deactivateIfExclusiveToConnection(
                                                     organizationId: UUID,
                                                     id: UUID,
                                                     connectionId: UUID,
                                                     now: Instant
                                                   ): IO[Unit] =
      IO.unit
  }

  private final class RecordingExternalRefRepository(parentExternalRef: ExternalRef)
    extends ExternalRefRepository[IO] {

    var saved: Option[ExternalRef] = None

    override def findByExternalIdentity(
                                         organizationId: UUID,
                                         connectionId: UUID,
                                         externalType: String,
                                         externalId: String
                                       ): IO[Option[ExternalRef]] =
      IO.pure(Some(parentExternalRef))

    override def findByConnection(
                                  organizationId: UUID,
                                  connectionId: UUID
                                ): IO[List[ExternalRef]] =
      IO.pure(List(parentExternalRef))

    override def save(externalRef: ExternalRef): IO[Unit] =
      IO { saved = Some(externalRef) }
  }
}
