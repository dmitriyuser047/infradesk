package ru.bitec.app.ops
package application.operation

import application.auth.ActorContext
import application.port._
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionScope, SshConnectionSettings}
import domain.operation._
import munit.FunSuite
import support.{FailingAuditEventRepository, RecordingAuditEventRepository, TestAuditRecorder}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

final class ResourceOperationsSpec extends FunSuite {
  private val org = UUID.randomUUID()
  private val resource = UUID.randomUUID()
  private val actor = ActorContext(UUID.randomUUID(), org)
  private val now = Instant.parse("2026-09-24T10:00:00Z")

  test("successful execution is prepared, audited and completed around one external attempt") {
    val fixture = OpFixture()
    val result = fixture.service.execute(actor, resource, ResourceOperationCode.ContainerStart).unsafeRunSync()
    assertEquals(result.status, OperationExecutionStatus.Succeeded)
    assertEquals(fixture.executor.calls.unsafeRunSync(), 1)
    assertEquals(fixture.auditRepository.toList.flatMap(_.recorded).map(_.action.code), List("CONTAINER_START_REQUESTED"))
    assertEquals(fixture.repository.values.head.status, OperationExecutionStatus.Succeeded)
  }

  test("clear remote failure becomes FAILED with safe metadata and is not retried") {
    val fixture = OpFixture(Some(ResourceOperationFailure("DOCKER_OPERATION_FAILED", "Docker container stop failed")))
    val result = fixture.service.execute(actor, resource, ResourceOperationCode.ContainerStop).unsafeRunSync()
    assertEquals(result.status, OperationExecutionStatus.Failed)
    assertEquals(result.errorCode, Some("DOCKER_OPERATION_FAILED"))
    assertEquals(fixture.executor.calls.unsafeRunSync(), 1)
  }

  test("duplicate running and ambiguous or unsupported targets never perform IO") {
    val duplicate = OpFixture()
    duplicate.repository.running = true
    intercept[OperationAlreadyRunning] {
      duplicate.service.execute(actor, resource, ResourceOperationCode.ContainerStart).unsafeRunSync()
    }
    assertEquals(duplicate.executor.calls.unsafeRunSync(), 0)

    List(OpFixture(targetCount = 2), OpFixture(resourceType = "NODE"), OpFixture(targetCount = 0)).foreach { fixture =>
      intercept[ResourceOperationError] {
        fixture.service.execute(actor, resource, ResourceOperationCode.ContainerStart).unsafeRunSync()
      }
      assertEquals(fixture.executor.calls.unsafeRunSync(), 0)
    }
  }

  test("audit failure aborts before external IO") {
    val fixture = OpFixture(failingAudit = true)
    intercept[IllegalStateException] {
      fixture.service.execute(actor, resource, ResourceOperationCode.ContainerRestart).unsafeRunSync()
    }
    assertEquals(fixture.executor.calls.unsafeRunSync(), 0)
  }

  test("an execution stores the deadline of the conditions it started under") {
    val fixture = OpFixture(commandTimeoutSeconds = 1200)

    fixture.service.execute(actor, resource, ResourceOperationCode.ContainerStart).unsafeRunSync()

    // connect 10s + command 1200s + one minute of margin.
    assertEquals(fixture.repository.values.head.recoverAfterAt, now.plusSeconds(10 + 1200 + 60))

    // A fast connection still keeps the ten-minute floor.
    val fast = OpFixture(commandTimeoutSeconds = 30)
    fast.service.execute(actor, resource, ResourceOperationCode.ContainerStart).unsafeRunSync()
    assertEquals(fast.repository.values.head.recoverAfterAt, now.plusSeconds(600))
  }

  test("a running operation is never abandoned while its own command can still be in flight") {
    // The running operation was started when the connection allowed twenty-minute commands; the
    // connection has since been reconfigured to thirty seconds.
    val fixture = OpFixture(commandTimeoutSeconds = 30)
    fixture.repository.start(startedAt = now.minusSeconds(11 * 60), recoverAfterAt = now.plusSeconds(10 * 60))

    intercept[OperationAlreadyRunning] {
      fixture.service.execute(actor, resource, ResourceOperationCode.ContainerRestart).unsafeRunSync()
    }

    assertEquals(fixture.repository.recovered, List.empty)
    assertEquals(fixture.repository.values.map(_.status), List(OperationExecutionStatus.Running))
    // The decisive part: no second remote command next to the first one.
    assertEquals(fixture.executor.calls.unsafeRunSync(), 0)
    assertEquals(fixture.auditRepository.toList.flatMap(_.recorded), List.empty)
  }

  test("a running operation past its own deadline becomes unknown and releases the resource") {
    val fixture = OpFixture(commandTimeoutSeconds = 30)
    fixture.repository.start(startedAt = now.minusSeconds(22 * 60), recoverAfterAt = now.minusSeconds(60))

    val result = fixture.service.execute(actor, resource, ResourceOperationCode.ContainerRestart)
      .unsafeRunSync()

    assertEquals(fixture.repository.recovered.size, 1)
    assertEquals(result.status, OperationExecutionStatus.Succeeded)
    assertEquals(fixture.executor.calls.unsafeRunSync(), 1)
  }

  test("the stale horizon never drops below its floor and always covers the transport budget") {
    assertEquals(ResourceOperationPolicy.staleAfter(30.seconds), 10.minutes)
    assertEquals(ResourceOperationPolicy.staleAfter(Duration.Zero), 10.minutes)
    assertEquals(ResourceOperationPolicy.staleAfter(20.minutes), 21.minutes)
    assert(ResourceOperationPolicy.staleAfter(9.minutes + 30.seconds) >= 10.minutes)
  }

  private final case class OpFixture(failure: Option[ResourceOperationFailure] = None,
    targetCount: Int = 1, resourceType: String = "CONTAINER", failingAudit: Boolean = false,
    commandTimeoutSeconds: Int = 30) {
    val repository = new MemoryExecutions
    private val sshConfig = SshConnectionSettings.toConnectionConfig(
      SshConnectionSettings("node.example.test", 22, "root", None, 10, commandTimeoutSeconds))
    val target = ResourceOperationTarget(Connection(UUID.randomUUID(), org, ConnectionScope.Organization,
      "SSH", "ssh", "SSH", sshConfig, None, isActive = true, now, now),
      "CONTAINER", "73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d")
    val query = new ResourceOperationTargetQuery[IO] {
      def find(organizationId: UUID, resourceId: UUID): IO[Option[ResourceOperationTargetProjection]] =
        IO.pure(Some(ResourceOperationTargetProjection(resourceId, resourceType, resourceActive = true,
          List.fill(targetCount)(target))))
    }
    val auditRepository: Option[RecordingAuditEventRepository] = if (failingAudit) None else Some(new RecordingAuditEventRepository)
    val auditRecorder = TestAuditRecorder(auditRepository.getOrElse(new FailingAuditEventRepository))
    val budget = new SshTimeoutBudget
    val preparation = new ResourceOperationPreparation[IO](query, repository, new FixedId,
      new FixedTime, auditRecorder, budget)
    val executor = new RecordingExecutor(failure)
    val service = new ExecuteResourceOperation[IO](preparation, repository, executor,
      new TransactionRunner[IO, IO] { def run[A](program: IO[A]): IO[A] = program }, new FixedTime,
      Slf4jLogger.getLoggerFromName[IO]("test.operations"))
  }

  /** Mirrors the SSH adapter: the budget of a target is what its own connection allows. */
  private final class SshTimeoutBudget extends ResourceOperationBudget {
    def maxAttemptDuration(target: ResourceOperationTarget): FiniteDuration =
      SshConnectionSettings.from(target.connection.config)
        .map(value => value.connectTimeoutSeconds.seconds + value.commandTimeoutSeconds.seconds)
        .getOrElse(Duration.Zero)
  }

  private final class FixedId extends IdGenerator[IO] { def nextId = IO.pure(UUID.randomUUID()) }
  private final class FixedTime extends TimeProvider[IO] { def now = IO.pure(ResourceOperationsSpec.this.now) }
  private final class RecordingExecutor(failure: Option[ResourceOperationFailure]) extends ResourceOperationExecutor[IO] {
    private val count = Ref.of[IO, Int](0).unsafeRunSync()
    def calls: IO[Int] = count.get
    def execute(target: ResourceOperationTarget, operation: ResourceOperationCode): IO[Unit] =
      count.update(_ + 1) *> failure.fold(IO.unit)(IO.raiseError)
  }
  private final class MemoryExecutions extends OperationExecutionRepository[IO] {
    var values = List.empty[OperationExecution]
    var running = false
    var recovered = List.empty[UUID]

    /** Seeds a running execution with the deadline a previous request would have stored. */
    def start(startedAt: Instant, recoverAfterAt: Instant): Unit = {
      running = true
      values ::= OperationExecution(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), ResourceOperationCode.ContainerRestart, UUID.randomUUID(), "CONTAINER",
        "73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d",
        OperationExecutionStatus.Running, startedAt, recoverAfterAt, None, None, None, startedAt,
        startedAt)
    }

    def tryCreateRunning(value: OperationExecution) = IO {
      if (running) false else { running = true; values ::= value; true }
    }
    def recoverStaleRunning(o: UUID, r: UUID, at: Instant, c: String, m: String) = IO {
      val stale = values.filter(value =>
        value.status == OperationExecutionStatus.Running && !value.recoverAfterAt.isAfter(at))
      values = values.map(value =>
        if (stale.exists(_.id == value.id)) value.copy(status = OperationExecutionStatus.Unknown,
          finishedAt = Some(at), errorCode = Some(c), errorMessage = Some(m))
        else value)
      if (stale.nonEmpty) running = false
      recovered = recovered ++ stale.map(_.id)
      stale.map(_.id)
    }
    def markSucceeded(o: UUID, id: UUID, at: Instant) = finish(id, OperationExecutionStatus.Succeeded, at, None, None)
    def markFailed(o: UUID, id: UUID, at: Instant, c: String, m: String) = finish(id, OperationExecutionStatus.Failed, at, Some(c), Some(m))
    private def finish(id: UUID, state: OperationExecutionStatus, at: Instant, c: Option[String], m: Option[String]) = IO {
      val found = values.exists(_.id == id) && running
      if (found) { values = values.map(v => if (v.id == id) v.copy(status = state, finishedAt = Some(at), errorCode = c, errorMessage = m) else v); running = false }
      found
    }
    def findById(o: UUID, r: UUID, id: UUID) = IO.pure(values.find(_.id == id))
    def listByResource(o: UUID, r: UUID, c: Option[OperationExecutionCursor], l: Int) = IO.pure(values.take(l))
  }
}
