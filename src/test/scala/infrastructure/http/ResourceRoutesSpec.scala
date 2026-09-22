package ru.bitec.app.ops
package infrastructure.http

import application.port.{ResourceRepository, TransactionRunner}
import application.resource.GetResource
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeSpec, NodeStatus}
import domain.resource.{Resource, ResourceData}
import io.circe.Json
import infrastructure.http.dto.HttpJsonCodecs._
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._

import java.time.Instant
import java.util.UUID

final class ResourceRoutesSpec extends FunSuite {

  test("returns a NODE resource and its current status") {
    val fixture = buildFixture(Map((OrganizationId, ResourceId) -> nodeResource))

    val response = fixture.app.run(request(OrganizationId, ResourceId)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assertEquals(body.hcursor.get[String]("resourceTypeCode"), Right("NODE"))
    assertEquals(body.hcursor.downField("data").get[String]("kind"), Right("NODE"))
    assertEquals(body.hcursor.downField("data").downField("spec").get[String]("hostname"), Right("node-1"))
    assertEquals(body.hcursor.downField("data").downField("spec").get[Int]("cpuCores"), Right(4))
    assertEquals(body.hcursor.downField("data").downField("status").get[BigDecimal]("cpuUsagePercent"), Right(BigDecimal(42.5)))
    assertEquals(body.hcursor.downField("data").downField("status").get[BigDecimal]("memoryUsagePercent"), Right(BigDecimal(70)))
    assertEquals(fixture.transactionRunner.calls, 1)
    assertEquals(fixture.repository.requests, List((OrganizationId, ResourceId)))
  }

  test("returns a CONTAINER resource and its data") {
    val fixture = buildFixture(Map((OrganizationId, ResourceId) -> containerResource))

    val response = fixture.app.run(request(OrganizationId, ResourceId)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assertEquals(body.hcursor.downField("data").get[String]("kind"), Right("CONTAINER"))
    assertEquals(body.hcursor.downField("data").downField("spec").get[String]("image"), Right("nginx:1.27"))
    assertEquals(body.hcursor.downField("data").downField("status").get[String]("state"), Right("running"))
  }

  test("returns a NODE resource with empty resource data") {
    val fixture = buildFixture(Map((OrganizationId, ResourceId) -> nodeResource.copy(data = ResourceData.empty)))

    val response = fixture.app.run(request(OrganizationId, ResourceId)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assertEquals(body.hcursor.downField("data").get[String]("kind"), Right("NODE"))
    assert(body.hcursor.downField("data").downField("spec").focus.exists(_.isNull))
    assert(body.hcursor.downField("data").downField("status").focus.exists(_.isNull))
  }

  test("returns 404 when a resource is absent") {
    val fixture = buildFixture(Map.empty)

    val response = fixture.app.run(request(OrganizationId, ResourceId)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.NotFound)
    assertEquals(body.hcursor.get[String]("code"), Right("RESOURCE_NOT_FOUND"))
  }

  test("returns 404 when a resource belongs to another organization") {
    val fixture = buildFixture(Map((OrganizationId, ResourceId) -> nodeResource))

    val response = fixture.app.run(request(OtherOrganizationId, ResourceId)).unsafeRunSync()

    assertEquals(response.status, Status.NotFound)
  }

  test("returns 400 for an invalid organization UUID") {
    val fixture = buildFixture(Map.empty)
    val response = fixture.app.run(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/not-a-uuid/resources/$ResourceId"))
    ).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.BadRequest)
    assertEquals(body.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
    assertEquals(body.hcursor.get[String]("message"), Right("Invalid organizationId"))
  }

  test("returns 400 for an invalid resource UUID") {
    val fixture = buildFixture(Map.empty)
    val response = fixture.app.run(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationId/resources/not-a-uuid"))
    ).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.BadRequest)
    assertEquals(body.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
    assertEquals(body.hcursor.get[String]("message"), Right("Invalid resourceId"))
  }

  test("returns a sanitized 500 when the application query fails") {
    val fixture = buildFixture(Map.empty, failWith = Some(new IllegalStateException("database connection secret")))

    val response = fixture.app.run(request(OrganizationId, ResourceId)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.InternalServerError)
    assertEquals(body.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assertEquals(body.hcursor.get[String]("message"), Right("Internal server error"))
    assert(!body.noSpaces.contains("database connection secret"))
  }

  private def buildFixture(
                       resources: Map[(UUID, UUID), Resource],
                       failWith: Option[Throwable] = None
                     ): RouteFixture = {
    val repository = new InMemoryResourceRepository(resources, failWith)
    val transactionRunner = new RecordingTransactionRunner
    val routes = new ResourceRoutes[IO](GetResource[IO](repository), transactionRunner)

    RouteFixture(routes.routes.orNotFound, repository, transactionRunner)
  }

  private def request(organizationId: UUID, resourceId: UUID): Request[IO] =
    Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/resources/$resourceId")
    )

  private final case class RouteFixture(
                                          app: org.http4s.HttpApp[IO],
                                          repository: InMemoryResourceRepository,
                                          transactionRunner: RecordingTransactionRunner
                                        )

  private final class RecordingTransactionRunner extends TransactionRunner[IO, IO] {
    var calls = 0

    override def run[A](program: IO[A]): IO[A] =
      IO { calls += 1 } *> program
  }

  private final class InMemoryResourceRepository(
                                                   resources: Map[(UUID, UUID), Resource],
                                                   failWith: Option[Throwable]
                                                 ) extends ResourceRepository[IO] {
    var requests: List[(UUID, UUID)] = List.empty

    override def findById(organizationId: UUID, id: UUID): IO[Option[Resource]] =
      IO {
        requests = requests :+ (organizationId, id)
      } *> failWith.fold(IO.pure(resources.get((organizationId, id))))(IO.raiseError)

    override def save(resource: Resource): IO[Unit] = IO.unit

    override def deactivateIfExclusiveToConnection(
                                                     organizationId: UUID,
                                                     id: UUID,
                                                     connectionId: UUID,
                                                     now: Instant
                                                   ): IO[Unit] = IO.unit
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val ResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val Now = Instant.parse("2026-09-22T10:00:00Z")

  private val nodeResource = Resource(
    ResourceId,
    OrganizationId,
    EnvironmentId,
    ResourceTypeId,
    None,
    "node-1",
    "node-1",
    isActive = true,
    Now,
    Now,
    "NODE",
    ResourceData(
      Some(NodeSpec("node-1", Some("Linux"), Some("x86_64"), Some(4), Some(8192))),
      Some(NodeStatus(true, Some(BigDecimal(42.5)), Some(BigDecimal(70)), Some(12345)))
    )
  )

  private val containerResource = nodeResource.copy(
    code = "nginx",
    name = "nginx",
    resourceTypeCode = "CONTAINER",
    data = ResourceData(
      Some(ContainerSpec(Some("nginx:1.27"))),
      Some(ContainerStatus(Some("running")))
    )
  )
}
