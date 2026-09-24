package ru.bitec.app.ops
package infrastructure.http

import application.audit.ListAuditEvents
import application.port.{AuditEventRepository, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.audit.{AuditAction, AuditCursor, AuditEvent, AuditTargetType}
import domain.auth.OrganizationRole
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import support.AuthorizationFixtures

import java.time.Instant
import java.util.UUID

final class AuditRoutesSpec extends FunSuite {

  test("returns the newest events of the current organization only") {
    val fixture = new RouteFixture(List(newest, older, foreign))

    val response = run(fixture, request())
    val rows = response._2.asArray.getOrElse(fail("expected an array"))

    assertEquals(response._1.status, Status.Ok)
    assertEquals(rows.size, 2)
    assertEquals(rows.head.hcursor.get[String]("id"), Right(newest.id.toString))
    assertEquals(rows.head.hcursor.get[String]("action"), Right("MONITOR_RULE_UPDATED"))
    assertEquals(rows.head.hcursor.get[String]("targetType"), Right("MONITOR_RULE"))
    assertEquals(rows.head.hcursor.get[String]("actorUserId"),
      Right(AuthorizationFixtures.ActorUserId.toString))
    // Identifiers only: no payload, no configuration, nothing a secret could hide in.
    assertEquals(rows.head.asObject.map(_.keys.size), Some(6))
    assertEquals(fixture.repository.queries.head._1, OrganizationId)
    assertEquals(fixture.repository.queries.head._3, ListAuditEvents.DefaultLimit)
  }

  test("a cursor continues after one exact row and needs both of its parts") {
    val fixture = new RouteFixture(List(newest, older))

    val page = run(fixture, request(s"?beforeOccurredAt=${newest.occurredAt}&beforeId=${newest.id}"))
    val halfCursor = run(fixture, request(s"?beforeOccurredAt=${newest.occurredAt}"))
    val malformed = run(fixture, request("?beforeOccurredAt=yesterday&beforeId=nonsense"))

    assertEquals(page._1.status, Status.Ok)
    assertEquals(fixture.repository.queries.head._2, Some(AuditCursor(newest.occurredAt, newest.id)))
    assertEquals(halfCursor._1.status, Status.BadRequest)
    assertEquals(malformed._1.status, Status.BadRequest)
    assertEquals(malformed._2.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
  }

  test("the page size is bounded and rejected when it is not a usable number") {
    val fixture = new RouteFixture(List(newest))

    assertEquals(run(fixture, request("?limit=10"))._1.status, Status.Ok)
    assertEquals(fixture.repository.queries.head._3, 10)
    assertEquals(run(fixture, request("?limit=0"))._1.status, Status.BadRequest)
    assertEquals(run(fixture, request("?limit=1000"))._1.status, Status.BadRequest)
    assertEquals(run(fixture, request("?limit=all"))._1.status, Status.BadRequest)
  }

  test("a member cannot read the journal") {
    val fixture = new RouteFixture(List(newest))

    val response = fixture.app(OrganizationRole.Member)
      .run(Request[IO](Method.GET, uri())).unsafeRunSync()

    assertEquals(response.status, Status.Forbidden)
    assertEquals(response.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("FORBIDDEN"))
    assertEquals(fixture.repository.queries, List.empty)
  }

  test("a repository failure is sanitized") {
    val fixture = new RouteFixture(List.empty, failure = Some(new IllegalStateException("database password")))

    val response = run(fixture, request())

    assertEquals(response._1.status, Status.InternalServerError)
    assertEquals(response._2.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assert(!response._2.noSpaces.contains("database password"))
  }

  private def run(fixture: RouteFixture, request: Request[IO]): (org.http4s.Response[IO], Json) = {
    val response = fixture.app(OrganizationRole.Owner).run(request).unsafeRunSync()
    response -> response.as[Json].unsafeRunSync()
  }

  private def uri(query: String = ""): Uri =
    Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationId/audit-events$query")

  private def request(query: String = ""): Request[IO] = Request[IO](Method.GET, uri(query))

  private final class RouteFixture(events: List[AuditEvent], failure: Option[Throwable] = None) {
    val repository = new RecordingRepository(events, failure)

    def app(role: OrganizationRole): org.http4s.HttpApp[IO] =
      AuthorizationFixtures.authorized(
        new AuditRoutes[IO](new ListAuditEvents[IO](repository), runner,
          AuthorizationFixtures.authorization).routes.orNotFound,
        role
      )

    private val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] = program
    }
  }

  private final class RecordingRepository(
    events: List[AuditEvent],
    failure: Option[Throwable]
  ) extends AuditEventRepository[IO] {
    var queries: List[(UUID, Option[AuditCursor], Int)] = List.empty

    override def save(event: AuditEvent): IO[Unit] = IO.unit
    override def saveAll(values: List[AuditEvent]): IO[Unit] = IO.unit

    override def listByOrganization(
      organizationId: UUID,
      before: Option[AuditCursor],
      limit: Int
    ): IO[List[AuditEvent]] =
      IO { queries = queries :+ ((organizationId, before, limit)) } *>
        failure.fold(IO.pure(events.filter(_.organizationId == organizationId)
          .sortBy(event => (event.occurredAt, event.id))(Ordering.Tuple2[Instant, UUID].reverse)
          .take(limit)))(IO.raiseError[List[AuditEvent]])
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val At = Instant.parse("2026-09-24T10:00:00Z")

  private def event(
    id: UUID,
    organizationId: UUID,
    action: AuditAction,
    targetType: AuditTargetType,
    occurredAt: Instant
  ): AuditEvent =
    AuditEvent(id, organizationId, AuthorizationFixtures.ActorUserId, action, targetType,
      Some(UUID.fromString("90000000-0000-0000-0000-000000000001")), occurredAt, occurredAt)

  private val newest = event(UUID.fromString("e0000000-0000-0000-0000-000000000002"), OrganizationId,
    AuditAction.MonitorRuleUpdated, AuditTargetType.MonitorRule, At.plusSeconds(60))
  private val older = event(UUID.fromString("e0000000-0000-0000-0000-000000000001"), OrganizationId,
    AuditAction.ProjectCreated, AuditTargetType.Project, At)
  private val foreign = event(UUID.fromString("e0000000-0000-0000-0000-000000000003"),
    OtherOrganizationId, AuditAction.ConnectionCreated, AuditTargetType.Connection, At.plusSeconds(120))
}
