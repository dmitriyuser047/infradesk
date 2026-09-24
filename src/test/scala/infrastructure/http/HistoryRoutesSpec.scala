package ru.bitec.app.ops
package infrastructure.http

import application.history.ListHistoryEvents
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.history.{HistoryEventCursor, HistoryEventSource, HistoryEventType}
import domain.resource.{Resource, ResourceData}
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import support.AuthorizationFixtures

import java.time.Instant
import java.util.UUID

final class HistoryRoutesSpec extends FunSuite {

  test("both members and owners read the organization timeline, newest first") {
    val fixture = new RoutesFixture

    List(OrganizationRole.Owner, OrganizationRole.Member).foreach { role =>
      val response = run(fixture, "history-events", role)
      val rows = response._2.asArray.getOrElse(fail("expected an array"))

      assertEquals(response._1.status, Status.Ok)
      assertEquals(rows.size, 2)
      assertEquals(rows.head.hcursor.get[String]("eventType"), Right("OPERATION_FAILED"))
      assertEquals(rows.head.hcursor.get[String]("source"), Right("SYSTEM"))
      assertEquals(rows.head.hcursor.downField("resource").get[String]("name"), Right("api-1"))
      assertEquals(rows.head.hcursor.downField("operation").get[String]("errorCode"),
        Right("SSH_COMMAND_TIMEOUT"))
      // The safe error only; nothing about how the command reached the host.
      assert(!rows.head.noSpaces.contains("docker"))
      assert(!rows.head.noSpaces.contains("targetExternalId"))
      assertEquals(rows.last.hcursor.downField("actor").get[String]("displayName"), Right("Owner"))
    }
    assertEquals(fixture.query.organizationCalls.map(_._2), List(None, None))
  }

  test("the resource timeline is tenant-safe and paginated by its cursor") {
    val fixture = new RoutesFixture

    val page = run(fixture, s"resources/${fixture.resourceId}/history-events", OrganizationRole.Member)
    val cursorPage = run(fixture,
      s"resources/${fixture.resourceId}/history-events?beforeOccurredAt=$At&beforeId=${fixture.secondId}",
      OrganizationRole.Owner)
    val foreign = run(fixture, s"resources/${UUID.randomUUID()}/history-events", OrganizationRole.Owner)
    val malformed = run(fixture, "resources/not-a-uuid/history-events", OrganizationRole.Owner)

    assertEquals(page._1.status, Status.Ok)
    assertEquals(cursorPage._1.status, Status.Ok)
    assertEquals(fixture.query.resourceCalls.last._3, Some(HistoryEventCursor(At, fixture.secondId)))
    // A resource of another organization is not found, not forbidden.
    assertEquals(foreign._1.status, Status.NotFound)
    assertEquals(foreign._2.hcursor.get[String]("code"), Right("RESOURCE_NOT_FOUND"))
    assertEquals(malformed._1.status, Status.BadRequest)
  }

  test("a half cursor and an unusable limit are rejected") {
    val fixture = new RoutesFixture

    assertEquals(run(fixture, s"history-events?beforeOccurredAt=$At", OrganizationRole.Owner)._1.status,
      Status.BadRequest)
    assertEquals(run(fixture, s"history-events?beforeId=${UUID.randomUUID()}", OrganizationRole.Owner)._1.status,
      Status.BadRequest)
    assertEquals(run(fixture, "history-events?limit=0", OrganizationRole.Owner)._1.status, Status.BadRequest)
    assertEquals(run(fixture, "history-events?limit=500", OrganizationRole.Owner)._1.status, Status.BadRequest)
    assertEquals(run(fixture, "history-events?limit=10", OrganizationRole.Owner)._1.status, Status.Ok)
    assertEquals(fixture.query.organizationCalls.last._3, 10)
  }

  test("a repository failure is sanitized") {
    val fixture = new RoutesFixture(failure = Some(new IllegalStateException("database password")))

    val response = run(fixture, "history-events", OrganizationRole.Owner)

    assertEquals(response._1.status, Status.InternalServerError)
    assert(!response._2.noSpaces.contains("database password"))
  }

  private def run(
    fixture: RoutesFixture,
    path: String,
    role: OrganizationRole
  ): (org.http4s.Response[IO], Json) = {
    val request = Request[IO](Method.GET,
      Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationId/$path"))
    val response = AuthorizationFixtures.authorized(fixture.app, role).run(request).unsafeRunSync()
    response -> response.as[Json].unsafeRunSync()
  }

  private final class RoutesFixture(failure: Option[Throwable] = None) {
    val resourceId: UUID = UUID.fromString("70000000-0000-0000-0000-000000000001")
    val secondId: UUID = UUID.fromString("b0000000-0000-0000-0000-000000000002")
    val query = new RecordingQuery(resourceId, secondId, failure)

    private val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] = program
    }

    val app: org.http4s.HttpApp[IO] =
      new HistoryRoutes[IO](new ListHistoryEvents[IO](query), new SingleResourceRepository(resourceId),
        runner, AuthorizationFixtures.authorization).routes.orNotFound
  }

  private final class RecordingQuery(
    resourceId: UUID,
    secondId: UUID,
    failure: Option[Throwable]
  ) extends HistoryEventQuery[IO] {
    var organizationCalls: List[(UUID, Option[HistoryEventCursor], Int)] = List.empty
    var resourceCalls: List[(UUID, UUID, Option[HistoryEventCursor], Int)] = List.empty

    private val failed = HistoryEventView(
      UUID.fromString("b0000000-0000-0000-0000-000000000001"),
      HistoryEventType.OperationFailed,
      HistoryEventSource.System,
      At.plusSeconds(60),
      Some(HistoryResourceView(resourceId, "api-1", "CONTAINER")),
      None,
      None,
      None,
      Some(HistoryOperationView(UUID.randomUUID(), "CONTAINER_RESTART", "FAILED",
        Some("SSH_COMMAND_TIMEOUT"), Some("SSH command timed out"))),
      None
    )

    private val requested = failed.copy(
      id = secondId,
      eventType = HistoryEventType.OperationRequested,
      source = HistoryEventSource.User,
      occurredAt = At,
      actor = Some(HistoryActorView(AuthorizationFixtures.ActorUserId, "Owner")),
      operation = None
    )

    override def listByOrganization(
      organizationId: UUID,
      before: Option[HistoryEventCursor],
      limit: Int
    ): IO[List[HistoryEventView]] =
      IO { organizationCalls = organizationCalls :+ ((organizationId, before, limit)) } *>
        failure.fold(IO.pure(List(failed, requested)))(IO.raiseError[List[HistoryEventView]])

    override def listByResource(
      organizationId: UUID,
      resourceId: UUID,
      before: Option[HistoryEventCursor],
      limit: Int
    ): IO[List[HistoryEventView]] =
      IO { resourceCalls = resourceCalls :+ ((organizationId, resourceId, before, limit)) } *>
        failure.fold(IO.pure(List(failed, requested)))(IO.raiseError[List[HistoryEventView]])
  }

  /** Knows one resource of one organization, which is what tenant safety is tested against. */
  private final class SingleResourceRepository(resourceId: UUID) extends ResourceRepository[IO] {
    private val resource = Resource(resourceId, OrganizationId,
      UUID.fromString("40000000-0000-0000-0000-000000000001"),
      UUID.fromString("10000000-0000-0000-0000-000000000002"), None, "api-1", "api-1",
      isActive = true, At, At, "CONTAINER", ResourceData.empty)

    override def findById(organizationId: UUID, id: UUID): IO[Option[Resource]] =
      IO.pure(Option.when(organizationId == OrganizationId && id == resourceId)(resource))
    override def findActiveByEnvironment(organizationId: UUID, environmentId: UUID): IO[List[Resource]] =
      IO.pure(List.empty)
    override def save(value: Resource): IO[Unit] = IO.unit
    override def deactivateIfExclusiveToConnection(
      organizationId: UUID,
      id: UUID,
      connectionId: UUID,
      now: Instant
    ): IO[Boolean] = IO.pure(false)
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val At = Instant.parse("2026-09-24T10:00:00Z")
}
