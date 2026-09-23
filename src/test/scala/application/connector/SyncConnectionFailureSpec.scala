package ru.bitec.app.ops
package application.connector

import application.discovery.SyncDiscoveredSnapshot
import application.connection.RunManualConnectionSync
import application.port.{ConnectionSyncResult, ConnectionSyncRunner, IdGenerator, ResourceConnector, ResourceConnectorFailure, ResourceConnectorFailureCode, ResourceConnectorResult, SyncSessionRepository, TimeProvider, TransactionRunner}
import application.resource.RecordResourceObservations
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
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
    val fresh = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(1), None, SyncSessionStatus.Running)
    f.repository.current = Some(fresh)
    intercept[SyncAlreadyRunning] { f.sync.execute(connection).unsafeRunSync() }
    assertEquals(f.discoverCalls, 0)
    assertEquals(f.repository.current, Some(fresh))
  }

  test("RUNNING session exactly at the TTL boundary is not stale") {
    val f = new FailureFixture
    val boundary = SyncSession(UUID.randomUUID(), org, connectionId,
      at.minusSeconds(SyncSessionPolicy.StaleAfterSeconds), None, SyncSessionStatus.Running)
    f.repository.current = Some(boundary)

    intercept[SyncAlreadyRunning] { f.sync.execute(connection).unsafeRunSync() }

    assertEquals(f.repository.values, List(boundary))
    assertEquals(f.discoverCalls, 0)
  }

  test("stale RUNNING becomes FAILED before a new exact session starts outside transaction") {
    val f = new FailureFixture
    val stale = SyncSession(UUID.randomUUID(), org, connectionId,
      at.minusSeconds(SyncSessionPolicy.StaleAfterSeconds + 1), None, SyncSessionStatus.Running)
    f.repository.current = Some(stale)

    val error = intercept[ConnectionSyncExecutionFailed] { f.sync.execute(connection).unsafeRunSync() }

    assertEquals(error.sessionId, sessionId)
    assertEquals(f.discoverCalls, 1)
    assertEquals(f.discoveredInTransaction, false)
    assertEquals(f.repository.values.map(_.id), List(sessionId, stale.id))
    assertEquals(f.repository.findRecentByConnection(org, connectionId, 10).unsafeRunSync().map(_.id),
      List(sessionId, stale.id))
    val old = f.repository.values.find(_.id == stale.id).get
    assertEquals(old.status, SyncSessionStatus.Failed)
    assertEquals(old.finishedAt, Some(at))
    assertEquals(old.errorCode, Some(SyncFailure.Stale.code))
    assertEquals(old.errorMessage, Some(SyncFailure.Stale.message))
    assertEquals(f.repository.values.head.status, SyncSessionStatus.Failed)
  }

  test("manual sync after stale recovery returns the new session, not the recovered one") {
    val f = new FailureFixture
    val oldId = UUID.randomUUID()
    f.repository.current = Some(SyncSession(oldId, org, connectionId,
      at.minusSeconds(SyncSessionPolicy.StaleAfterSeconds + 1), None, SyncSessionStatus.Running))
    val sharedRunner = new ConnectionSyncRunner[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
        f.sync.execute(connection)
    }

    val result = new RunManualConnectionSync[IO](sharedRunner, f.repository, f.runner)
      .execute(org, connectionId).unsafeRunSync()

    assertEquals(result.id, sessionId)
    assertEquals(result.status, SyncSessionStatus.Failed)
    assertEquals(f.repository.values.find(_.id == oldId).flatMap(_.errorCode), Some(SyncFailure.Stale.code))
  }

  test("completed and failed history is never changed by recovery") {
    val f = new FailureFixture
    val completed = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(2000),
      Some(at.minusSeconds(1999)), SyncSessionStatus.Completed)
    val failed = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(1900),
      Some(at.minusSeconds(1899)), SyncSessionStatus.Failed,
      Some(SyncFailure.Generic.code), Some(SyncFailure.Generic.message))
    f.repository.values = List(completed, failed)

    intercept[ConnectionSyncExecutionFailed] { f.sync.execute(connection).unsafeRunSync() }

    assertEquals(f.repository.values.find(_.id == completed.id), Some(completed))
    assertEquals(f.repository.values.find(_.id == failed.id), Some(failed))
    assertEquals(f.discoverCalls, 1)
  }

  private final class FailureFixture(failure: Throwable = new IllegalStateException("raw password in SSH exception")) {
    var discoverCalls = 0
    var discoveredInTransaction = false
    var inTransaction = false
    val connector = new ResourceConnector[IO] {
      override val connectorType = "FAILING"
      override def discover(value: Connection): IO[ResourceConnectorResult] = IO {
        discoverCalls += 1
        discoveredInTransaction ||= inTransaction
        throw failure
      }
    }
    val repository = new Sessions
    val runner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] =
        IO { inTransaction = true } *> program.guarantee(IO { inTransaction = false })
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
      null.asInstanceOf[RecordResourceObservations[IO]],
      _root_.org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("test.sync-failure"))
  }

  private final class Sessions extends SyncSessionRepository[IO] {
    var values: List[SyncSession] = Nil
    def current: Option[SyncSession] = values.headOption
    def current_=(value: Option[SyncSession]): Unit = { values = value.toList }
    override def findLatestByConnection(organizationId: UUID, forConnection: UUID): IO[Option[SyncSession]] =
      IO.pure(values.filter(s => s.organizationId == organizationId && s.connectionId == forConnection)
        .sortBy(_.startedAt).lastOption)
    override def findRecentByConnection(organizationId: UUID, forConnection: UUID, limit: Int): IO[List[SyncSession]] =
      IO.pure(values.filter(s => s.organizationId == organizationId && s.connectionId == forConnection)
        .sortBy(_.startedAt).reverse.take(limit))
    override def findById(organizationId: UUID, forConnection: UUID, id: UUID): IO[Option[SyncSession]] =
      IO.pure(values.find(s => s.organizationId == organizationId && s.connectionId == forConnection && s.id == id))
    override def create(value: SyncSession): IO[Unit] = IO { values = value :: values }
    override def tryCreate(value: SyncSession): IO[Boolean] = IO {
      if (values.exists(s => s.organizationId == value.organizationId && s.connectionId == value.connectionId &&
        s.status == SyncSessionStatus.Running)) false
      else { values = value :: values; true }
    }
    override def recoverStaleAndTryCreate(value: SyncSession, staleBefore: Instant,
                                          recoveredAt: Instant, errorCode: String, errorMessage: String): IO[Boolean] = IO {
      values = values.map { existing =>
        if (existing.organizationId == value.organizationId && existing.connectionId == value.connectionId &&
          existing.status == SyncSessionStatus.Running && existing.startedAt.isBefore(staleBefore))
          existing.copy(status = SyncSessionStatus.Failed, finishedAt = Some(recoveredAt),
            errorCode = Some(errorCode), errorMessage = Some(errorMessage))
        else existing
      }
      if (values.exists(s => s.organizationId == value.organizationId && s.connectionId == value.connectionId &&
        s.status == SyncSessionStatus.Running)) false
      else { values = value :: values; true }
    }
    override def complete(organizationId: UUID, id: UUID, finishedAt: Instant): IO[Unit] = IO.unit
    override def fail(organizationId: UUID, id: UUID, finishedAt: Instant, code: String, message: String): IO[Unit] = IO {
      values = values.map { session =>
        if (session.organizationId == organizationId && session.id == id && session.status == SyncSessionStatus.Running)
          session.copy(status = SyncSessionStatus.Failed, finishedAt = Some(finishedAt),
            errorCode = Some(code), errorMessage = Some(message))
        else session
      }
    }
  }
}
