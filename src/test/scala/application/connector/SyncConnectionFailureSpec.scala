package ru.bitec.app.ops
package application.connector

import application.discovery.SyncDiscoveredSnapshot
import application.port.{IdGenerator, ResourceConnector, ResourceConnectorFailure, ResourceConnectorFailureCode, ResourceConnectorResult, SyncSessionRepository, TimeProvider, TransactionRunner}
import application.resource.RecordResourceObservations
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.sync.{SyncSession, SyncSessionStatus}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class SyncConnectionFailureSpec extends FunSuite {
  private val org = UUID.randomUUID()
  private val connectionId = UUID.randomUUID()
  private val sessionId = UUID.randomUUID()
  private val at = Instant.parse("2026-09-23T10:00:00Z")
  private val connection = Connection(connectionId, org, ConnectionScope.Organization, "FAILING", "fail", "Failing",
    ConnectionConfig(Map.empty), None, true, at, at)

  test("discovery failure persists only safe metadata and preserves exact session ID") {
    val raw = new IllegalStateException("password=super-secret host=10.0.0.1")
    val f = new FailureFixture(raw)
    val error = intercept[ConnectionSyncExecutionFailed] { f.sync.execute(connection).unsafeRunSync() }
    assertEquals(error.sessionId, sessionId)
    assertEquals(error.getCause, raw)
    assertEquals(f.repository.current.map(_.status), Some(SyncSessionStatus.Failed))
    assertEquals(f.repository.current.flatMap(_.finishedAt), Some(at.plusSeconds(1)))
    assertEquals(f.repository.current.flatMap(_.errorCode), Some("SYNC_FAILED"))
    assertEquals(f.repository.current.flatMap(_.errorMessage), Some("Synchronization failed"))
    assert(!f.repository.current.toString.contains("super-secret"))
    assert(!f.repository.current.toString.contains("10.0.0.1"))
    assertEquals(f.discoverCalls, 1)
  }

  test("typed connector failure persists safe code and message while preserving original cause") {
    val raw = new IllegalStateException("password=super-secret host=10.0.0.1")
    val failure = ResourceConnectorFailure(ResourceConnectorFailureCode.SshConnectTimeout,
      "SSH connection timed out", raw)
    val f = new FailureFixture(failure)

    val error = intercept[ConnectionSyncExecutionFailed] { f.sync.execute(connection).unsafeRunSync() }

    assertEquals(error.sessionId, sessionId)
    assertEquals(error.getCause, failure)
    assertEquals(failure.getCause, raw)
    assertEquals(f.repository.current.map(_.status), Some(SyncSessionStatus.Failed))
    assertEquals(f.repository.current.flatMap(_.finishedAt), Some(at.plusSeconds(1)))
    assertEquals(f.repository.current.flatMap(_.errorCode), Some(ResourceConnectorFailureCode.SshConnectTimeout))
    assertEquals(f.repository.current.flatMap(_.errorMessage), Some("SSH connection timed out"))
    assert(!f.repository.current.toString.contains("super-secret"))
    assert(!f.repository.current.toString.contains("10.0.0.1"))
  }

  test("already RUNNING session rejects a second run before discovery") {
    val f = new FailureFixture
    f.repository.current = Some(SyncSession(UUID.randomUUID(), org, connectionId, at, None, SyncSessionStatus.Running))
    intercept[SyncAlreadyRunning] { f.sync.execute(connection).unsafeRunSync() }
    assertEquals(f.discoverCalls, 0)
    assertEquals(f.repository.current.map(_.status), Some(SyncSessionStatus.Running))
  }

  private final class FailureFixture(failure: Throwable = new IllegalStateException("raw password in SSH exception")) {
    var discoverCalls = 0
    val connector = new ResourceConnector[IO] {
      override val connectorType = "FAILING"
      override def discover(value: Connection): IO[ResourceConnectorResult] = IO {
        discoverCalls += 1
        throw failure
      }
    }
    val repository = new Sessions
    val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] = program
    }
    val ids = new IdGenerator[IO] { override def nextId: IO[UUID] = IO.pure(sessionId) }
    val clock = new TimeProvider[IO] {
      private var calls = 0
      override def now: IO[Instant] = IO {
        calls += 1
        if (calls == 1) at else at.plusSeconds(1)
      }
    }
    // Discovery fails before these collaborators are touched in this focused failure-path test.
    val sync = new SyncConnection[IO, IO](new ResourceConnectorRegistry(List(connector)),
      null.asInstanceOf[SyncDiscoveredSnapshot[IO]], null, repository, runner, ids, clock,
      null.asInstanceOf[RecordResourceObservations[IO]])
  }

  private final class Sessions extends SyncSessionRepository[IO] {
    var current: Option[SyncSession] = None
    override def findLatestByConnection(organizationId: UUID, forConnection: UUID): IO[Option[SyncSession]] = IO.pure(current)
    override def findRecentByConnection(organizationId: UUID, forConnection: UUID, limit: Int): IO[List[SyncSession]] =
      IO.pure(current.toList)
    override def findById(organizationId: UUID, forConnection: UUID, id: UUID): IO[Option[SyncSession]] =
      IO.pure(current.filter(s => s.organizationId == organizationId && s.connectionId == forConnection && s.id == id))
    override def create(value: SyncSession): IO[Unit] = IO { current = Some(value) }
    override def tryCreate(value: SyncSession): IO[Boolean] = IO {
      if (current.exists(_.status == SyncSessionStatus.Running)) false
      else { current = Some(value); true }
    }
    override def complete(organizationId: UUID, id: UUID, finishedAt: Instant): IO[Unit] = IO.unit
    override def fail(organizationId: UUID, id: UUID, finishedAt: Instant, code: String, message: String): IO[Unit] = IO {
      current = current.map(_.copy(status = SyncSessionStatus.Failed, finishedAt = Some(finishedAt),
        errorCode = Some(code), errorMessage = Some(message)))
    }
  }
}
