package ru.bitec.app.ops
package infrastructure.http

import application.navigation.EnvironmentContext
import application.overview.GetOperationsOverview
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.connection.ConnectionScope
import domain.enviroment.{Environment, EnvironmentKind}
import domain.history.{HistoryEventCursor, HistoryEventSource, HistoryEventType}
import domain.project.Project
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import support.AuthorizationFixtures

import java.time.Instant
import java.util.UUID

final class OperationsOverviewRoutesSpec extends FunSuite {

  test("members and owners read the same overview, with typed attention and the timeline") {
    List(OrganizationRole.Member, OrganizationRole.Owner).foreach { role =>
      val fixture = new RoutesFixture
      val (status, body) = run(fixture, "overview", role)

      assertEquals(status, Status.Ok)
      assertEquals(body.hcursor.downField("scope").get[String]("type"), Right("ORGANIZATION"))
      val summary = body.hcursor.downField("summary")
      assertEquals(summary.downField("nodes").get[Int]("offline"), Right(1))
      assertEquals(summary.downField("containers").get[Int]("running"), Right(21))
      assertEquals(summary.downField("connections").get[Int]("neverSynced"), Right(1))
      assertEquals(summary.downField("incidents").get[Int]("noData"), Right(1))
      assertEquals(summary.downField("operations").get[Int]("unknown"), Right(1))
      assertEquals(body.hcursor.get[Long]("operationsHorizonHours"), Right(24L))

      val items = body.hcursor.downField("attention").downField("items").focus.flatMap(_.asArray)
        .getOrElse(fail("expected attention items"))
      assertEquals(items.map(_.hcursor.get[String]("kind").toOption.get).toList,
        List("OPERATION_UNKNOWN", "INCIDENT", "SYNC_FAILED"))
      assertEquals(items.map(_.hcursor.get[Int]("priority").toOption.get).toList, List(1, 2, 4))
      assertEquals(items.head.hcursor.downField("operation").get[String]("errorCode"),
        Right("OPERATION_RESULT_UNKNOWN"))
      assertEquals(items.head.hcursor.downField("resource").get[String]("environmentId"),
        Right(fixture.environmentId.toString))
      assertEquals(items(1).hcursor.downField("incident").get[String]("reason"), Right("NO_DATA"))
      assertEquals(items(2).hcursor.downField("connection").get[String]("name"), Right("finland_node"))
      assertEquals(body.hcursor.downField("attention").get[Int]("total"), Right(3))
      assertEquals(body.hcursor.downField("recentActivity").focus.flatMap(_.asArray).map(_.size), Some(1))
      // Nothing about how a connection reaches its host is part of the overview.
      List("hostKeyFingerprint", "secretRef", "password", "privateKey", "passphrase", "\"config\"")
        .foreach(secret => assert(!body.noSpaces.contains(secret), s"overview exposed $secret"))
      assertEquals(fixture.scopes, List(ConnectionScope.Organization))
    }
  }

  test("query parameters select a project or an environment of the organization") {
    val fixture = new RoutesFixture

    val project = run(fixture, s"overview?projectId=${fixture.projectId}", OrganizationRole.Member)
    val environment = run(fixture,
      s"overview?projectId=${fixture.projectId}&environmentId=${fixture.environmentId}", OrganizationRole.Member)

    assertEquals(project._1, Status.Ok)
    assertEquals(environment._1, Status.Ok)
    assertEquals(environment._2.hcursor.downField("scope").get[String]("environmentId"),
      Right(fixture.environmentId.toString))
    assertEquals(fixture.scopes, List(
      ConnectionScope.Project(fixture.projectId),
      ConnectionScope.Environment(fixture.projectId, fixture.environmentId)
    ))
  }

  test("an unknown or foreign project or environment is not found, and never queried") {
    val fixture = new RoutesFixture
    val foreign = UUID.randomUUID()

    val project = run(fixture, s"overview?projectId=$foreign", OrganizationRole.Owner)
    val environment = run(fixture, s"overview?projectId=${fixture.projectId}&environmentId=$foreign",
      OrganizationRole.Owner)
    val mismatched = run(fixture, s"overview?projectId=$foreign&environmentId=${fixture.environmentId}",
      OrganizationRole.Owner)

    assertEquals(project._1, Status.NotFound)
    assertEquals(project._2.hcursor.get[String]("code"), Right("PROJECT_NOT_FOUND"))
    assertEquals(environment._1, Status.NotFound)
    assertEquals(environment._2.hcursor.get[String]("code"), Right("ENVIRONMENT_NOT_FOUND"))
    assertEquals(mismatched._1, Status.NotFound)
    assertEquals(fixture.scopes, Nil)
  }

  test("malformed scope parameters are rejected") {
    val fixture = new RoutesFixture

    assertEquals(run(fixture, "overview?projectId=nope", OrganizationRole.Owner)._1, Status.BadRequest)
    assertEquals(run(fixture, s"overview?projectId=${fixture.projectId}&environmentId=nope",
      OrganizationRole.Owner)._1, Status.BadRequest)
    assertEquals(run(fixture, s"overview?environmentId=${fixture.environmentId}",
      OrganizationRole.Owner)._1, Status.BadRequest)
    assertEquals(fixture.scopes, Nil)
  }

  test("a read failure is sanitized") {
    val fixture = new RoutesFixture(failure = Some(new IllegalStateException("database password")))

    val (status, body) = run(fixture, "overview", OrganizationRole.Owner)

    assertEquals(status, Status.InternalServerError)
    assert(!body.noSpaces.contains("database password"))
  }

  private def run(fixture: RoutesFixture, path: String, role: OrganizationRole): (Status, Json) = {
    val request = Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationId/$path"))
    val response = AuthorizationFixtures.authorized(fixture.app, role).run(request).unsafeRunSync()
    response.status -> response.as[Json].unsafeRunSync()
  }

  private final class RoutesFixture(failure: Option[Throwable] = None) {
    val projectId: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
    val environmentId: UUID = UUID.fromString("40000000-0000-0000-0000-000000000001")
    var scopes: List[ConnectionScope] = Nil

    private val project = Project(projectId, OrganizationId, "p", "Project", None, isActive = true, At, At)
    private val environment = Environment(environmentId, OrganizationId, projectId, "prod", "Production",
      EnvironmentKind.Prod, isActive = true, At, At)
    private val resource = OverviewResourceView(UUID.randomUUID(), "api-1", "CONTAINER", environmentId)

    private val query = new OperationsOverviewQuery[IO] {
      override def summary(organizationId: UUID, scope: ConnectionScope, since: Instant): IO[OperationsOverviewSummary] =
        IO { scopes = scopes :+ scope } *> failure.fold(IO.pure(OperationsOverviewSummary(
          NodeSummary(3, 2, 1), ContainerSummary(24, 21, 3), ConnectionSummary(5, 3, 1, 1),
          IncidentSummary(2, 1, 1), OperationSummary(1, 1))))(IO.raiseError)

      override def attention(
        organizationId: UUID,
        scope: ConnectionScope,
        since: Instant,
        limit: Int
      ): IO[AttentionPage] = IO.pure(AttentionPage(List(
        OperationAttention(AttentionKind.OperationUnknown, UUID.randomUUID(), At, "CONTAINER_STOP",
          Some("OPERATION_RESULT_UNKNOWN"), Some("Operation result is unknown"), resource),
        IncidentAttention(UUID.randomUUID(), At, "NO_DATA", "CPU_USAGE_PERCENT", resource),
        SyncFailureAttention(UUID.randomUUID(), At, Some("SSH_HOST_KEY_MISMATCH"),
          Some("SSH host key does not match"), OverviewConnectionView(UUID.randomUUID(), "finland_node"))
      ), 3))
    }

    private val history = new HistoryEventQuery[IO] {
      private val event = HistoryEventView(UUID.randomUUID(), HistoryEventType.SyncFailed,
        HistoryEventSource.System, At, None, Some(HistoryConnectionView(UUID.randomUUID(), "finland_node")),
        None, None, None, Some(HistorySyncView(UUID.randomUUID(), "FAILED", Some("SSH_HOST_KEY_MISMATCH"))))

      override def listByOrganization(organizationId: UUID, before: Option[HistoryEventCursor],
        limit: Int): IO[List[HistoryEventView]] = IO.raiseError(new UnsupportedOperationException)
      override def listByResource(organizationId: UUID, resourceId: UUID, before: Option[HistoryEventCursor],
        limit: Int): IO[List[HistoryEventView]] = IO.raiseError(new UnsupportedOperationException)
      override def listByScope(organizationId: UUID, scope: ConnectionScope,
        limit: Int): IO[List[HistoryEventView]] = IO.pure(List(event))
    }

    private val projects = new ProjectRepository[IO] {
      override def tryCreate(value: Project): IO[Boolean] = IO.pure(false)
      override def findActiveByOrganization(organizationId: UUID): IO[List[Project]] = IO.pure(List(project))
      override def findActiveById(organizationId: UUID, id: UUID): IO[Option[Project]] =
        IO.pure(Option.when(organizationId == OrganizationId && id == projectId)(project))
    }

    private val navigation = new NavigationQueryRepository[IO] {
      override def findEnvironmentContext(organizationId: UUID, id: UUID): IO[Option[EnvironmentContext]] =
        IO.pure(Option.when(organizationId == OrganizationId && id == environmentId)(
          EnvironmentContext(project, environment)))
    }

    private val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] = program
    }

    val app: org.http4s.HttpApp[IO] = new OperationsOverviewRoutes[IO](
      new GetOperationsOverview[IO](query, history, projects, navigation,
        new TimeProvider[IO] { override def now: IO[Instant] = IO.pure(At) }),
      runner,
      AuthorizationFixtures.authorization
    ).routes.orNotFound
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val At = Instant.parse("2026-09-24T10:00:00Z")
}
