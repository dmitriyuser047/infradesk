package ru.bitec.app.ops
package application.connection

import application.connector.{ConnectionSyncExecutionFailed, ConnectionSyncInactive, ConnectionSyncNotFound, RunConnectionSync, SyncAlreadyRunning}
import application.monitor.{MonitorRuleEvaluator, MonitorTransition}
import application.connector.SyncSessionPolicy
import application.port.{SyncSessionClaim, ConnectionRepository, ConnectionSyncResult, ConnectionSyncRunner, ConnectionSynchronizer, ResourceConnectorFailure, ResourceConnectorFailureCode, SyncSessionRepository, TimeProvider, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.sync.{SyncSession, SyncSessionStatus}
import domain.resource.Resource
import infrastructure.http.ConnectionSyncRoutes
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID

final class ConnectionSyncSessionsSpec extends FunSuite {
  private val org = UUID.randomUUID()
  private val otherOrg = UUID.randomUUID()
  private val connectionId = UUID.randomUUID()
  private val otherConnectionId = UUID.randomUUID()
  private val at = Instant.parse("2026-09-23T10:00:00Z")
  private val connection = Connection(connectionId, org, ConnectionScope.Organization, "SSH", "vps", "VPS",
    ConnectionConfig(Map.empty), None, true, at, at)

  test("history validates connection, limits to 20, orders newest first and scopes each lookup") {
    val f = new SyncFixture
    f.sessions.values = (1 to 25).map(index => session(UUID.randomUUID(), org, connectionId,
      at.plusSeconds(index), SyncSessionStatus.Completed)).toList :+
      session(UUID.randomUUID(), otherOrg, connectionId, at.plusSeconds(100), SyncSessionStatus.Completed) :+
      session(UUID.randomUUID(), org, otherConnectionId, at.plusSeconds(200), SyncSessionStatus.Completed)
    val list = new ListConnectionSyncSessions[IO](f.connections, f.sessions)
    val recent = list.execute(org, connectionId).unsafeRunSync().get
    assertEquals(recent.length, 20)
    assertEquals(recent.map(_.startedAt), recent.map(_.startedAt).sortWith(_.isAfter(_)))
    assert(recent.forall(s => s.organizationId == org && s.connectionId == connectionId))
    assertEquals(f.sessions.requestedLimit, Some(20))
    assertEquals(list.execute(org, UUID.randomUUID()).unsafeRunSync(), None)
    f.connections.current = Some(connection.copy(isActive = false))
    assertEquals(list.execute(org, connectionId).unsafeRunSync().map(_.length), Some(20))
    val get = new GetConnectionSyncSession[IO](f.sessions)
    assertEquals(get.execute(otherOrg, connectionId, recent.head.id).unsafeRunSync(), None)
    assertEquals(get.execute(org, otherConnectionId, recent.head.id).unsafeRunSync(), None)
  }

  test("manual success returns the exact completed session, not a newer unrelated row") {
    val f = new SyncFixture
    val id = UUID.randomUUID()
    val run = new ConnectionSyncRunner[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] = IO {
        f.sessions.values = List(
          session(UUID.randomUUID(), org, connectionId, at.plusSeconds(10), SyncSessionStatus.Completed),
          session(id, org, connectionId, at, SyncSessionStatus.Completed)
        )
        ConnectionSyncResult(id, Nil)
      }
    }
    val result = manualSync(f, run)
      .execute(support.AuthorizationFixtures.actor(org), connectionId).unsafeRunSync()
    assertEquals(result.id, id)
    assertEquals(f.sessions.latestCalls, 0)
  }

  test("manual uses the shared runner and evaluates monitors after successful synchronization") {
    val f = new SyncFixture
    val id = UUID.randomUUID()
    var evaluations = 0
    val synchronizer = new ConnectionSynchronizer[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] = IO {
        f.sessions.values = List(session(id, org, connectionId, at, SyncSessionStatus.Completed))
        ConnectionSyncResult(id, Nil)
      }
    }
    val evaluator = new MonitorRuleEvaluator[IO] {
      override def execute(organizationId: UUID, evaluatedConnectionId: UUID, evaluatedAt: Instant): IO[List[application.monitor.MonitorTransition]] =
        IO { evaluations += 1; List.empty }
    }
    val clock = new TimeProvider[IO] { override def now: IO[Instant] = IO.pure(at) }
    val shared = new RunConnectionSync[IO, IO](synchronizer, evaluator, f.transactionRunner, clock,
      _root_.org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("test.sync-sessions"))
    val result = manualSync(f, shared)
      .execute(support.AuthorizationFixtures.actor(org), connectionId).unsafeRunSync()
    assertEquals(result.id, id)
    assertEquals(evaluations, 1)
  }

  test("incident transitions are logged only after the monitor transaction commits") {
    val log = new RecordingLogger
      val sessionId = UUID.randomUUID()
      val incidentId = UUID.randomUUID()
      val transition = MonitorTransition.Opened(org, UUID.randomUUID(), UUID.randomUUID(), incidentId,
        _root_.ru.bitec.app.ops.domain.incident.IncidentReason.ThresholdViolation, at)
      val synchronizer = new ConnectionSynchronizer[IO] {
        override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
          IO.pure(ConnectionSyncResult(sessionId, Nil))
      }
      val evaluator = new MonitorRuleEvaluator[IO] {
        override def execute(organizationId: UUID, evaluatedConnectionId: UUID, evaluatedAt: Instant): IO[List[MonitorTransition]] =
          IO.pure(List(transition))
      }
      val clock = new TimeProvider[IO] { override def now: IO[Instant] = IO.pure(at) }
      var committed = false
      val rolledBack = new TransactionRunner[IO, IO] {
        override def run[A](program: IO[A]): IO[A] =
          program.flatMap(_ => IO.raiseError[A](new IllegalStateException("commit failed")))
      }
      new RunConnectionSync[IO, IO](synchronizer, evaluator, rolledBack, clock, log)
        .execute(org, connectionId).unsafeRunSync()
      assertEquals(log.infoMessages.count(_.startsWith("incident.opened")), 0)

      val committedRunner = new TransactionRunner[IO, IO] {
        override def run[A](program: IO[A]): IO[A] = program.flatMap(value => IO { committed = true; value })
      }
      new RunConnectionSync[IO, IO](synchronizer, evaluator, committedRunner, clock, log)
        .execute(org, connectionId).unsafeRunSync()
      assert(committed)
      assertEquals(log.infoMessages.count(_.startsWith("incident.opened")), 1)
  }

  test("evaluates monitoring after a failed synchronization without masking the failure") {
    val log = new RecordingLogger
    val sessionId = UUID.randomUUID()
    val failure = ConnectionSyncExecutionFailed(sessionId, new IllegalStateException("ssh timeout"))
    var evaluatedFor: List[(UUID, UUID, Instant)] = Nil
    val synchronizer = new ConnectionSynchronizer[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
        IO.raiseError(failure)
    }
    val evaluator = new MonitorRuleEvaluator[IO] {
      override def execute(organizationId: UUID, evaluatedConnectionId: UUID, evaluatedAt: Instant): IO[List[MonitorTransition]] =
        IO {
          evaluatedFor = evaluatedFor :+ ((organizationId, evaluatedConnectionId, evaluatedAt))
          List(MonitorTransition.Opened(organizationId, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), _root_.ru.bitec.app.ops.domain.incident.IncidentReason.NoData, evaluatedAt))
        }
    }
    val clock = new TimeProvider[IO] { override def now: IO[Instant] = IO.pure(at) }
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]): IO[A] = program }

    val thrown = intercept[ConnectionSyncExecutionFailed](
      new RunConnectionSync[IO, IO](synchronizer, evaluator, runner, clock, log)
        .execute(org, connectionId).unsafeRunSync()
    )

    assertEquals(thrown, failure)
    assertEquals(evaluatedFor, List((org, connectionId, at)))
    assertEquals(log.infoMessages.count(message => message.startsWith("incident.opened") && message.contains("reason=NO_DATA")), 1)
  }

  test("a run that never started leaves monitoring alone") {
    val log = new RecordingLogger
    val clock = new TimeProvider[IO] { override def now: IO[Instant] = IO.pure(at) }
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]): IO[A] = program }

    val skipped: List[Throwable] = List(
      SyncAlreadyRunning(),
      ConnectionSyncInactive(),
      ConnectionSyncNotFound()
    )

    skipped.foreach { failure =>
      var evaluations = 0
      val evaluator = new MonitorRuleEvaluator[IO] {
        override def execute(organizationId: UUID, evaluatedConnectionId: UUID, evaluatedAt: Instant): IO[List[MonitorTransition]] =
          IO { evaluations += 1; List.empty }
      }
      val synchronizer = new ConnectionSynchronizer[IO] {
        override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
          IO.raiseError(failure)
      }

      val thrown = intercept[RuntimeException](
        new RunConnectionSync[IO, IO](synchronizer, evaluator, runner, clock, log)
          .execute(org, connectionId).unsafeRunSync()
      )

      // A skipped run says nothing about the data: the run already in progress owns it.
      assertEquals(thrown, failure, s"original failure changed for $failure")
      assertEquals(evaluations, 0, s"monitoring evaluated for $failure")
    }
    assertEquals(log.errorMessages.count(_.startsWith("monitor.evaluation.failed")), 0)
  }

  test("a failing monitor evaluation keeps the original outcome of the synchronization") {
    val log = new RecordingLogger
    val sessionId = UUID.randomUUID()
    val evaluator = new MonitorRuleEvaluator[IO] {
      override def execute(organizationId: UUID, evaluatedConnectionId: UUID, evaluatedAt: Instant): IO[List[MonitorTransition]] =
        IO.raiseError(new IllegalStateException("projection failed"))
    }
    val clock = new TimeProvider[IO] { override def now: IO[Instant] = IO.pure(at) }
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]): IO[A] = program }
    val succeeding = new ConnectionSynchronizer[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
        IO.pure(ConnectionSyncResult(sessionId, Nil))
    }
    val failing = new ConnectionSynchronizer[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
        IO.raiseError(ConnectionSyncExecutionFailed(sessionId, new IllegalStateException("ssh timeout")))
    }

    val result = new RunConnectionSync[IO, IO](succeeding, evaluator, runner, clock, log)
      .execute(org, connectionId).unsafeRunSync()
    assertEquals(result.sessionId, sessionId)

    intercept[ConnectionSyncExecutionFailed](
      new RunConnectionSync[IO, IO](failing, evaluator, runner, clock, log)
        .execute(org, connectionId).unsafeRunSync()
    )
    assertEquals(log.errorMessages.count(_.startsWith("monitor.evaluation.failed")), 2)
  }

  private final class RecordingLogger extends Logger[IO] {
    var infoMessages: List[String] = Nil
    var errorMessages: List[String] = Nil
    override def info(message: => String): IO[Unit] = IO { infoMessages = infoMessages :+ message }
    override def error(message: => String): IO[Unit] = IO { errorMessages = errorMessages :+ message }
    override def warn(message: => String): IO[Unit] = IO.unit
    override def debug(message: => String): IO[Unit] = IO.unit
    override def trace(message: => String): IO[Unit] = IO.unit
    override def error(t: Throwable)(message: => String): IO[Unit] = error(message)
    override def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
    override def info(t: Throwable)(message: => String): IO[Unit] = info(message)
    override def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
    override def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit
  }

  test("manual execution failure returns the exact FAILED session with safe metadata") {
    val f = new SyncFixture
    val id = UUID.randomUUID()
    val run = new ConnectionSyncRunner[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] = IO {
        f.sessions.values = List(session(id, org, connectionId, at, SyncSessionStatus.Failed).copy(
          finishedAt = Some(at.plusSeconds(1)), errorCode = Some("SYNC_FAILED"),
          errorMessage = Some("Synchronization failed")))
        throw ConnectionSyncExecutionFailed(id, new IllegalStateException("password in raw exception"))
      }
    }
    val result = manualSync(f, run)
      .execute(support.AuthorizationFixtures.actor(org), connectionId).unsafeRunSync()
    assertEquals(result.id, id)
    assertEquals(result.status, SyncSessionStatus.Failed)
    assertEquals(result.errorCode, Some("SYNC_FAILED"))
    assertEquals(result.errorMessage, Some("Synchronization failed"))
    assert(!result.toString.contains("password in raw exception"))
  }

  test("pre-session errors do not create a failed session") {
    for (error <- List(ConnectionSyncNotFound(), ConnectionSyncInactive(), SyncAlreadyRunning())) {
      val f = new SyncFixture
      val run = new ConnectionSyncRunner[IO] {
        override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
          IO.raiseError(error)
      }
      intercept[RuntimeException] {
        manualSync(f, run)
          .execute(support.AuthorizationFixtures.actor(org), connectionId).unsafeRunSync()
      }
      assertEquals(f.sessions.values, Nil)
    }
  }

  test("RUNNING guard rejects a second run for the same connection but allows another") {
    val f = new SyncFixture
    val first = session(UUID.randomUUID(), org, connectionId, at, SyncSessionStatus.Running)
    assert(f.sessions.tryCreate(first).unsafeRunSync())
    assert(!f.sessions.tryCreate(first.copy(id = UUID.randomUUID())).unsafeRunSync())
    assert(f.sessions.tryCreate(first.copy(id = UUID.randomUUID(), connectionId = otherConnectionId)).unsafeRunSync())
    assertEquals(f.sessions.values.length, 2)
  }

  test("history HTTP distinguishes empty, missing connection and foreign session") {
    val f = new SyncFixture
    val known = session(UUID.randomUUID(), org, connectionId, at, SyncSessionStatus.Failed).copy(
      errorCode = Some("SYNC_FAILED"), errorMessage = Some("Synchronization failed"))
    f.sessions.values = List(known)
    val app = routes(f, new ConnectionSyncRunner[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] =
        IO.raiseError(ConnectionSyncNotFound())
    })
    val listPath = s"/api/v1/organizations/$org/connections/$connectionId/sync-sessions"
    val list = response(app, Method.GET, listPath)
    assertEquals(list._1, Status.Ok)
    assertEquals(list._2.asArray.map(_.length), Some(1))
    val detail = response(app, Method.GET, s"$listPath/${known.id}")
    assertEquals(detail._1, Status.Ok)
    assertEquals(detail._2.hcursor.get[String]("errorCode"), Right("SYNC_FAILED"))
    assert(!detail._2.noSpaces.contains("password"))
    val foreign = response(app, Method.GET,
      s"/api/v1/organizations/$otherOrg/connections/$connectionId/sync-sessions/${known.id}")
    assertEquals(foreign._1, Status.NotFound)
    assertEquals(foreign._2.hcursor.get[String]("code"), Right("SYNC_SESSION_NOT_FOUND"))
    f.sessions.values = Nil
    assertEquals(response(app, Method.GET, listPath)._2.asArray, Some(Vector.empty))
    val missing = response(app, Method.GET,
      s"/api/v1/organizations/$org/connections/${UUID.randomUUID()}/sync-sessions")
    assertEquals(missing._1, Status.NotFound)
    assertEquals(missing._2.hcursor.get[String]("code"), Right("CONNECTION_NOT_FOUND"))
  }

  test("manual HTTP returns terminal FAILED as 200 and maps pre-session errors") {
    val f = new SyncFixture
    val id = UUID.randomUUID()
    val path = s"/api/v1/organizations/$org/connections/$connectionId/sync"
    val failedRunner = new ConnectionSyncRunner[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] = IO {
        f.sessions.values = List(session(id, org, connectionId, at, SyncSessionStatus.Failed).copy(
          errorCode = Some("SYNC_FAILED"), errorMessage = Some("Synchronization failed")))
        throw ConnectionSyncExecutionFailed(id, new RuntimeException("stderr password"))
      }
    }
    val failed = response(routes(f, failedRunner), Method.POST, path)
    assertEquals(failed._1, Status.Ok)
    assertEquals(failed._2.hcursor.get[String]("status"), Right("FAILED"))
    assert(!failed._2.noSpaces.contains("stderr"))
    for ((error, status, code) <- List(
      (ConnectionSyncNotFound(): Throwable, Status.NotFound, "CONNECTION_NOT_FOUND"),
      (ConnectionSyncInactive(): Throwable, Status.Conflict, "CONNECTION_INACTIVE"),
      (SyncAlreadyRunning(): Throwable, Status.Conflict, "SYNC_ALREADY_RUNNING")
    )) {
      val preStart = new ConnectionSyncRunner[IO] {
        override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] = IO.raiseError(error)
      }
      val result = response(routes(f, preStart), Method.POST, path)
      assertEquals(result._1, status)
      assertEquals(result._2.hcursor.get[String]("code"), Right(code))
    }
  }

  test("manual and history HTTP expose only typed safe failure metadata") {
    val f = new SyncFixture
    val id = UUID.randomUUID()
    val raw = new IllegalStateException("password=super-secret host=10.0.0.1")
    val typed = ResourceConnectorFailure(ResourceConnectorFailureCode.SshConnectTimeout,
      "SSH connection timed out", raw)
    val run = new ConnectionSyncRunner[IO] {
      override def execute(organizationId: UUID, requestedConnectionId: UUID): IO[ConnectionSyncResult] = IO {
        f.sessions.values = List(session(id, org, connectionId, at, SyncSessionStatus.Failed).copy(
          errorCode = Some(ResourceConnectorFailureCode.SshConnectTimeout),
          errorMessage = Some("SSH connection timed out")))
        throw ConnectionSyncExecutionFailed(id, typed)
      }
    }
    val app = routes(f, run)
    val path = s"/api/v1/organizations/$org/connections/$connectionId"

    val manual = response(app, Method.POST, s"$path/sync")
    assertEquals(manual._1, Status.Ok)
    assertEquals(manual._2.hcursor.get[String]("status"), Right("FAILED"))
    assertEquals(manual._2.hcursor.get[String]("errorCode"), Right(ResourceConnectorFailureCode.SshConnectTimeout))
    assertEquals(manual._2.hcursor.get[String]("errorMessage"), Right("SSH connection timed out"))

    val detail = response(app, Method.GET, s"$path/sync-sessions/$id")
    assertEquals(detail._1, Status.Ok)
    assertEquals(detail._2.hcursor.get[String]("errorCode"), Right(ResourceConnectorFailureCode.SshConnectTimeout))
    assertEquals(detail._2.hcursor.get[String]("errorMessage"), Right("SSH connection timed out"))
    assert(!manual._2.noSpaces.contains("super-secret"))
    assert(!detail._2.noSpaces.contains("10.0.0.1"))
    assert(!detail._2.noSpaces.contains("IllegalStateException"))
  }

  private def routes(f: SyncFixture, syncRunner: ConnectionSyncRunner[IO]): _root_.org.http4s.HttpApp[IO] =
    new ConnectionSyncRoutes[IO](new ListConnectionSyncSessions(f.connections, f.sessions),
      new GetConnectionSyncSession(f.sessions),
      manualSync(f, syncRunner),
      f.transactionRunner,
      support.AuthorizationFixtures.authorization).routes.orNotFound

  private def manualSync(
    f: SyncFixture,
    syncRunner: ConnectionSyncRunner[IO]
  ): RunManualConnectionSync[IO] =
    new RunManualConnectionSync[IO](syncRunner, f.sessions, f.connections,
      support.TestAuditRecorder(new support.RecordingAuditEventRepository), f.transactionRunner)

  private def response(app: _root_.org.http4s.HttpApp[IO], method: Method, path: String): (Status, Json) = {
    val result = support.AuthorizationFixtures.authorized(app)
      .run(Request[IO](method, Uri.unsafeFromString(path))).unsafeRunSync()
    result.status -> result.as[Json].unsafeRunSync()
  }

  private def session(id: UUID, organizationId: UUID, forConnection: UUID, started: Instant,
                      status: SyncSessionStatus): SyncSession =
    SyncSession(id, organizationId, forConnection, started, started.plusSeconds(900),
      if (status == SyncSessionStatus.Running) None else Some(started.plusSeconds(1)), status)

  private final class SyncFixture {
    val connections = new ConnectionRepository[IO] {
      var current: Option[Connection] = Some(connection)
      override def findById(organizationId: UUID, id: UUID): IO[Option[Connection]] =
        IO.pure(current.filter(c => c.organizationId == organizationId && c.id == id))
      override def findByOrganization(organizationId: UUID): IO[List[Connection]] =
        IO.pure(current.filter(_.organizationId == organizationId).toList)
      override def save(value: Connection): IO[Unit] = IO { current = Some(value) }
      override def saveIfUnmodified(value: Connection, expectedUpdatedAt: Instant): IO[Boolean] = IO {
        if (current.exists(_.updatedAt == expectedUpdatedAt)) {
          current = Some(value)
          true
        } else false
      }
    }
    val sessions = new Sessions
    val transactionRunner = new TransactionRunner[IO, IO] {
      override def run[A](program: IO[A]): IO[A] = program
    }
  }

  private final class Sessions extends SyncSessionRepository[IO] {
    var values: List[SyncSession] = Nil
    var requestedLimit: Option[Int] = None
    var latestCalls = 0
    override def findLatestByConnection(organizationId: UUID, forConnection: UUID): IO[Option[SyncSession]] = IO {
      latestCalls += 1
      values.filter(s => s.organizationId == organizationId && s.connectionId == forConnection)
        .sortBy(s => (s.startedAt, s.id.toString)).lastOption
    }
    override def findRecentByConnection(organizationId: UUID, forConnection: UUID, limit: Int): IO[List[SyncSession]] = IO {
      requestedLimit = Some(limit)
      values.filter(s => s.organizationId == organizationId && s.connectionId == forConnection)
        .sortBy(s => (s.startedAt, s.id.toString)).reverse.take(limit)
    }
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
    override def fail(organizationId: UUID, id: UUID, finishedAt: Instant, code: String, message: String): IO[Unit] = IO.unit
  }
}
