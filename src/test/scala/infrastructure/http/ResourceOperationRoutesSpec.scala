package ru.bitec.app.ops
package infrastructure.http

import application.operation.{ExecuteResourceOperation, ListResourceOperationExecutions, ResourceOperationPreparation}
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.operation._
import domain.auth.OrganizationRole
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import support.{AuthorizationFixtures, TestAuditRecorder}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID

final class ResourceOperationRoutesSpec extends FunSuite {
  private val org = UUID.randomUUID(); private val resource = UUID.randomUUID(); private val now = Instant.EPOCH

  test("owner executes a typed operation, invalid code is 400, and member is forbidden") {
    val repo = new Repo
    val query = new Query
    val preparation = new ResourceOperationPreparation[IO](query, repo, new Ids, new Clock,
      TestAuditRecorder.recording._2, FixedBudget)
    val execute = new ExecuteResourceOperation[IO](preparation, repo,
      new ResourceOperationExecutor[IO] { def execute(t: ResourceOperationTarget, o: ResourceOperationCode) = IO.unit },
      Runner, new Clock, Slf4jLogger.getLoggerFromName[IO]("test.operations"))
    val list = new ListResourceOperationExecutions[IO](query, repo)
    val app = new ResourceOperationRoutes(preparation, execute, list, Runner, AuthorizationFixtures.authorization).routes.orNotFound
    def request(code: String, role: OrganizationRole) = app.run(AuthorizationFixtures.as(Request[IO](Method.POST,
      Uri.unsafeFromString(s"/api/v1/organizations/$org/resources/$resource/operations/$code/executions")), org, role))

    assertEquals(request("CONTAINER_START", OrganizationRole.Owner).unsafeRunSync().status, Status.Created)
    // A path segment is not a command: an unknown code stops at the typed parser.
    assertEquals(request("anything", OrganizationRole.Owner).unsafeRunSync().status, Status.BadRequest)
    assertEquals(request("CONTAINER_STOP", OrganizationRole.Member).unsafeRunSync().status, Status.Forbidden)

    def read(path: String, role: OrganizationRole) = app.run(AuthorizationFixtures.as(Request[IO](Method.GET,
      Uri.unsafeFromString(s"/api/v1/organizations/$org/resources/$resource/$path")), org, role))
        .unsafeRunSync()

    // Reading what is available and what already ran needs membership only.
    assertEquals(read("operations", OrganizationRole.Member).status, Status.Ok)
    assertEquals(read("operation-executions", OrganizationRole.Member).status, Status.Ok)
    assertEquals(read("operation-executions?limit=0", OrganizationRole.Owner).status, Status.BadRequest)
    assertEquals(read("operation-executions?limit=500", OrganizationRole.Owner).status, Status.BadRequest)
    assertEquals(read(s"operation-executions?beforeStartedAt=$now", OrganizationRole.Owner).status,
      Status.BadRequest)
  }

  private object Runner extends TransactionRunner[IO, IO] { def run[A](program: IO[A]) = program }
  private object FixedBudget extends ResourceOperationBudget {
    def maxAttemptDuration(target: ResourceOperationTarget) = scala.concurrent.duration.Duration.Zero
  }
  private final class Ids extends IdGenerator[IO] { def nextId = IO.pure(UUID.randomUUID()) }
  private final class Clock extends TimeProvider[IO] { def now = IO.pure(ResourceOperationRoutesSpec.this.now) }
  private final class Query extends ResourceOperationTargetQuery[IO] {
    private val connection = Connection(UUID.randomUUID(), org, ConnectionScope.Organization, "SSH", "ssh", "SSH",
      ConnectionConfig(Map.empty), None, isActive = true, now, now)
    def find(o: UUID, r: UUID) = IO.pure(Some(ResourceOperationTargetProjection(r, "CONTAINER", resourceActive = true,
      List(ResourceOperationTarget(connection, "CONTAINER", "aaaaaaaaaaaa")))))
  }
  private final class Repo extends OperationExecutionRepository[IO] {
    private var values = List.empty[OperationExecution]
    def tryCreateRunning(v: OperationExecution) = IO { values ::= v; true }
    def recoverStaleRunning(o: UUID, r: UUID, at: Instant, c: String, m: String) = IO.pure(List.empty[UUID])
    def markSucceeded(o: UUID, id: UUID, at: Instant) = IO { values = values.map(v => if(v.id == id) v.copy(status = OperationExecutionStatus.Succeeded, finishedAt = Some(at)) else v); true }
    def markFailed(o: UUID, id: UUID, at: Instant, c: String, m: String) = IO.pure(true)
    def findById(o: UUID, r: UUID, id: UUID) = IO.pure(values.find(_.id == id))
    def listByResource(o: UUID, r: UUID, c: Option[OperationExecutionCursor], l: Int) = IO.pure(values.take(l))
  }
}
