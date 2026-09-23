package ru.bitec.app.ops
package infrastructure.http

import application.port.{EnvironmentRepository, IdGenerator, OrganizationRepository, ProjectRepository, TimeProvider, TransactionRunner}
import application.workspace.{CreateEnvironment, CreateProject}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.enviroment.Environment
import domain.organization.Organization
import domain.project.Project
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}

import java.time.Instant
import java.util.UUID

final class WorkspaceMutationRoutesSpec extends FunSuite {
  import CirceEntityDecoder._
  import CirceEntityEncoder._

  private val orgA = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val orgB = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val now = Instant.parse("2026-09-23T12:00:00Z")

  test("creates normalized project and environment with one transaction each") {
    val fixture = new WorkspaceFixture
    val project = fixture.post(fixture.projectPath(orgA), Json.obj(
      "code" -> Json.fromString("  SvinPeak "), "name" -> Json.fromString(" Svin Peak "),
      "description" -> Json.fromString("  Demo  ")))
    assertEquals(project._1, Status.Created)
    assertEquals(project._2.hcursor.get[String]("code"), Right("SvinPeak"))
    assertEquals(project._2.hcursor.get[String]("name"), Right("Svin Peak"))
    assertEquals(project._2.hcursor.get[String]("description"), Right("Demo"))
    assertEquals(project._2.hcursor.get[String]("organizationId"), Right(orgA.toString))
    val projectId = UUID.fromString(project._2.hcursor.get[String]("id").toOption.get)
    val environment = fixture.post(fixture.environmentPath(orgA, projectId), Json.obj(
      "code" -> Json.fromString(" prod "), "name" -> Json.fromString(" Production "),
      "kind" -> Json.fromString("PROD")))
    assertEquals(environment._1, Status.Created)
    assertEquals(environment._2.hcursor.get[String]("code"), Right("prod"))
    assertEquals(environment._2.hcursor.get[String]("kind"), Right("PROD"))
    assertEquals(environment._2.hcursor.get[String]("projectId"), Right(projectId.toString))
    assertEquals(fixture.calls, 2)
  }

  test("rejects case-insensitive duplicates within scope but allows same codes in another scope") {
    val fixture = new WorkspaceFixture
    val first = fixture.post(fixture.projectPath(orgA), fixture.projectBody("alpha"))
    val firstId = UUID.fromString(first._2.hcursor.get[String]("id").toOption.get)
    val duplicate = fixture.post(fixture.projectPath(orgA), fixture.projectBody("ALPHA"))
    assertEquals(duplicate._1, Status.Conflict)
    assertEquals(duplicate._2.hcursor.get[String]("code"), Right("PROJECT_CODE_ALREADY_EXISTS"))
    assertEquals(fixture.post(fixture.projectPath(orgB), fixture.projectBody("ALPHA"))._1, Status.Created)
    val second = fixture.post(fixture.projectPath(orgA), fixture.projectBody("beta"))
    val secondId = UUID.fromString(second._2.hcursor.get[String]("id").toOption.get)
    assertEquals(fixture.post(fixture.environmentPath(orgA, firstId), fixture.environmentBody("prod"))._1, Status.Created)
    val environmentDuplicate = fixture.post(fixture.environmentPath(orgA, firstId), fixture.environmentBody("PROD"))
    assertEquals(environmentDuplicate._1, Status.Conflict)
    assertEquals(environmentDuplicate._2.hcursor.get[String]("code"), Right("ENVIRONMENT_CODE_ALREADY_EXISTS"))
    assertEquals(fixture.post(fixture.environmentPath(orgA, secondId), fixture.environmentBody("PROD"))._1, Status.Created)
  }

  test("validates payload and path and does not expose foreign project") {
    val fixture = new WorkspaceFixture
    val foreign = fixture.post(fixture.projectPath(orgB), fixture.projectBody("foreign"))
    val foreignId = UUID.fromString(foreign._2.hcursor.get[String]("id").toOption.get)
    assertEquals(fixture.post(fixture.environmentPath(orgA, foreignId), fixture.environmentBody("prod"))._2
      .hcursor.get[String]("code"), Right("PROJECT_NOT_FOUND"))
    assertEquals(fixture.post(fixture.projectPath(UUID.randomUUID()), fixture.projectBody("new"))._2
      .hcursor.get[String]("code"), Right("ORGANIZATION_NOT_FOUND"))
    assertEquals(fixture.post(fixture.projectPath(orgA), fixture.projectBody("  "))._1, Status.BadRequest)
    assertEquals(fixture.post(fixture.environmentPath(orgB, foreignId), Json.obj(
      "code" -> Json.fromString("prod"), "name" -> Json.fromString("Production"),
      "kind" -> Json.fromString("WRONG")))._1, Status.BadRequest)
    assertEquals(fixture.post("/api/v1/organizations/nope/projects", fixture.projectBody("new"))._1, Status.BadRequest)
    assertEquals(fixture.post(fixture.projectPath(orgA), Json.obj())._1, Status.BadRequest)
  }

  test("rejects blank and oversized project and environment fields") {
    val fixture = new WorkspaceFixture
    val project = fixture.post(fixture.projectPath(orgA), fixture.projectBody("valid"))
    val projectId = UUID.fromString(project._2.hcursor.get[String]("id").toOption.get)
    val invalidFields = List(("code", " "), ("name", " "),
      ("code", "x" * 65), ("name", "x" * 256))
    for ((field, value) <- invalidFields) {
      val projectBody = Json.obj("code" -> Json.fromString("fresh"), "name" -> Json.fromString("Name"))
        .mapObject(_.add(field, Json.fromString(value)))
      val environmentBody = fixture.environmentBody("new").mapObject(_.add(field, Json.fromString(value)))
      for ((path, body) <- List(fixture.projectPath(orgA) -> projectBody,
        fixture.environmentPath(orgA, projectId) -> environmentBody)) {
        val result = fixture.post(path, body)
        assertEquals(result._1, Status.BadRequest)
        assertEquals(result._2.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
      }
    }
  }

  private final class WorkspaceFixture {
    private var projects = List.empty[Project]
    private var environments = List.empty[Environment]
    var calls = 0
    private val organizations = new OrganizationRepository[IO] {
      override def findActiveById(id: UUID): IO[Option[Organization]] = IO.pure(
        List(orgA, orgB).find(_ == id).map(value => Organization(value, "org", "Organization", true, now, now)))
    }
    private val projectRepository = new ProjectRepository[IO] {
      override def tryCreate(project: Project): IO[Boolean] = IO {
        if (projects.exists(value => value.organizationId == project.organizationId &&
          value.code.equalsIgnoreCase(project.code))) false
        else { projects = project :: projects; true }
      }
      override def findActiveByOrganization(organizationId: UUID): IO[List[Project]] =
        IO.pure(projects.filter(_.organizationId == organizationId))
      override def findActiveById(organizationId: UUID, projectId: UUID): IO[Option[Project]] =
        IO.pure(projects.find(value => value.organizationId == organizationId && value.id == projectId))
    }
    private val environmentRepository = new EnvironmentRepository[IO] {
      override def tryCreate(environment: Environment): IO[Boolean] = IO {
        if (environments.exists(value => value.organizationId == environment.organizationId &&
          value.projectId == environment.projectId && value.code.equalsIgnoreCase(environment.code))) false
        else { environments = environment :: environments; true }
      }
      override def findActiveByProject(organizationId: UUID, projectId: UUID): IO[List[Environment]] =
        IO.pure(environments.filter(value => value.organizationId == organizationId && value.projectId == projectId))
    }
    private val ids = new IdGenerator[IO] { override def nextId: IO[UUID] = IO(UUID.randomUUID()) }
    private val time = new TimeProvider[IO] { override def now: IO[Instant] = IO.pure(WorkspaceMutationRoutesSpec.this.now) }
    private val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] = IO { calls += 1 } *> program
    }
    private val routes = new WorkspaceMutationRoutes[IO](new CreateProject(organizations, projectRepository, ids, time),
      new CreateEnvironment(projectRepository, environmentRepository, ids, time), runner).routes.orNotFound

    def projectPath(org: UUID): String = s"/api/v1/organizations/$org/projects"
    def environmentPath(org: UUID, project: UUID): String = s"${projectPath(org)}/$project/environments"
    def projectBody(code: String): Json = Json.obj("code" -> Json.fromString(code),
      "name" -> Json.fromString("Name"))
    def environmentBody(code: String): Json = Json.obj("code" -> Json.fromString(code),
      "name" -> Json.fromString("Production"), "kind" -> Json.fromString("PROD"))
    def post(path: String, body: Json): (Status, Json) = {
      val response = routes.run(Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(body)).unsafeRunSync()
      response.status -> response.as[Json].unsafeRunSync()
    }
  }
}
