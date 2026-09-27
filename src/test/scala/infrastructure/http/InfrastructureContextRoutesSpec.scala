package ru.bitec.app.ops
package infrastructure.http

import application.context.{
  GetConnectionInfrastructureSummary,
  GetResourceContext,
  ListConnectionInfrastructureCounts,
  ListConnectionResources,
  ListEnvironmentResourceSources
}
import application.incident.{ListConnectionIncidents, ListResourceIncidents}
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.{Resource, ResourceData}
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.{Method, Request, Status, Uri}

import java.time.Instant
import java.util.UUID

final class InfrastructureContextRoutesSpec extends FunSuite {

  test("a connection's resources carry their environment; an unknown connection is not found") {
    val (found, body) = run(s"/api/v1/organizations/$Org/connections/$ConnectionId/resources")
    assertEquals(found, Status.Ok)
    val first = body.asArray.flatMap(_.headOption).getOrElse(fail("no resource")).hcursor
    assertEquals(first.get[String]("name"), Right("backend"))
    assertEquals(first.downField("environment").get[String]("name"), Right("Production"))
    assertEquals(first.downField("data").get[String]("kind"), Right("CONTAINER"))

    val (missing, error) = run(s"/api/v1/organizations/$Org/connections/${UUID.randomUUID()}/resources")
    assertEquals(missing, Status.NotFound)
    assertEquals(error.hcursor.get[String]("code"), Right("CONNECTION_NOT_FOUND"))
  }

  test("the summary counts and previews") {
    val (status, body) = run(s"/api/v1/organizations/$Org/connections/$ConnectionId/infrastructure-summary")
    assertEquals(status, Status.Ok)
    val cursor = body.hcursor
    assertEquals(cursor.get[Long]("activeResourceCount"), Right(8L))
    assertEquals(cursor.get[Long]("openIncidentCount"), Right(1L))
    assertEquals(cursor.downField("resourceTypeCounts").downArray.get[String]("resourceTypeCode"), Right("CONTAINER"))
    assertEquals(cursor.downField("openIncidents").downArray.downField("resource").get[String]("name"), Right("backend"))
    assertEquals(cursor.downField("resources").downArray.get[String]("name"), Right("backend"))
  }

  test("incident pages validate their status, limit and two-part cursor") {
    val base = s"/api/v1/organizations/$Org/connections/$ConnectionId/incidents"
    assertEquals(run(s"$base?status=OPEN&limit=10")._1, Status.Ok)
    assertEquals(run(s"$base?status=BAD")._1, Status.BadRequest)
    assertEquals(run(s"$base?limit=0")._1, Status.BadRequest)
    assertEquals(run(s"$base?limit=201")._1, Status.BadRequest)
    // Both halves of the cursor travel together.
    assertEquals(run(s"$base?beforeOpenedAt=$Now")._1, Status.BadRequest)
    assertEquals(run(s"$base?beforeOpenedAt=$Now&beforeId=bad")._1, Status.BadRequest)
    assertEquals(run(s"$base?beforeOpenedAt=$Now&beforeId=$IncidentId")._1, Status.Ok)
    assertEquals(run(s"/api/v1/organizations/$Org/connections/bad/incidents")._1, Status.BadRequest)
  }

  test("a resource context names every source connection and never a primary one") {
    val (status, body) = run(s"/api/v1/organizations/$Org/resources/$ResourceId/context")
    assertEquals(status, Status.Ok)
    val sources = body.hcursor.downField("sourceConnections").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    assertEquals(sources.flatMap(_.hcursor.get[String]("name").toOption), Vector("Finnish Node", "Prometheus"))
    assertEquals(body.hcursor.downField("project").get[String]("name"), Right("SvinPeak"))
    assert(body.hcursor.downField("parentResource").focus.exists(_.isNull))

    val (missing, error) = run(s"/api/v1/organizations/$Org/resources/${UUID.randomUUID()}/context")
    assertEquals(missing, Status.NotFound)
    assertEquals(error.hcursor.get[String]("code"), Right("RESOURCE_NOT_FOUND"))
  }

  test("resource incidents, connection counts and environment sources") {
    val (incidents, incidentBody) = run(s"/api/v1/organizations/$Org/resources/$ResourceId/incidents")
    assertEquals(incidents, Status.Ok)
    assertEquals(incidentBody.asArray.map(_.size), Some(1))
    assertEquals(run(s"/api/v1/organizations/$Org/resources/${UUID.randomUUID()}/incidents")._1, Status.NotFound)

    val (counts, countBody) = run(s"/api/v1/organizations/$Org/connection-infrastructure")
    assertEquals(counts, Status.Ok)
    assertEquals(countBody.asArray.flatMap(_.headOption).map(_.hcursor.get[Long]("openIncidentCount")), Some(Right(1L)))

    val (sources, sourceBody) = run(s"/api/v1/organizations/$Org/environments/$EnvironmentId/resource-sources")
    assertEquals(sources, Status.Ok)
    assertEquals(sourceBody.asArray.flatMap(_.headOption).map(_.hcursor.get[UUID]("resourceId")), Some(Right(ResourceId)))
  }

  test("failures are sanitized") {
    val (status, body) = run(s"/api/v1/organizations/$Org/connections/$ConnectionId/resources", failing = true)
    assertEquals(status, Status.InternalServerError)
    assert(!body.noSpaces.contains("sql secret"))
  }

  // -------------------------------------------------------------------------------------------

  private val Org = UUID.fromString("30000000-0000-0000-0000-000000000001")
  private val ConnectionId = UUID.randomUUID()
  private val ResourceId = UUID.randomUUID()
  private val EnvironmentId = UUID.randomUUID()
  private val IncidentId = UUID.randomUUID()
  private val Now = Instant.parse("2026-09-27T10:00:00Z")
  private val ContainerType = UUID.fromString("10000000-0000-0000-0000-000000000002")

  private val environment = EnvironmentReference(EnvironmentId, "Production", "PROD")
  private val location = InfrastructureLocation(ProjectReference(UUID.randomUUID(), "SvinPeak"), environment)
  private val sources = List(
    SourceConnectionReference(ConnectionId, "Finnish Node", "SSH", active = true),
    SourceConnectionReference(UUID.randomUUID(), "Prometheus", "PROMETHEUS", active = true))
  private val backend = Resource(ResourceId, Org, EnvironmentId, ContainerType, None, "backend", "backend", isActive = true,
    Now, Now, "CONTAINER", ResourceData(Some(ContainerSpec(Some("backend:1"))), Some(ContainerStatus(Some("running")))))
  private val incident = IncidentListItem(
    Incident(IncidentId, Org, UUID.randomUUID(), ResourceId, IncidentStatus.Open, IncidentReason.ThresholdViolation,
      Now, Now, None, Now, Now),
    IncidentResourceReference(ResourceId, "backend", "CONTAINER"),
    location,
    MonitorConditionView(UUID.randomUUID(), "CPU_USAGE_PERCENT", "GREATER_THAN", BigDecimal(85), 300, 900),
    None,
    sources)

  private final class Query(failing: Boolean) extends InfrastructureContextQuery[IO] with IncidentListQuery[IO] {
    private def guard[A](value: => A): IO[A] =
      if (failing) IO.raiseError(new IllegalStateException("sql secret")) else IO(value)

    def connectionExists(o: UUID, c: UUID) = guard(o == Org && c == ConnectionId)
    def resourceExists(o: UUID, r: UUID) = guard(o == Org && r == ResourceId)
    def connectionResources(o: UUID, c: UUID, l: Int) = guard(List(LocatedResource(backend, environment)))
    def connectionCounts(o: UUID, c: UUID) =
      guard(ConnectionResourceCounts(8, 0, 1, List(ResourceTypeCount("CONTAINER", 7), ResourceTypeCount("NODE", 1))))
    def organizationConnectionCounts(o: UUID) = guard(List(ConnectionInfrastructureCounts(ConnectionId, 8, 1)))
    def resourceContext(o: UUID, r: UUID, l: Int) =
      guard(Option.when(o == Org && r == ResourceId)(ResourceContextView(location, None, sources, List.empty, 0, 1)))
    def environmentResourceSources(o: UUID, e: UUID) = guard(List(ResourceSources(ResourceId, sources)))

    def list(o: UUID, s: Option[IncidentStatus], b: Option[IncidentCursor], l: Int) = guard(List(incident))
    def listByConnection(o: UUID, c: UUID, s: Option[IncidentStatus], b: Option[IncidentCursor], l: Int) = guard(List(incident))
    def listByResource(o: UUID, r: UUID, s: Option[IncidentStatus], b: Option[IncidentCursor], l: Int) = guard(List(incident))
    def find(o: UUID, id: UUID) = guard(Option.when(id == IncidentId)(incident))
  }

  private final class Runner extends TransactionRunner[IO, IO] {
    def run[A](program: IO[A]): IO[A] = program
  }

  private def run(path: String, failing: Boolean = false): (Status, Json) = {
    val query = new Query(failing)
    val routes = new InfrastructureContextRoutes[IO](
      GetConnectionInfrastructureSummary[IO](query, query),
      ListConnectionResources[IO](query),
      ListConnectionIncidents[IO](query, query),
      ListConnectionInfrastructureCounts[IO](query),
      GetResourceContext[IO](query),
      ListResourceIncidents[IO](query, query),
      ListEnvironmentResourceSources[IO](query),
      new Runner,
      support.AuthorizationFixtures.authorization,
      org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("test.read-models")
    )
    val response = support.AuthorizationFixtures.authorized(routes.routes.orNotFound)
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path))).unsafeRunSync()
    (response.status, response.as[Json].unsafeRunSync())
  }
}
