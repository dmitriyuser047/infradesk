package ru.bitec.app.ops
package infrastructure.http

import application.connection.{GetConnection, ListConnections}
import application.port.{ConnectionRepository, ConnectionScheduleRepository, SyncSessionRepository, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection, ConnectionConfig, ConnectionSchedule, ConnectionScope}
import domain.sync.{SyncSession, SyncSessionStatus}
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._

import java.time.Instant
import java.util.UUID

final class ConnectionRoutesSpec extends FunSuite {
  test("lists SSH and Docker connections including inactive connections in name and id order") {
    val fixture = buildFixture(List(dockerConnection, sshConnection, inactiveConnection, foreignConnection))

    val response = run(fixture, listRequest(OrganizationId))
    val connections = response._2.asArray.getOrElse(fail("Expected JSON array"))

    assertEquals(response._1.status, Status.Ok)
    assertEquals(connections.map(_.hcursor.get[String]("name").toOption), Vector(Some("Alpha Docker"), Some("Production SSH"), Some("Zulu inactive")))
    assertEquals(connections.map(_.hcursor.get[String]("connectorType").toOption), Vector(Some("DOCKER"), Some("SSH"), Some("SSH")))
    assertEquals(connections.map(_.hcursor.get[Boolean]("active").toOption), Vector(Some(true), Some(true), Some(false)))
    assertEquals(fixture.connectionRepository.findByOrganizationRequests, List(OrganizationId))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  test("uses id as the deterministic list tie breaker for equal names") {
    val lowerId = connection(
      UUID.fromString("50000000-0000-0000-0000-000000000010"),
      OrganizationId,
      ConnectionScope.Organization,
      "SSH",
      "Same name"
    )
    val higherId = lowerId.copy(id = UUID.fromString("50000000-0000-0000-0000-000000000011"))
    val fixture = buildFixture(List(higherId, lowerId))

    val response = run(fixture, listRequest(OrganizationId))
    val ids = response._2.asArray.getOrElse(fail("Expected JSON array"))
      .map(_.hcursor.get[String]("id").toOption)

    assertEquals(ids, Vector(Some(lowerId.id.toString), Some(higherId.id.toString)))
  }

  test("returns an empty array when an organization has no connections") {
    val fixture = buildFixture(List.empty)

    val response = run(fixture, listRequest(OrganizationId))

    assertEquals(response._1.status, Status.Ok)
    assertEquals(response._2.asArray, Some(Vector.empty))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  test("serializes organization, project, and environment scopes") {
    val fixture = buildFixture(List(organizationConnection, projectConnection, environmentConnection))

    val response = run(fixture, listRequest(OrganizationId))
    val scopes = response._2.asArray.getOrElse(fail("Expected JSON array")).map(_.hcursor.downField("scope"))

    assertEquals(scopes.map(_.get[String]("type").toOption), Vector(Some("ORGANIZATION"), Some("PROJECT"), Some("ENVIRONMENT")))
    assertEquals(scopes(1).get[String]("projectId").toOption, Some(ProjectId.toString))
    assertEquals(scopes(2).get[String]("projectId").toOption, Some(ProjectId.toString))
    assertEquals(scopes(2).get[String]("environmentId").toOption, Some(EnvironmentId.toString))
  }

  test("never exposes connection config or secretRef") {
    val fixture = buildFixture(List(sshConnection))

    val response = run(fixture, detailRequest(OrganizationId, sshConnection.id))

    assertEquals(response._1.status, Status.Ok)
    assert(!response._2.noSpaces.contains("config"))
    assert(!response._2.noSpaces.contains("secretRef"))
    assert(!response._2.noSpaces.contains("private-key-reference"))
    assert(!response._2.noSpaces.contains("10.0.0.1"))
  }

  test("returns the latest completed sync and enabled schedule") {
    val fixture = buildFixture(
      List(sshConnection),
      sessions = List(olderCompletedSync, latestCompletedSync),
      schedules = List(enabledSchedule)
    )

    val response = run(fixture, detailRequest(OrganizationId, sshConnection.id))
    val cursor = response._2.hcursor

    assertEquals(cursor.downField("lastSync").get[String]("id"), Right(latestCompletedSync.id.toString))
    assertEquals(cursor.downField("lastSync").get[String]("status"), Right("COMPLETED"))
    assertEquals(cursor.downField("schedule").get[Boolean]("enabled"), Right(true))
    assertEquals(cursor.downField("schedule").get[Long]("intervalSeconds"), Right(60L))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  test("keeps failed and running sync sessions visible") {
    val failedResponse = run(
      buildFixture(List(sshConnection), sessions = List(failedSync)),
      detailRequest(OrganizationId, sshConnection.id)
    )
    val runningResponse = run(
      buildFixture(List(sshConnection), sessions = List(runningSync)),
      detailRequest(OrganizationId, sshConnection.id)
    )

    assertEquals(failedResponse._2.hcursor.downField("lastSync").get[String]("status"), Right("FAILED"))
    assertEquals(runningResponse._2.hcursor.downField("lastSync").get[String]("status"), Right("RUNNING"))
    assertEquals(runningResponse._2.hcursor.downField("lastSync").get[Option[String]]("finishedAt"), Right(None))
  }

  test("uses id as a deterministic tie breaker for the latest sync") {
    val first = latestCompletedSync.copy(id = UUID.fromString("90000000-0000-0000-0000-000000000001"))
    val second = latestCompletedSync.copy(id = UUID.fromString("90000000-0000-0000-0000-000000000002"))
    val fixture = buildFixture(List(sshConnection), sessions = List(first, second, foreignSync))

    val response = run(fixture, detailRequest(OrganizationId, sshConnection.id))

    assertEquals(response._2.hcursor.downField("lastSync").get[String]("id"), Right(second.id.toString))
  }

  test("returns null for missing sync or schedule and preserves disabled schedule") {
    val absent = run(buildFixture(List(sshConnection)), detailRequest(OrganizationId, sshConnection.id))
    val disabled = run(
      buildFixture(List(sshConnection), schedules = List(disabledSchedule)),
      detailRequest(OrganizationId, sshConnection.id)
    )
    val foreign = run(
      buildFixture(List(sshConnection), schedules = List(foreignSchedule)),
      detailRequest(OrganizationId, sshConnection.id)
    )

    assertEquals(absent._2.hcursor.get[Option[Json]]("lastSync"), Right(None))
    assertEquals(absent._2.hcursor.get[Option[Json]]("schedule"), Right(None))
    assertEquals(disabled._2.hcursor.downField("schedule").get[Boolean]("enabled"), Right(false))
    assertEquals(foreign._2.hcursor.get[Option[Json]]("schedule"), Right(None))
  }

  test("returns detail not found for absent or foreign connections and validates ids") {
    val fixture = buildFixture(List(sshConnection, foreignConnection))

    val absent = run(fixture, detailRequest(OrganizationId, UnknownConnectionId))
    val foreign = run(fixture, detailRequest(OtherOrganizationId, sshConnection.id))
    val invalidOrganization = run(fixture, Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/organizations/bad/connections")))
    val invalidConnection = run(fixture, Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$OrganizationId/connections/bad")))

    assertEquals(absent._1.status, Status.NotFound)
    assertEquals(foreign._1.status, Status.NotFound)
    assertEquals(absent._2.hcursor.get[String]("code"), Right("CONNECTION_NOT_FOUND"))
    assertEquals(foreign._2.hcursor.get[String]("code"), Right("CONNECTION_NOT_FOUND"))
    assertEquals(invalidOrganization._1.status, Status.BadRequest)
    assertEquals(invalidConnection._1.status, Status.BadRequest)
  }

  test("sanitizes repository failures") {
    val fixture = buildFixture(List(sshConnection), failure = Some(new IllegalStateException("database credential")))

    val response = run(fixture, detailRequest(OrganizationId, sshConnection.id))

    assertEquals(response._1.status, Status.InternalServerError)
    assertEquals(response._2.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assert(!response._2.noSpaces.contains("database credential"))
    assertEquals(fixture.transactionRunner.calls, 1)
  }

  private def buildFixture(
    connections: List[Connection],
    sessions: List[SyncSession] = List.empty,
    schedules: List[ConnectionSchedule] = List.empty,
    failure: Option[Throwable] = None
  ): RouteFixture = {
    val connectionRepository = new InMemoryConnectionRepository(connections, failure)
    val syncSessionRepository = new InMemorySyncSessionRepository(sessions)
    val connectionScheduleRepository = new InMemoryConnectionScheduleRepository(schedules)
    val transactionRunner = new RecordingTransactionRunner
    val routes = new ConnectionRoutes[IO](
      GetConnection(connectionRepository, syncSessionRepository, connectionScheduleRepository),
      ListConnections(connectionRepository, syncSessionRepository, connectionScheduleRepository),
      transactionRunner
    )

    RouteFixture(routes.routes.orNotFound, connectionRepository, transactionRunner)
  }

  private def run(fixture: RouteFixture, request: Request[IO]): (org.http4s.Response[IO], Json) = {
    val response = fixture.app.run(request).unsafeRunSync()
    response -> response.as[Json].unsafeRunSync()
  }

  private def listRequest(organizationId: UUID): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/connections"))

  private def detailRequest(organizationId: UUID, connectionId: UUID): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/connections/$connectionId"))

  private final case class RouteFixture(
    app: org.http4s.HttpApp[IO],
    connectionRepository: InMemoryConnectionRepository,
    transactionRunner: RecordingTransactionRunner
  )

  private final class RecordingTransactionRunner extends TransactionRunner[IO, IO] {
    var calls: Int = 0

    override def run[A](program: IO[A]): IO[A] =
      IO { calls += 1 } *> program
  }

  private final class InMemoryConnectionRepository(
    connections: List[Connection],
    failure: Option[Throwable]
  ) extends ConnectionRepository[IO] {
    var findByOrganizationRequests: List[UUID] = List.empty

    override def findById(organizationId: UUID, id: UUID): IO[Option[Connection]] =
      operation(connections.find(connection => connection.organizationId == organizationId && connection.id == id))

    override def findByOrganization(organizationId: UUID): IO[List[Connection]] =
      IO { findByOrganizationRequests = findByOrganizationRequests :+ organizationId } *>
        operation(
          connections
            .filter(_.organizationId == organizationId)
            .sortBy(connection => (connection.name, connection.id.toString))
        )

    override def save(connection: Connection): IO[Unit] = IO.unit

    private def operation[A](value: => A): IO[A] =
      failure.fold(IO(value))(IO.raiseError)
  }

  private final class InMemorySyncSessionRepository(sessions: List[SyncSession]) extends SyncSessionRepository[IO] {
    override def findLatestByConnection(
      organizationId: UUID,
      connectionId: UUID
    ): IO[Option[SyncSession]] =
      IO.pure(
        sessions
          .filter(session => session.organizationId == organizationId && session.connectionId == connectionId)
          .sortBy(session => (session.startedAt, session.id.toString))
          .lastOption
      )

    override def create(session: SyncSession): IO[Unit] = IO.unit

    override def tryCreate(session: SyncSession): IO[Boolean] = IO.pure(true)

    override def recoverStaleAndTryCreate(session: SyncSession, staleBefore: Instant,
                                          recoveredAt: Instant, errorCode: String, errorMessage: String): IO[Boolean] =
      IO.pure(true)

    override def findRecentByConnection(organizationId: UUID, connectionId: UUID, limit: Int): IO[List[SyncSession]] =
      IO.pure(sessions.filter(s => s.organizationId == organizationId && s.connectionId == connectionId)
        .sortBy(s => (s.startedAt, s.id.toString)).reverse.take(limit))

    override def findById(organizationId: UUID, connectionId: UUID, sessionId: UUID): IO[Option[SyncSession]] =
      IO.pure(sessions.find(s => s.organizationId == organizationId && s.connectionId == connectionId && s.id == sessionId))

    override def complete(organizationId: UUID, id: UUID, finishedAt: Instant): IO[Unit] = IO.unit

    override def fail(organizationId: UUID, id: UUID, finishedAt: Instant, errorCode: String, errorMessage: String): IO[Unit] = IO.unit
  }

  private final class InMemoryConnectionScheduleRepository(
    schedules: List[ConnectionSchedule]
  ) extends ConnectionScheduleRepository[IO] {
    override def save(schedule: ConnectionSchedule): IO[Unit] = IO.unit
    override def findByConnection(
      organizationId: UUID,
      connectionId: UUID
    ): IO[Option[ConnectionSchedule]] =
      IO.pure(schedules.find(schedule =>
        schedule.organizationId == organizationId && schedule.connectionId == connectionId
      ))

    override def findDue(now: Instant, limit: Int): IO[List[ConnectionSchedule]] = IO.pure(List.empty)

    override def updateAfterRun(
      organizationId: UUID,
      connectionId: UUID,
      nextRunAt: Instant,
      consecutiveFailures: Long
    ): IO[Unit] = IO.unit
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val ProjectId = UUID.fromString("30000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val UnknownConnectionId = UUID.fromString("50000000-0000-0000-0000-000000000099")
  private val Now = Instant.parse("2026-09-22T13:00:00Z")
  private val Earlier = Now.minusSeconds(60)

  private def connection(
    id: UUID,
    organizationId: UUID,
    scope: ConnectionScope,
    connectorType: String,
    name: String,
    isActive: Boolean = true
  ): Connection =
    Connection(
      id,
      organizationId,
      scope,
      connectorType,
      name.toLowerCase.replace(' ', '-'),
      name,
      ConnectionConfig(Map("host" -> "10.0.0.1")),
      Some("private-key-reference"),
      isActive,
      Earlier,
      Now
    )

  private val dockerConnection = connection(
    UUID.fromString("50000000-0000-0000-0000-000000000001"),
    OrganizationId,
    ConnectionScope.Organization,
    "DOCKER",
    "Alpha Docker"
  )
  private val sshConnection = connection(
    UUID.fromString("50000000-0000-0000-0000-000000000002"),
    OrganizationId,
    ConnectionScope.Project(ProjectId),
    "SSH",
    "Production SSH"
  )
  private val inactiveConnection = connection(
    UUID.fromString("50000000-0000-0000-0000-000000000003"),
    OrganizationId,
    ConnectionScope.Environment(ProjectId, EnvironmentId),
    "SSH",
    "Zulu inactive",
    isActive = false
  )
  private val organizationConnection = dockerConnection
  private val projectConnection = sshConnection
  private val environmentConnection = inactiveConnection
  private val foreignConnection = connection(
    UUID.fromString("50000000-0000-0000-0000-000000000004"),
    OtherOrganizationId,
    ConnectionScope.Organization,
    "SSH",
    "Foreign"
  )

  private def session(
    id: UUID,
    organizationId: UUID,
    status: SyncSessionStatus,
    startedAt: Instant,
    finishedAt: Option[Instant]
  ): SyncSession =
    SyncSession(id, organizationId, sshConnection.id, startedAt, finishedAt, status)

  private val olderCompletedSync = session(
    UUID.fromString("90000000-0000-0000-0000-000000000003"),
    OrganizationId,
    SyncSessionStatus.Completed,
    Earlier,
    Some(Earlier.plusSeconds(2))
  )
  private val latestCompletedSync = session(
    UUID.fromString("90000000-0000-0000-0000-000000000004"),
    OrganizationId,
    SyncSessionStatus.Completed,
    Now,
    Some(Now.plusSeconds(2))
  )
  private val failedSync = latestCompletedSync.copy(status = SyncSessionStatus.Failed)
  private val runningSync = latestCompletedSync.copy(status = SyncSessionStatus.Running, finishedAt = None)
  private val foreignSync = latestCompletedSync.copy(organizationId = OtherOrganizationId)

  private val enabledSchedule = ConnectionSchedule(OrganizationId, sshConnection.id, enabled = true, 60, Now.plusSeconds(60), 0L)
  private val disabledSchedule = enabledSchedule.copy(enabled = false)
  private val foreignSchedule = enabledSchedule.copy(organizationId = OtherOrganizationId)
}
