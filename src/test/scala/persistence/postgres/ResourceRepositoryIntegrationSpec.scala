package ru.bitec.app.ops
package persistence.postgres

import cats.effect.unsafe.implicits.global
import domain.resource.container.{ContainerDefinition, ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}
import domain.resource.{Resource, ResourceData}
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite

import java.time.Instant
import java.util.UUID

/** Typed resource data survives a PostgreSQL round trip for every shipped resource type, through
  * the registry-backed codec the composition root builds.
  */
final class ResourceRepositoryIntegrationSpec extends FunSuite {

  import ResourceRepositoryIntegrationSpec._

  test("saves and reads back typed NODE and CONTAINER data") {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    val nodeId = UUID.randomUUID()
    val containerId = UUID.randomUUID()
    val emptyId = UUID.randomUUID()
    val suffix = UUID.randomUUID().toString.replace("-", "")

    val nodeData = ResourceData(
      Some(NodeSpec("node-1", Some("Linux"), Some("x86_64"), Some(8), Some(16384))),
      Some(NodeStatus(true, Some(BigDecimal("42.5")), Some(BigDecimal("71.25")), Some(98765)))
    )
    val containerData = ResourceData(
      Some(ContainerSpec(Some("backend:2.0"))),
      Some(ContainerStatus(Some("running")))
    )

    val node = resource(nodeId, NodeResourceTypeId, s"node-$suffix", NodeDefinition.code, nodeData)
    val container =
      resource(containerId, ContainerResourceTypeId, s"container-$suffix", ContainerDefinition.code, containerData)
    val emptyNode =
      resource(emptyId, NodeResourceTypeId, s"empty-$suffix", NodeDefinition.code, ResourceData.empty)

    val loaded = PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val repository = ProductionResourceCodec.resourceRepository
      val runner = new DoobieTransactionRunner(xa)

      runner.run(for {
        _ <- repository.save(node)
        _ <- repository.save(container)
        _ <- repository.save(emptyNode)
        storedNode <- repository.findById(OrganizationId, nodeId)
        storedContainer <- repository.findById(OrganizationId, containerId)
        storedEmpty <- repository.findById(OrganizationId, emptyId)
      } yield (storedNode, storedContainer, storedEmpty))
    }.unsafeRunSync()

    assertEquals(loaded._1.map(_.data), Some(nodeData))
    assertEquals(loaded._1.map(_.resourceTypeCode), Some(NodeDefinition.code))
    assertEquals(loaded._2.map(_.data), Some(containerData))
    assertEquals(loaded._2.map(_.resourceTypeCode), Some(ContainerDefinition.code))
    assertEquals(loaded._3.map(_.data), Some(ResourceData.empty))
  }
}

object ResourceRepositoryIntegrationSpec {

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val NodeResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ContainerResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val Now = Instant.parse("2026-09-23T10:00:00Z")

  private def resource(
    id: UUID,
    resourceTypeId: UUID,
    code: String,
    resourceTypeCode: String,
    data: ResourceData
  ): Resource =
    Resource(
      id = id,
      organizationId = OrganizationId,
      environmentId = EnvironmentId,
      resourceTypeId = resourceTypeId,
      parentResourceId = None,
      code = code,
      name = code,
      isActive = true,
      createdAt = Now,
      updatedAt = Now,
      resourceTypeCode = resourceTypeCode,
      data = data
    )
}
