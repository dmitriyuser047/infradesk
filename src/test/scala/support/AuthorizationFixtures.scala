package ru.bitec.app.ops
package support

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{AuditEventRepository, IdGenerator, TimeProvider}
import cats.data.Kleisli
import cats.effect.IO
import domain.audit.{AuditCursor, AuditEvent}
import domain.auth.{AuthenticatedUser, OrganizationRole}
import infrastructure.http.{OrganizationAccessContext, OrganizationAuthorization}
import org.http4s.{HttpApp, Request}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Shared helpers for the tests that exercise authorization and the audit journal. */
object AuthorizationFixtures {

  val ActorUserId: UUID = UUID.fromString("10000000-0000-0000-0000-0000000000aa")

  val authorization: OrganizationAuthorization =
    new OrganizationAuthorization(Slf4jLogger.getLoggerFromName[IO]("test.authorization"))

  def user(id: UUID = ActorUserId): AuthenticatedUser =
    AuthenticatedUser(id, "owner@example.test", "Owner")

  def context(
    organizationId: UUID,
    role: OrganizationRole = OrganizationRole.Owner,
    userId: UUID = ActorUserId
  ): OrganizationAccessContext =
    OrganizationAccessContext(user(userId), organizationId, role)

  def actor(organizationId: UUID, userId: UUID = ActorUserId): ActorContext =
    ActorContext(userId, organizationId)

  /** Routes are reached through the authentication boundary, which attaches the access context;
    * a test that calls them directly has to attach it the same way.
    */
  def as(
    request: Request[IO],
    organizationId: UUID,
    role: OrganizationRole = OrganizationRole.Owner,
    userId: UUID = ActorUserId
  ): Request[IO] =
    OrganizationAuthorization.withContext(request, context(organizationId, role, userId))

  /** Stands in for the authentication boundary in specs that drive one route class directly:
    * it attaches the access context of the organization in the path, with the given role.
    */
  def authorized(
    app: HttpApp[IO],
    role: OrganizationRole = OrganizationRole.Owner,
    userId: UUID = ActorUserId
  ): HttpApp[IO] =
    Kleisli { request: Request[IO] =>
      app.run(as(request, organizationInPath(request), role, userId))
    }

  /** A path whose organization segment is not a UUID never reaches a route through the real
    * boundary; the specs that check the route's own parsing still need some context.
    */
  private val UnparseableOrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000ff")

  private def organizationInPath(request: Request[IO]): UUID =
    request.uri.path.renderString.split('/').filter(_.nonEmpty).toList match {
      case "api" :: "v1" :: "organizations" :: value :: _ =>
        Try(UUID.fromString(value)).getOrElse(UnparseableOrganizationId)
      case _ => UnparseableOrganizationId
    }
}

/** An in-memory journal that keeps what was recorded, in order. */
final class RecordingAuditEventRepository extends AuditEventRepository[IO] {
  private var events: List[AuditEvent] = List.empty

  def recorded: List[AuditEvent] = synchronized(events)

  override def save(event: AuditEvent): IO[Unit] = saveAll(List(event))

  override def saveAll(values: List[AuditEvent]): IO[Unit] =
    IO(synchronized { events = events ++ values })

  override def listByOrganization(
    organizationId: UUID,
    before: Option[AuditCursor],
    limit: Int
  ): IO[List[AuditEvent]] =
    IO(recorded.filter(_.organizationId == organizationId).take(limit))
}

/** A journal whose insert always fails, for the tests that assert the mutation rolls back. */
final class FailingAuditEventRepository extends AuditEventRepository[IO] {
  override def save(event: AuditEvent): IO[Unit] =
    IO.raiseError(new IllegalStateException("audit unavailable"))
  override def saveAll(events: List[AuditEvent]): IO[Unit] = save(events.head)
  override def listByOrganization(
    organizationId: UUID,
    before: Option[AuditCursor],
    limit: Int
  ): IO[List[AuditEvent]] = IO.pure(List.empty)
}

object TestAuditRecorder {

  def apply(repository: AuditEventRepository[IO]): AuditRecorder[IO] =
    new AuditRecorder[IO](repository, new SequentialIdGenerator, new FixedTimeProvider)

  def recording: (RecordingAuditEventRepository, AuditRecorder[IO]) = {
    val repository = new RecordingAuditEventRepository
    (repository, apply(repository))
  }

  val RecordedAt: Instant = Instant.parse("2026-09-24T10:00:00Z")

  private final class SequentialIdGenerator extends IdGenerator[IO] {
    private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    override def nextId: IO[UUID] =
      IO(UUID.fromString(f"e0000000-0000-0000-0000-${counter.incrementAndGet()}%012d"))
  }

  private final class FixedTimeProvider extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(RecordedAt)
  }
}

/** Finds one connection whatever is asked for: enough for the existence check a manual sync
  * performs before it journals the request.
  */
final class FixedConnectionRepository(connection: domain.connection.Connection)
  extends application.port.ConnectionRepository[IO] {
  override def findById(organizationId: UUID, id: UUID): IO[Option[domain.connection.Connection]] =
    IO.pure(Some(connection))
  override def findByOrganization(organizationId: UUID): IO[List[domain.connection.Connection]] =
    IO.pure(List(connection))
  override def save(value: domain.connection.Connection): IO[Unit] = IO.unit
}

/** An in-memory timeline that keeps what was recorded, in order. */
final class RecordingHistoryEventRepository extends application.port.HistoryEventRepository[IO] {
  private var events: List[domain.history.HistoryEvent] = List.empty

  def recorded: List[domain.history.HistoryEvent] = synchronized(events)

  override def save(event: domain.history.HistoryEvent): IO[Unit] = saveAll(List(event))

  override def saveAll(values: List[domain.history.HistoryEvent]): IO[Unit] =
    IO(synchronized { events = events ++ values })
}

/** A timeline whose insert always fails, for the tests that assert the transition rolls back. */
final class FailingHistoryEventRepository(
  failOn: domain.history.HistoryEventType => Boolean = _ => true
) extends application.port.HistoryEventRepository[IO] {
  override def save(event: domain.history.HistoryEvent): IO[Unit] = saveAll(List(event))
  override def saveAll(values: List[domain.history.HistoryEvent]): IO[Unit] =
    if (values.exists(value => failOn(value.eventType)))
      IO.raiseError(new IllegalStateException("history unavailable"))
    else IO.unit
}

object TestHistoryRecorder {

  def apply(repository: application.port.HistoryEventRepository[IO]): application.history.HistoryRecorder[IO] =
    new application.history.HistoryRecorder[IO](repository, new SequentialIdGenerator,
      new FixedTimeProvider)

  def recording: (RecordingHistoryEventRepository, application.history.HistoryRecorder[IO]) = {
    val repository = new RecordingHistoryEventRepository
    (repository, apply(repository))
  }

  val RecordedAt: Instant = TestAuditRecorder.RecordedAt

  private final class SequentialIdGenerator extends IdGenerator[IO] {
    private val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    override def nextId: IO[UUID] =
      IO(UUID.fromString(f"d0000000-0000-0000-0000-${counter.incrementAndGet()}%012d"))
  }

  private final class FixedTimeProvider extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(RecordedAt)
  }
}
