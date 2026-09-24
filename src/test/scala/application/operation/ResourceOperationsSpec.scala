package ru.bitec.app.ops
package application.operation

import application.auth.ActorContext
import application.port._
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.operation._
import munit.FunSuite
import support.{FailingAuditEventRepository, RecordingAuditEventRepository, TestAuditRecorder}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID

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

  private final case class OpFixture(failure: Option[ResourceOperationFailure] = None,
    targetCount: Int = 1, resourceType: String = "CONTAINER", failingAudit: Boolean = false) {
    val repository = new MemoryExecutions
    val target = ResourceOperationTarget(Connection(UUID.randomUUID(), org, ConnectionScope.Organization,
      "SSH", "ssh", "SSH", ConnectionConfig(Map.empty), None, isActive = true, now, now),
      "CONTAINER", "73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d")
    val query = new ResourceOperationTargetQuery[IO] {
      def find(organizationId: UUID, resourceId: UUID): IO[Option[ResourceOperationTargetProjection]] =
        IO.pure(Some(ResourceOperationTargetProjection(resourceId, resourceType, resourceActive = true,
          List.fill(targetCount)(target))))
    }
    val auditRepository: Option[RecordingAuditEventRepository] = if (failingAudit) None else Some(new RecordingAuditEventRepository)
    val auditRecorder = TestAuditRecorder(auditRepository.getOrElse(new FailingAuditEventRepository))
    val preparation = new ResourceOperationPreparation[IO](query, repository, new FixedId, new FixedTime, auditRecorder)
    val executor = new RecordingExecutor(failure)
    val service = new ExecuteResourceOperation[IO](preparation, repository, executor,
      new TransactionRunner[IO, IO] { def run[A](program: IO[A]): IO[A] = program }, new FixedTime,
      Slf4jLogger.getLoggerFromName[IO]("test.operations"))
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
    def tryCreateRunning(value: OperationExecution) = IO {
      if (running) false else { running = true; values ::= value; true }
    }
    def recoverStaleRunning(o: UUID, r: UUID, b: Instant, at: Instant, c: String, m: String) = IO.pure(List.empty[UUID])
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
