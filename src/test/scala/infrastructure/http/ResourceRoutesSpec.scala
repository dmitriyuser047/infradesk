package ru.bitec.app.ops
package infrastructure.http

import application.port.{MetricObservationRepository, ResourceRepository, TransactionRunner}
import application.resource.{GetResource, GetResourceMetricHistory, ListEnvironmentResources}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeSpec, NodeStatus}
import domain.resource.{Resource, ResourceData}
import domain.metric.{MetricCode, MetricObservation}
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
    assertEquals(body.hcursor.downField("data").downField("spec").get[String]("distribution"), Right("Ubuntu 24.04 LTS"))
    assertEquals(body.hcursor.downField("data").downField("spec").get[String]("kernelVersion"), Right("6.8.0"))
    assertEquals(body.hcursor.downField("data").downField("spec").get[String]("cpuModel"), Right("AMD EPYC"))
    assertEquals(body.hcursor.downField("data").downField("spec").get[Int]("cpuCores"), Right(4))
    assertEquals(body.hcursor.downField("data").downField("status").get[BigDecimal]("cpuUsagePercent"), Right(BigDecimal(42.5)))
    assertEquals(body.hcursor.downField("data").downField("status").get[BigDecimal]("memoryUsagePercent"), Right(BigDecimal(70)))
    assertEquals(fixture.transactionRunner.calls, 1)
    assertEquals(fixture.repository.requests, List((OrganizationId, ResourceId)))
  }

  test("returns a CONTAINER resource and its data") {
    val fixture = buildFixture(Map((OrganizationId, ContainerResourceId) -> containerResource))

    val response = fixture.app.run(request(OrganizationId, ContainerResourceId)).unsafeRunSync()
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

  test("returns chronological CPU and memory history with an inclusive-exclusive period") {
    val from = Instant.parse("2026-09-22T10:00:00Z")
    val to = Instant.parse("2026-09-22T11:00:00Z")
    val fixture = buildFixture(
      Map((OrganizationId, ResourceId) -> nodeResource),
      observations = List(
        observation(MetricCode.CpuUsagePercent, 10, from.minusSeconds(60)),
        observation(MetricCode.CpuUsagePercent, 42.5, from),
        observation(MetricCode.MemoryUsagePercent, 68.2, from),
        observation(MetricCode.CpuUsagePercent, 45.1, from.plusSeconds(1800)),
        observation(MetricCode.CpuUsagePercent, 99, to)
      )
    )

    val response = fixture.app.run(metricRequest(OrganizationId, ResourceId, from, to)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()
    val values = body.asArray.getOrElse(fail("Expected metric array"))

    assertEquals(response.status, Status.Ok)
    assertEquals(values.size, 3)
    assertEquals(values.map(_.hcursor.get[String]("metricCode")), Vector(Right("CPU_USAGE_PERCENT"), Right("MEMORY_USAGE_PERCENT"), Right("CPU_USAGE_PERCENT")))
    assertEquals(values.map(_.hcursor.get[BigDecimal]("value")), Vector(Right(BigDecimal(42.5)), Right(BigDecimal(68.2)), Right(BigDecimal(45.1))))
    assertEquals(values.head.hcursor.get[String]("observedAt"), Right(from.toString))
    assertEquals(fixture.transactionRunner.calls, 1)
    assertEquals(fixture.metricRepository.requests, List((OrganizationId, ResourceId, from, to)))
  }

  test("returns 404 for absent or cross-organization resource metric history") {
    val from = Now
    val to = Now.plusSeconds(60)
    val fixture = buildFixture(Map((OrganizationId, ResourceId) -> nodeResource))

    val absent = fixture.app.run(metricRequest(OrganizationId, ContainerResourceId, from, to)).unsafeRunSync()
    val foreign = fixture.app.run(metricRequest(OtherOrganizationId, ResourceId, from, to)).unsafeRunSync()

    assertEquals(absent.status, Status.NotFound)
    assertEquals(foreign.status, Status.NotFound)
  }

  test("returns 400 for missing, invalid, and invalid-order metric periods") {
    val fixture = buildFixture(Map((OrganizationId, ResourceId) -> nodeResource))
    val base = s"/api/v1/organizations/$OrganizationId/resources/$ResourceId/metrics"
    val requests = List(
      Uri.unsafeFromString(base),
      Uri.unsafeFromString(s"$base?from=$Now"),
      Uri.unsafeFromString(s"$base?from=invalid&to=${Now.plusSeconds(60)}"),
      Uri.unsafeFromString(s"$base?from=${Now.plusSeconds(60)}&to=invalid"),
      Uri.unsafeFromString(s"$base?from=$Now&to=$Now"),
      Uri.unsafeFromString(s"$base?from=${Now.plusSeconds(60)}&to=$Now")
    )

    requests.foreach { uri =>
      val response = fixture.app.run(Request[IO](Method.GET, uri)).unsafeRunSync()
      assertEquals(response.status, Status.BadRequest)
    }
  }

  test("returns sanitized 500 when metric history query fails") {
    val fixture = buildFixture(
      Map((OrganizationId, ResourceId) -> nodeResource),
      metricFailure = Some(new IllegalStateException("metric SQL secret"))
    )
    val response = fixture.app.run(metricRequest(OrganizationId, ResourceId, Now, Now.plusSeconds(60))).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.InternalServerError)
    assertEquals(body.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assert(!body.noSpaces.contains("metric SQL secret"))
  }

  test("lists NODE and CONTAINER resources for an environment in one transaction") {
    val fixture = buildFixture(
      Map(
        (OrganizationId, ResourceId) -> nodeResource,
        (OrganizationId, ContainerResourceId) -> containerResource
      )
    )

    val response = fixture.app.run(environmentResourcesRequest(OrganizationId, EnvironmentId)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()
    val resources = body.asArray.getOrElse(fail("Expected resource array"))
    val node = resources.head.hcursor
    val container = resources(1).hcursor

    assertEquals(response.status, Status.Ok)
    assertEquals(resources.size, 2)
    assertEquals(node.get[String]("resourceTypeCode"), Right("NODE"))
    assertEquals(
      container.downField("data").downField("spec").get[String]("image"),
      Right("nginx:1.27")
    )
    assertEquals(
      container.get[String]("parentResourceId"),
      Right(ResourceId.toString)
    )
    assertEquals(fixture.transactionRunner.calls, 1)
    assertEquals(fixture.repository.environmentRequests, List((OrganizationId, EnvironmentId)))
  }

  test("lists empty resource data without failing") {
    val fixture = buildFixture(
      Map((OrganizationId, ResourceId) -> nodeResource.copy(data = ResourceData.empty))
    )

    val response = fixture.app.run(environmentResourcesRequest(OrganizationId, EnvironmentId)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assert(body.hcursor.downArray.downField("data").downField("spec").focus.exists(_.isNull))
    assert(body.hcursor.downArray.downField("data").downField("status").focus.exists(_.isNull))
  }

  test("returns an empty list and excludes inactive, other environment, and other organization resources") {
    val inactive = nodeResource.copy(id = InactiveResourceId, isActive = false)
    val otherEnvironment = nodeResource.copy(id = OtherEnvironmentResourceId, environmentId = OtherEnvironmentId)
    val otherOrganization = nodeResource.copy(id = OtherOrganizationResourceId, organizationId = OtherOrganizationId)
    val fixture = buildFixture(
      Map(
        (OrganizationId, InactiveResourceId) -> inactive,
        (OrganizationId, OtherEnvironmentResourceId) -> otherEnvironment,
        (OtherOrganizationId, OtherOrganizationResourceId) -> otherOrganization
      )
    )

    val response = fixture.app.run(environmentResourcesRequest(OrganizationId, EnvironmentId)).unsafeRunSync()
    val body = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assertEquals(body.asArray, Some(Vector.empty))
  }

  test("returns 400 for invalid UUIDs in environment resource list") {
    val fixture = buildFixture(Map.empty)
    val invalidOrganization = fixture.app.run(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/not-a-uuid/environments/$EnvironmentId/resources"))
    ).unsafeRunSync()
    val invalidEnvironment = fixture.app.run(
      Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationId/environments/not-a-uuid/resources"))
    ).unsafeRunSync()
    val invalidOrganizationBody = invalidOrganization.as[Json].unsafeRunSync()
    val invalidEnvironmentBody = invalidEnvironment.as[Json].unsafeRunSync()

    assertEquals(invalidOrganization.status, Status.BadRequest)
    assertEquals(invalidEnvironment.status, Status.BadRequest)
    assertEquals(invalidOrganizationBody.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
    assertEquals(invalidOrganizationBody.hcursor.get[String]("message"), Right("Invalid organizationId"))
    assertEquals(invalidEnvironmentBody.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
    assertEquals(invalidEnvironmentBody.hcursor.get[String]("message"), Right("Invalid environmentId"))
  }

  test("returns a sanitized 500 for list query and mapper failures") {
    val queryFailureFixture = buildFixture(Map.empty, failWith = Some(new IllegalStateException("database connection secret")))
    val malformedFixture = buildFixture(
      Map((OrganizationId, ResourceId) -> nodeResource.copy(data = ResourceData(Some(NodeSpec("node-1", None, None, None, None)), None)))
    )

    val queryFailure = queryFailureFixture.app.run(environmentResourcesRequest(OrganizationId, EnvironmentId)).unsafeRunSync()
    val malformed = malformedFixture.app.run(environmentResourcesRequest(OrganizationId, EnvironmentId)).unsafeRunSync()
    val queryFailureBody = queryFailure.as[Json].unsafeRunSync()
    val malformedBody = malformed.as[Json].unsafeRunSync()

    assertEquals(queryFailure.status, Status.InternalServerError)
    assertEquals(malformed.status, Status.InternalServerError)
    assertEquals(queryFailureBody.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assertEquals(malformedBody.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assert(!queryFailureBody.noSpaces.contains("database connection secret"))
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
                       failWith: Option[Throwable] = None,
                       observations: List[MetricObservation] = List.empty,
                       metricFailure: Option[Throwable] = None
                     ): RouteFixture = {
    val repository = new InMemoryResourceRepository(resources, failWith)
    val transactionRunner = new RecordingTransactionRunner
    val metricRepository = new RecordingMetricObservationRepository(observations, metricFailure)
    val routes = new ResourceRoutes[IO](
      GetResource[IO](repository),
      ListEnvironmentResources[IO](repository),
      GetResourceMetricHistory[IO](repository, metricRepository),
      transactionRunner,
      support.AuthorizationFixtures.authorization
    )

    RouteFixture(support.AuthorizationFixtures.authorized(routes.routes.orNotFound), repository, metricRepository,
      transactionRunner)
  }

  private def request(organizationId: UUID, resourceId: UUID): Request[IO] =
    Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/resources/$resourceId")
    )

  private def environmentResourcesRequest(organizationId: UUID, environmentId: UUID): Request[IO] =
    Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/environments/$environmentId/resources")
    )

  private def metricRequest(organizationId: UUID, resourceId: UUID, from: Instant, to: Instant): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/resources/$resourceId/metrics?from=$from&to=$to"))

  private final case class RouteFixture(
                                          app: org.http4s.HttpApp[IO],
                                          repository: InMemoryResourceRepository,
                                          metricRepository: RecordingMetricObservationRepository,
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
    var environmentRequests: List[(UUID, UUID)] = List.empty

    override def findById(organizationId: UUID, id: UUID): IO[Option[Resource]] =
      IO {
        requests = requests :+ (organizationId, id)
      } *> failWith.fold(IO.pure(resources.get((organizationId, id))))(IO.raiseError)

    override def findActiveByEnvironment(
                                           organizationId: UUID,
                                           environmentId: UUID
                                         ): IO[List[Resource]] =
      IO {
        environmentRequests = environmentRequests :+ (organizationId, environmentId)
      } *> failWith.fold(
        IO.pure(
          resources.values.toList
            .filter(resource =>
              resource.organizationId == organizationId &&
                resource.environmentId == environmentId &&
                resource.isActive
            )
            .sortBy(resource => (resource.parentResourceId.isDefined, resource.name, resource.id.toString))
        )
      )(IO.raiseError)

    override def save(resource: Resource): IO[Unit] = IO.unit

    override def deactivateIfExclusiveToConnection(
                                                     organizationId: UUID,
                                                     id: UUID,
                                                     connectionId: UUID,
                                                     now: Instant
                                                   ): IO[Boolean] = IO.pure(false)
  }

  private final class RecordingMetricObservationRepository(initial: List[MetricObservation], failure: Option[Throwable]) extends MetricObservationRepository[IO] {
    var requests: List[(UUID, UUID, Instant, Instant)] = List.empty
    override def insertAll(observations: List[MetricObservation]): IO[Unit] = IO.unit
    override def findLatestAtOrAfter(organizationId: UUID, resourceId: UUID, metricCode: MetricCode, observedAt: Instant): IO[Option[MetricObservation]] = IO.pure(None)
    override def findByResourceAndPeriod(organizationId: UUID, resourceId: UUID, from: Instant, to: Instant): IO[List[MetricObservation]] =
      IO { requests = requests :+ (organizationId, resourceId, from, to) } *> (failure match {
        case Some(error) => IO.raiseError[List[MetricObservation]](error)
        case None => IO.pure(initial.filter(value => value.organizationId == organizationId && value.resourceId == resourceId && !value.observedAt.isBefore(from) && value.observedAt.isBefore(to)).sortBy(value => (value.observedAt, value.metricCode.code, value.id.toString)))
      })
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val OtherEnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000002")
  private val ResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val ContainerResourceId = UUID.fromString("70000000-0000-0000-0000-000000000002")
  private val InactiveResourceId = UUID.fromString("70000000-0000-0000-0000-000000000003")
  private val OtherEnvironmentResourceId = UUID.fromString("70000000-0000-0000-0000-000000000004")
  private val OtherOrganizationResourceId = UUID.fromString("70000000-0000-0000-0000-000000000005")
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
      Some(NodeSpec(
        hostname = "node-1", operatingSystem = Some("Linux"), distribution = Some("Ubuntu 24.04 LTS"),
        kernelVersion = Some("6.8.0"), architecture = Some("x86_64"), cpuModel = Some("AMD EPYC"),
        cpuCores = Some(4), memoryMb = Some(8192)
      )),
      Some(NodeStatus(true, Some(BigDecimal(42.5)), Some(BigDecimal(70)), Some(12345)))
    )
  )

  private val containerResource = nodeResource.copy(
    id = ContainerResourceId,
    parentResourceId = Some(ResourceId),
    code = "nginx",
    name = "nginx",
    resourceTypeCode = "CONTAINER",
    data = ResourceData(
      Some(ContainerSpec(Some("nginx:1.27"))),
      Some(ContainerStatus(Some("running")))
    )
  )

  private def observation(metricCode: MetricCode, value: BigDecimal, observedAt: Instant): MetricObservation =
    MetricObservation(UUID.randomUUID(), OrganizationId, ResourceId, metricCode, value, observedAt)
}
