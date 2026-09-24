package ru.bitec.app.ops
package application.connector

import application.discovery.SyncDiscoveredSnapshot
import application.connection.RunManualConnectionSync
import application.connector.SyncSessionPolicy
import application.port.{SyncSessionClaim, ConnectionSyncResult, ConnectionSyncRunner, IdGenerator, ResourceConnector, ResourceConnectorFailure, ResourceConnectorFailureCode, ResourceConnectorResult, SyncSessionRepository, TimeProvider, TransactionRunner}
import application.resource.RecordResourceObservations
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.sync.{SyncSession, SyncSessionStatus}
import munit.FunSuite
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

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
    assertEquals(f.logger.throwableErrors, 0)
    assert(!f.logger.errors.mkString(" ").contains("super-secret"))
    assert(!f.logger.errors.mkString(" ").contains("10.0.0.1"))
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
    val fresh = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(1),
      at.plusSeconds(900), None, SyncSessionStatus.Running)
    f.repository.current = Some(fresh)
    intercept[SyncAlreadyRunning] { f.sync.execute(connection).unsafeRunSync() }
    assertEquals(f.discoverCalls, 0)
    assertEquals(f.repository.current, Some(fresh))
    assertEquals(f.historyEvents.recorded, List.empty[domain.history.HistoryEvent])
  }

  test("RUNNING session exactly at the TTL boundary is not stale") {
    val f = new FailureFixture
    // Its deadline falls exactly now: the boundary belongs to the session that still holds it.
    val boundary = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(900),
      at, None, SyncSessionStatus.Running)
    f.repository.current = Some(boundary)

    intercept[SyncAlreadyRunning] { f.sync.execute(connection).unsafeRunSync() }

    assertEquals(f.repository.values, List(boundary))
    assertEquals(f.discoverCalls, 0)
    // Nothing was retired, so the timeline stays silent about a session that is still running.
    assertEquals(f.historyEvents.recorded, List.empty[domain.history.HistoryEvent])
  }

  test("a long sync keeps its own deadline after the connection is reconfigured") {
    // A started at 14:00 when the connection allowed twenty-minute commands, so its own budget
    // reaches far beyond the former fixed fifteen minutes.
    val f = new FailureFixture
    val longRunning = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(16 * 60),
      at.plusSeconds(10 * 60), None, SyncSessionStatus.Running)
    f.repository.current = Some(longRunning)

    intercept[SyncAlreadyRunning] { f.sync.execute(connection).unsafeRunSync() }

    // The connection may have been reconfigured to thirty-second commands since; the deadline of
    // the attempt in flight does not move, so nothing is retired and nothing is started.
    assertEquals(f.repository.values, List(longRunning))
    assertEquals(f.discoverCalls, 0)
    assertEquals(f.historyEvents.recorded, List.empty[domain.history.HistoryEvent])
  }

  test("a new session carries the deadline of the budget it was started with") {
    val f = new FailureFixture(budget = 20.minutes)

    intercept[ConnectionSyncExecutionFailed] { f.sync.execute(connection).unsafeRunSync() }

    val created = f.repository.values.find(_.id == sessionId).get
    // Twenty minutes of attempt plus the policy margin, measured from its own start.
    assertEquals(created.recoverAfterAt, created.startedAt.plusSeconds(21 * 60))
  }

  test("stale RUNNING becomes FAILED before a new exact session starts outside transaction") {
    val f = new FailureFixture
    val stale = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(901),
      at.minusSeconds(1), None, SyncSessionStatus.Running)
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
    // Retiring the abandoned session is a fact of its own, recorded in the transaction that
    // retired it and pointing at that session rather than the new one.
    val recovered = f.historyEvents.recorded.filter(_.syncSessionId.contains(stale.id))
    assertEquals(recovered.map(_.eventType.code), List("SYNC_FAILED"))
    assertEquals(recovered.map(_.source.code), List("SYSTEM"))
    assertEquals(recovered.map(_.connectionId), List(Some(connectionId)))
    assertEquals(recovered.map(_.occurredAt), List(at))
    // The run of this test then failed on its own, which is a second, separate fact.
    assertEquals(f.historyEvents.recorded.map(_.syncSessionId).distinct.size, 2)
  }

  test("manual sync after stale recovery returns the new session, not the recovered one") {
    val f = new FailureFixture
    val oldId = UUID.randomUUID()
    f.repository.current = Some(SyncSession(oldId, org, connectionId, at.minusSeconds(901),
      at.minusSeconds(1), None, SyncSessionStatus.Running))
    val sharedRunner = new ConnectionSyncRunner[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
        f.sync.execute(connection)
    }

    val result = new RunManualConnectionSync[IO](sharedRunner, f.repository, new support.FixedConnectionRepository(connection),
      support.TestAuditRecorder(new support.RecordingAuditEventRepository), f.runner)
      .execute(support.AuthorizationFixtures.actor(org), connectionId).unsafeRunSync()

    assertEquals(result.id, sessionId)
    assertEquals(result.status, SyncSessionStatus.Failed)
    assertEquals(f.repository.values.find(_.id == oldId).flatMap(_.errorCode), Some(SyncFailure.Stale.code))
  }

  test("completed and failed history is never changed by recovery") {
    val f = new FailureFixture
    val completed = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(2000),
      at.minusSeconds(1100), Some(at.minusSeconds(1999)), SyncSessionStatus.Completed)
    val failed = SyncSession(UUID.randomUUID(), org, connectionId, at.minusSeconds(1900),
      at.minusSeconds(1000), Some(at.minusSeconds(1899)), SyncSessionStatus.Failed,
      Some(SyncFailure.Generic.code), Some(SyncFailure.Generic.message))
    f.repository.values = List(completed, failed)

    intercept[ConnectionSyncExecutionFailed] { f.sync.execute(connection).unsafeRunSync() }

    assertEquals(f.repository.values.find(_.id == completed.id), Some(completed))
    assertEquals(f.repository.values.find(_.id == failed.id), Some(failed))
    assertEquals(f.discoverCalls, 1)
  }

  private final class FailureFixture(
    failure: Throwable = new IllegalStateException("raw password in SSH exception"),
    budget: FiniteDuration = Duration.Zero
  ) {
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
    val historyEvents = new support.RecordingHistoryEventRepository
    val history = support.TestHistoryRecorder(historyEvents)
    val logger = new RecordingLogger
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
      history,
      // Mirrors the SSH adapter without importing it: connect plus both sequential commands.
      (_: domain.connection.Connection) => budget,
      logger)
  }

  private final class RecordingLogger extends Logger[IO] {
    var errors: List[String] = Nil
    var throwableErrors = 0
    override def error(message: => String): IO[Unit] = IO { errors = errors :+ message }
    override def error(t: Throwable)(message: => String): IO[Unit] =
      IO { throwableErrors += 1; errors = errors :+ message }
    override def warn(message: => String): IO[Unit] = IO.unit
    override def info(message: => String): IO[Unit] = IO.unit
    override def debug(message: => String): IO[Unit] = IO.unit
    override def trace(message: => String): IO[Unit] = IO.unit
    override def warn(t: Throwable)(message: => String): IO[Unit] = warn(message)
    override def info(t: Throwable)(message: => String): IO[Unit] = info(message)
    override def debug(t: Throwable)(message: => String): IO[Unit] = debug(message)
    override def trace(t: Throwable)(message: => String): IO[Unit] = trace(message)
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
    override def recoverStaleAndTryCreate(value: SyncSession, at: Instant,
                                          errorCode: String, errorMessage: String): IO[SyncSessionClaim] = IO {
      val stale = values.filter(existing =>
        existing.organizationId == value.organizationId && existing.connectionId == value.connectionId &&
          existing.status == SyncSessionStatus.Running &&
          SyncSessionPolicy.isRecoverable(existing.recoverAfterAt, at))
      val recovered = stale.map(_.copy(status = SyncSessionStatus.Failed,
        finishedAt = Some(at), errorCode = Some(errorCode), errorMessage = Some(errorMessage)))
      values = values.map(existing =>
        recovered.find(_.id == existing.id).getOrElse(existing))
      val created =
        if (values.exists(s => s.organizationId == value.organizationId &&
          s.connectionId == value.connectionId && s.status == SyncSessionStatus.Running)) false
        else { values = value :: values; true }
      SyncSessionClaim(created, recovered)
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
