package ru.bitec.app.ops
package application.account

import application.audit.AuditRecorder
import application.port.{
  AuditEventRepository,
  AuthSessionRepository,
  IdGenerator,
  MyOrganization,
  OrganizationMembershipRepository,
  TimeProvider,
  TransactionRunner,
  UserAccountRepository
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.audit.{AuditAction, AuditCursor, AuditEvent}
import domain.auth.{AuthSession, AuthenticatedSession, OrganizationMembership, OrganizationRole, UserAccount, UserSessionSummary}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

/** Listing and revoking the authenticated user's sessions. Scope is the user; another user's
  * sessions are never listed or revoked, and the current session is revoked only through logout.
  */
final class UserSessionsSpec extends FunSuite {

  private val UserId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val OtherUser = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val Current = UUID.fromString("50000000-0000-0000-0000-000000000001")
  private val Other = UUID.fromString("50000000-0000-0000-0000-000000000002")
  private val OtherUsersSession = UUID.fromString("50000000-0000-0000-0000-0000000000ff")
  private val Now = Instant.parse("2026-09-27T10:00:00Z")

  test("listing returns only this user's live sessions, newest first") {
    val setup = newSetup()
    val rows = setup.list.execute(UserId).unsafeRunSync()
    assertEquals(rows.map(_.id), List(Other, Current)) // Other created after Current
    assert(!rows.exists(_.id == OtherUsersSession))
  }

  test("revoking another session revokes it and writes an audit event") {
    val setup = newSetup()
    val result = setup.revokeOne.execute(UserId, Current, Other).unsafeRunSync()
    assertEquals(result, Right(()))
    assert(setup.sessions.isRevoked(Other))
    assert(!setup.sessions.isRevoked(Current))
    assertEquals(setup.audit.saved.map(_.action), List(AuditAction.AccountSessionRevoked))
  }

  test("revoking the current session is refused; logout is the way out") {
    val setup = newSetup()
    assertEquals(setup.revokeOne.execute(UserId, Current, Current).unsafeRunSync(), Left(AccountError.SessionIsCurrent))
    assert(!setup.sessions.isRevoked(Current))
    assertEquals(setup.audit.saved, List.empty)
  }

  test("revoking a session that is not this user's is a not-found with no audit") {
    val setup = newSetup()
    assertEquals(setup.revokeOne.execute(UserId, Current, OtherUsersSession).unsafeRunSync(),
      Left(AccountError.SessionNotFound))
    assert(!setup.sessions.isRevoked(OtherUsersSession))
    assertEquals(setup.audit.saved, List.empty)
  }

  test("revoke-others revokes every session but the current and audits once") {
    val setup = newSetup()
    val revoked = setup.revokeOthers.execute(UserId, Current).unsafeRunSync()
    assertEquals(revoked, 1)
    assert(setup.sessions.isRevoked(Other))
    assert(!setup.sessions.isRevoked(Current))
    assertEquals(setup.audit.saved.map(_.action), List(AuditAction.AccountOtherSessionsRevoked))
  }

  test("revoke-all revokes the current session too and audits once") {
    val setup = newSetup()
    val revoked = setup.revokeAll.execute(UserId).unsafeRunSync()
    assertEquals(revoked, 2)
    assert(setup.sessions.isRevoked(Current) && setup.sessions.isRevoked(Other))
    assertEquals(setup.audit.saved.map(_.action), List(AuditAction.AccountAllSessionsRevoked))
  }

  private def newSetup(): Setup = {
    val sessions = new FakeSessions(Map(
      Current -> UserId, Other -> UserId, OtherUsersSession -> OtherUser),
      created = Map(Current -> Now.minusSeconds(100), Other -> Now.minusSeconds(10), OtherUsersSession -> Now))
    val audit = new RecordingAudit
    val accountAudit = new AccountAudit[IO](new AuditRecorder[IO](audit, new FixedIds, new FixedTime), new FakeMemberships)
    val runner = new DirectRunner
    val time = new FixedTime
    Setup(
      new ListUserSessions[IO](sessions, runner, time),
      new RevokeUserSession[IO](sessions, new support.InMemorySecurityEvents, accountAudit, runner, time),
      new RevokeOtherUserSessions[IO](sessions, new support.InMemorySecurityEvents, accountAudit, runner, time),
      new RevokeAllUserSessions[IO](sessions, new support.InMemorySecurityEvents, accountAudit, runner, time),
      sessions, audit)
  }

  private final case class Setup(
    list: ListUserSessions[IO],
    revokeOne: RevokeUserSession[IO],
    revokeOthers: RevokeOtherUserSessions[IO],
    revokeAll: RevokeAllUserSessions[IO],
    sessions: FakeSessions,
    audit: RecordingAudit
  )

  private final class FakeSessions(owners: Map[UUID, UUID], created: Map[UUID, Instant]) extends AuthSessionRepository[IO] {
    private var revoked: Set[UUID] = Set.empty
    def isRevoked(id: UUID): Boolean = synchronized(revoked.contains(id))
    override def create(session: AuthSession): IO[Unit] = IO.unit
    override def findAuthenticatedSessionByTokenHash(tokenHash: String, now: Instant): IO[Option[AuthenticatedSession]] = IO.pure(None)
    override def revokeByTokenHash(tokenHash: String, now: Instant): IO[Unit] = IO.unit
    override def listActiveByUser(userId: UUID, now: Instant): IO[List[UserSessionSummary]] = IO(synchronized {
      owners.collect { case (id, owner) if owner == userId && !revoked.contains(id) =>
        UserSessionSummary(id, created(id), now.plusSeconds(3600)) }.toList.sortBy(_.createdAt).reverse
    })
    override def revokeByIdForUser(userId: UUID, sessionId: UUID, now: Instant): IO[Boolean] = IO(synchronized {
      if (owners.get(sessionId).contains(userId) && !revoked.contains(sessionId)) { revoked += sessionId; true } else false
    })
    override def revokeOthersForUser(userId: UUID, exceptSessionId: UUID, now: Instant): IO[Int] = IO(synchronized {
      val hit = owners.collect { case (id, owner) if owner == userId && id != exceptSessionId && !revoked.contains(id) => id }.toList
      revoked ++= hit; hit.size
    })
    override def revokeAllForUser(userId: UUID, now: Instant): IO[Int] = IO(synchronized {
      val hit = owners.collect { case (id, owner) if owner == userId && !revoked.contains(id) => id }.toList
      revoked ++= hit; hit.size
    })
  }

  private final class FakeMemberships extends OrganizationMembershipRepository[IO] {
    private val org = UUID.fromString("20000000-0000-0000-0000-00000000000a")
    override def findActiveRole(userId: UUID, organizationId: UUID): IO[Option[OrganizationRole]] = IO.pure(None)
    override def hasActiveMembership(userId: UUID, organizationId: UUID): IO[Boolean] = IO.pure(true)
    override def listActiveOrganizations(userId: UUID): IO[List[MyOrganization]] =
      IO.pure(List(MyOrganization(org, "org", "Org", OrganizationRole.Owner)))
    override def createIfMissing(membership: OrganizationMembership): IO[Unit] = IO.unit
  }

  private final class RecordingAudit extends AuditEventRepository[IO] {
    private var events: List[AuditEvent] = List.empty
    def saved: List[AuditEvent] = synchronized(events)
    override def save(event: AuditEvent): IO[Unit] = IO(synchronized { events = events :+ event })
    override def saveAll(values: List[AuditEvent]): IO[Unit] = values.traverse_(save)
    override def listByOrganization(organizationId: UUID, before: Option[AuditCursor], limit: Int): IO[List[AuditEvent]] = IO.pure(List.empty)
  }

  private final class DirectRunner extends TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }

  private final class FixedIds extends IdGenerator[IO] {
    private var counter = 0L
    override def nextId: IO[UUID] = IO { counter += 1; new UUID(0L, counter) }
  }

  private final class FixedTime extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(Now)
  }
}
