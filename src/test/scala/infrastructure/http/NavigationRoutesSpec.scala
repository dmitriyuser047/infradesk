package ru.bitec.app.ops
package infrastructure.http

import application.navigation.{GetOrganization, ListEnvironments, ListProjects}
import application.port.{EnvironmentRepository, OrganizationRepository, ProjectRepository, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.enviroment.{Environment, EnvironmentKind}
import domain.organization.Organization
import domain.project.Project
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._

import java.time.Instant
import java.util.UUID

final class NavigationRoutesSpec extends FunSuite {
  test("gets an active organization with the minimal navigation DTO") {
    val fixture = buildFixture(List(organizationA), List.empty, List.empty)

    val response = run(fixture, Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationAId")))

    assertEquals(response._1.status, Status.Ok)
    assertEquals(response._2.hcursor.get[String]("id"), Right(OrganizationAId.toString))
    assertEquals(response._2.hcursor.get[String]("code"), Right("ACME"))
    assertEquals(response._2.hcursor.get[String]("name"), Right("Acme"))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  test("returns organization not found for unknown or inactive organizations and validates UUIDs") {
    val fixture = buildFixture(List(inactiveOrganization), List.empty, List.empty)

    val unknown = run(fixture, Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationAId")))
    val invalid = run(fixture, Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/organizations/invalid")))

    assertEquals(unknown._1.status, Status.NotFound)
    assertEquals(unknown._2.hcursor.get[String]("code"), Right("ORGANIZATION_NOT_FOUND"))
    assertEquals(invalid._1.status, Status.BadRequest)
    assertEquals(invalid._2.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
  }

  test("lists active projects and preserves a valid empty organization") {
    val projectsFixture = buildFixture(List(organizationA), List(projectA, inactiveProjectA, projectB), List.empty)
    val emptyFixture = buildFixture(List(organizationA), List.empty, List.empty)

    val projects = run(projectsFixture, projectsRequest(OrganizationAId))
    val empty = run(emptyFixture, projectsRequest(OrganizationAId))

    assertEquals(projects._1.status, Status.Ok)
    assertEquals(projects._2.asArray.map(_.map(_.hcursor.get[String]("id").toOption)), Some(Vector(Some(ProjectAId.toString))))
    assertEquals(projects._2.asArray.flatMap(_.headOption).flatMap(_.hcursor.get[Option[String]]("description").toOption), Some(None))
    assertEquals(projectsFixture.transactionRunner.calls, 1)
    assertEquals(empty._1.status, Status.Ok)
    assertEquals(empty._2.asArray, Some(Vector.empty))
  }

  test("returns organization not found when listing projects for an unknown organization") {
    val fixture = buildFixture(List.empty, List(projectA), List.empty)

    val response = run(fixture, projectsRequest(OrganizationAId))

    assertEquals(response._1.status, Status.NotFound)
    assertEquals(response._2.hcursor.get[String]("code"), Right("ORGANIZATION_NOT_FOUND"))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  test("lists active environments using stable kind codes and supports an empty project") {
    val environmentsFixture = buildFixture(
      List(organizationA),
      List(projectA),
      List(environmentA, inactiveEnvironmentA, environmentB)
    )
    val emptyFixture = buildFixture(List(organizationA), List(projectA), List.empty)

    val environments = run(environmentsFixture, environmentsRequest(OrganizationAId, ProjectAId))
    val empty = run(emptyFixture, environmentsRequest(OrganizationAId, ProjectAId))
    val environment = environments._2.asArray.flatMap(_.headOption).getOrElse(fail("Expected environment"))

    assertEquals(environments._1.status, Status.Ok)
    assertEquals(environment.hcursor.get[String]("id"), Right(EnvironmentAId.toString))
    assertEquals(environment.hcursor.get[String]("projectId"), Right(ProjectAId.toString))
    assertEquals(environment.hcursor.get[String]("kind"), Right("PROD"))
    assertEquals(environmentsFixture.transactionRunner.calls, 1)
    assertEquals(empty._1.status, Status.Ok)
    assertEquals(empty._2.asArray, Some(Vector.empty))
  }

  test("does not disclose environments through a foreign or invalid project") {
    val fixture = buildFixture(List(organizationA), List(projectA, projectB), List(environmentA, environmentB))

    val foreign = run(fixture, environmentsRequest(OrganizationAId, ProjectBId))
    val invalid = run(fixture, Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationAId/projects/invalid/environments")))

    assertEquals(foreign._1.status, Status.NotFound)
    assertEquals(foreign._2.hcursor.get[String]("code"), Right("PROJECT_NOT_FOUND"))
    assertEquals(invalid._1.status, Status.BadRequest)
    assertEquals(invalid._2.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
  }

  private def buildFixture(
    organizations: List[Organization],
    projects: List[Project],
    environments: List[Environment]
  ): RouteFixture = {
    val organizationRepository = new InMemoryOrganizationRepository(organizations)
    val projectRepository = new InMemoryProjectRepository(projects)
    val environmentRepository = new InMemoryEnvironmentRepository(environments)
    val transactionRunner = new RecordingTransactionRunner
    val routes = new NavigationRoutes[IO](
      GetOrganization(organizationRepository),
      ListProjects(organizationRepository, projectRepository),
      ListEnvironments(organizationRepository, projectRepository, environmentRepository),
      transactionRunner
    )

    RouteFixture(routes.routes.orNotFound, transactionRunner)
  }

  private def run(
    fixture: RouteFixture,
    request: Request[IO]
  ): (org.http4s.Response[IO], Json) = {
    val response = fixture.app.run(request).unsafeRunSync()
    response -> response.as[Json].unsafeRunSync()
  }

  private def projectsRequest(organizationId: UUID): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/projects"))

  private def environmentsRequest(
    organizationId: UUID,
    projectId: UUID
  ): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/projects/$projectId/environments"))

  private final case class RouteFixture(
    app: org.http4s.HttpApp[IO],
    transactionRunner: RecordingTransactionRunner
  )

  private final class RecordingTransactionRunner extends TransactionRunner[IO, IO] {
    var calls: Int = 0

    override def run[A](program: IO[A]): IO[A] =
      IO { calls += 1 } *> program
  }

  private final class InMemoryOrganizationRepository(
    organizations: List[Organization]
  ) extends OrganizationRepository[IO] {
    override def findActiveById(id: UUID): IO[Option[Organization]] =
      IO.pure(organizations.find(organization => organization.id == id && organization.isActive))
  }

  private final class InMemoryProjectRepository(
    projects: List[Project]
  ) extends ProjectRepository[IO] {
    override def tryCreate(project: Project): IO[Boolean] = IO.pure(false)
    override def findActiveByOrganization(organizationId: UUID): IO[List[Project]] =
      IO.pure(projects.filter(project => project.organizationId == organizationId && project.isActive))

    override def findActiveById(
      organizationId: UUID,
      projectId: UUID
    ): IO[Option[Project]] =
      IO.pure(projects.find(project =>
        project.organizationId == organizationId && project.id == projectId && project.isActive
      ))
  }

  private final class InMemoryEnvironmentRepository(
    environments: List[Environment]
  ) extends EnvironmentRepository[IO] {
    override def tryCreate(environment: Environment): IO[Boolean] = IO.pure(false)
    override def findActiveByProject(
      organizationId: UUID,
      projectId: UUID
    ): IO[List[Environment]] =
      IO.pure(environments.filter(environment =>
        environment.organizationId == organizationId && environment.projectId == projectId && environment.isActive
      ))
  }

  private val OrganizationAId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val OrganizationBId = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val ProjectAId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val ProjectBId = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val EnvironmentAId = UUID.fromString("30000000-0000-0000-0000-000000000001")
  private val EnvironmentBId = UUID.fromString("30000000-0000-0000-0000-000000000002")
  private val Now = Instant.parse("2026-09-22T12:00:00Z")

  private val organizationA = Organization(OrganizationAId, "ACME", "Acme", true, Now, Now)
  private val inactiveOrganization = organizationA.copy(isActive = false)
  private val projectA = Project(ProjectAId, OrganizationAId, "svinpeak", "SvinPeak", None, true, Now, Now)
  private val inactiveProjectA = projectA.copy(id = UUID.fromString("20000000-0000-0000-0000-000000000003"), isActive = false)
  private val projectB = Project(ProjectBId, OrganizationBId, "foreign", "Foreign", None, true, Now, Now)
  private val environmentA = Environment(EnvironmentAId, OrganizationAId, ProjectAId, "prod", "Production", EnvironmentKind.Prod, true, Now, Now)
  private val inactiveEnvironmentA = environmentA.copy(id = UUID.fromString("30000000-0000-0000-0000-000000000003"), isActive = false)
  private val environmentB = Environment(EnvironmentBId, OrganizationBId, ProjectBId, "prod", "Production", EnvironmentKind.Prod, true, Now, Now)
}
