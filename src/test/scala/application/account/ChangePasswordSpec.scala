package ru.bitec.app.ops
package application.account

import application.audit.AuditRecorder
import application.auth.BCryptPasswordHasher
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
import domain.audit.{AuditAction, AuditCursor, AuditEvent, AuditTargetType}
import domain.auth.{AuthSession, AuthenticatedSession, OrganizationMembership, OrganizationRole, UserAccount, UserSessionSummary}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

/** Changing one's own password: current one checked, new one hashed by the real BCrypt path, the
  * update guarded by a compare-and-set, and the other sessions revoked atomically with it.
  */
final class ChangePasswordSpec extends FunSuite {

  private val hasher = new BCryptPasswordHasher
  private val OldPassword = "old-password-1"
  private val NewPassword = "new-strong-password-2"
  private val UserId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val OtherUserId = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val CurrentSession = UUID.fromString("50000000-0000-0000-0000-000000000001")
  private val OtherSession = UUID.fromString("50000000-0000-0000-0000-000000000002")
  private val OrgA = UUID.fromString("20000000-0000-0000-0000-00000000000a")
  private val OrgB = UUID.fromString("20000000-0000-0000-0000-00000000000b")

  test("the current password is checked, the new one is hashed, and the old one stops working") {
    val setup = newSetup()
    val result = setup.change.execute(UserId, CurrentSession, OldPassword, NewPassword).unsafeRunSync()

    assertEquals(result, Right(()))
    val stored = setup.users.hashOf(UserId)
    assertNotEquals(stored, setup.originalHash)
    assert(hasher.verify(NewPassword, stored).unsafeRunSync(), "the new password must verify")
    assert(!hasher.verify(OldPassword, stored).unsafeRunSync(), "the old password must not verify")
  }

  test("a successful change revokes every other session but keeps the current one") {
    val setup = newSetup()
    setup.change.execute(UserId, CurrentSession, OldPassword, NewPassword).unsafeRunSync()

    assertEquals(setup.sessions.revokedOthersExcept, List(CurrentSession))
  }

  test("a security audit event is written for each organization, and carries no secret") {
    val setup = newSetup(orgs = List(OrgA, OrgB))
    setup.change.execute(UserId, CurrentSession, OldPassword, NewPassword).unsafeRunSync()

    val events = setup.audit.saved
    assertEquals(events.map(_.action), List(AuditAction.AccountPasswordChanged, AuditAction.AccountPasswordChanged))
    assertEquals(events.map(_.organizationId).toSet, Set(OrgA, OrgB))
    assertEquals(events.map(_.targetType).distinct, List(AuditTargetType.Account))
    val text = events.map(_.toString).mkString
    assert(!text.contains(OldPassword) && !text.contains(NewPassword), text)
    assert(!text.contains(setup.users.hashOf(UserId)), "the hash must not appear in the journal")
  }

  test("a wrong current password changes nothing, revokes nothing, writes no audit") {
    val setup = newSetup()
    val result = setup.change.execute(UserId, CurrentSession, "not-the-current-one", NewPassword).unsafeRunSync()

    assertEquals(result, Left(AccountError.CurrentPasswordInvalid))
    assertEquals(setup.users.hashOf(UserId), setup.originalHash)
    assertEquals(setup.sessions.revokedOthersExcept, List.empty)
    assertEquals(setup.audit.saved, List.empty)
  }

  test("a new password that is too short is refused and changes nothing") {
    val setup = newSetup()
    val result = setup.change.execute(UserId, CurrentSession, OldPassword, "short").unsafeRunSync()

    assertEquals(result, Left(AccountError.PasswordTooShort))
    assertEquals(setup.users.hashOf(UserId), setup.originalHash)
    assertEquals(setup.audit.saved, List.empty)
  }

  test("a new password equal to the current one is refused") {
    val setup = newSetup()
    assertEquals(setup.change.execute(UserId, CurrentSession, OldPassword, OldPassword).unsafeRunSync(),
      Left(AccountError.PasswordSameAsCurrent))
    assertEquals(setup.users.hashOf(UserId), setup.originalHash)
  }

  test("only the named account is touched: another user's password is left alone") {
    val setup = newSetup()
    val othersBefore = setup.users.hashOf(OtherUserId)
    setup.change.execute(UserId, CurrentSession, OldPassword, NewPassword).unsafeRunSync()
    assertEquals(setup.users.hashOf(OtherUserId), othersBefore)
  }

  test("a compare-and-set that no longer matches is refused without success, audit or revocation") {
    // The password moved on between the verify and the write, so the CAS affects no row.
    val setup = newSetup(casFails = true)
    val result = setup.change.execute(UserId, CurrentSession, OldPassword, NewPassword).unsafeRunSync()

    assertEquals(result, Left(AccountError.AccountStateChanged))
    assertEquals(setup.audit.saved, List.empty)
    assertEquals(setup.sessions.revokedOthersExcept, List.empty)
  }

  test("an audit failure rolls the password change and the session revocation back together") {
    val setup = newSetup(failAudit = true)
    val outcome = setup.change.execute(UserId, CurrentSession, OldPassword, NewPassword).attempt.unsafeRunSync()

    assert(outcome.isLeft, "the change must fail when its audit entry cannot be written")
    assertEquals(setup.users.hashOf(UserId), setup.originalHash)
    assertEquals(setup.sessions.revokedOthersExcept, List.empty)
  }

  // -------------------------------------------------------------------------------------------

  private def newSetup(
    orgs: List[UUID] = List(OrgA),
    failAudit: Boolean = false,
    casFails: Boolean = false
  ): Setup = {
    val originalHash = hasher.hash(OldPassword).unsafeRunSync()
    val users = new FakeUsers(Map(
      UserId -> account(UserId, originalHash),
      OtherUserId -> account(OtherUserId, hasher.hash("someone-elses-pw").unsafeRunSync())
    ), casFails)
    val sessions = new FakeSessions
    val memberships = new FakeMemberships(orgs)
    val auditRepo = new RecordingAudit(failAudit)
    val accountAudit = new AccountAudit[IO](new AuditRecorder[IO](auditRepo, new FixedIds, new FixedTime), memberships)
    val runner = new TransactionalRunner(users, sessions)
    val change = new ChangePassword[IO](users, sessions, new support.InMemorySecurityEvents, accountAudit, runner, hasher, new FixedTime)
    Setup(change, users, sessions, auditRepo, originalHash)
  }

  private def account(id: UUID, hash: String): UserAccount =
    UserAccount(id, s"$id@example.test", hash, "User", isActive = true, Instant.EPOCH, Instant.EPOCH)

  private final case class Setup(
    change: ChangePassword[IO],
    users: FakeUsers,
    sessions: FakeSessions,
    audit: RecordingAudit,
    originalHash: String
  )

  private final class FakeUsers(initial: Map[UUID, UserAccount], casFails: Boolean) extends UserAccountRepository[IO] {
    private var state: Map[UUID, UserAccount] = initial
    def hashOf(id: UUID): String = synchronized(state(id).passwordHash)
    def snapshot(): Map[UUID, UserAccount] = synchronized(state)
    def restore(value: Map[UUID, UserAccount]): Unit = synchronized { state = value }
    override def findByEmail(email: String): IO[Option[UserAccount]] = IO.pure(None)
    override def findActiveById(id: UUID): IO[Option[UserAccount]] = IO(synchronized(state.get(id).filter(_.isActive)))
    override def createIfMissing(user: UserAccount): IO[Unit] = IO.unit
    override def compareAndSetPasswordHash(id: UUID, expectedPasswordHash: String, newPasswordHash: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized {
        if (casFails) false
        else state.get(id).filter(u => u.isActive && u.passwordHash == expectedPasswordHash) match {
          case Some(u) => state = state.updated(id, u.copy(passwordHash = newPasswordHash, updatedAt = updatedAt)); true
          case None => false
        }
      })
    override def updateDisplayName(id: UUID, displayName: String, updatedAt: Instant): IO[Boolean] = IO.pure(false)
  }

  private final class FakeSessions extends AuthSessionRepository[IO] {
    private var revokedExcept: List[UUID] = List.empty
    def revokedOthersExcept: List[UUID] = synchronized(revokedExcept)
    def snapshot(): List[UUID] = synchronized(revokedExcept)
    def restore(value: List[UUID]): Unit = synchronized { revokedExcept = value }
    override def create(session: AuthSession): IO[Unit] = IO.unit
    override def findAuthenticatedSessionByTokenHash(tokenHash: String, now: Instant): IO[Option[AuthenticatedSession]] = IO.pure(None)
    override def revokeByTokenHash(tokenHash: String, now: Instant): IO[Unit] = IO.unit
    override def listActiveByUser(userId: UUID, now: Instant): IO[List[UserSessionSummary]] = IO.pure(List.empty)
    override def revokeByIdForUser(userId: UUID, sessionId: UUID, now: Instant): IO[Boolean] = IO.pure(true)
    override def revokeOthersForUser(userId: UUID, exceptSessionId: UUID, now: Instant): IO[Int] =
      IO(synchronized { revokedExcept = revokedExcept :+ exceptSessionId; 1 })
    override def revokeAllForUser(userId: UUID, now: Instant): IO[Int] = IO.pure(0)
  }

  private final class FakeMemberships(orgs: List[UUID]) extends OrganizationMembershipRepository[IO] {
    override def findActiveRole(userId: UUID, organizationId: UUID): IO[Option[OrganizationRole]] = IO.pure(None)
    override def hasActiveMembership(userId: UUID, organizationId: UUID): IO[Boolean] = IO.pure(orgs.contains(organizationId))
    override def listActiveOrganizations(userId: UUID): IO[List[MyOrganization]] =
      IO.pure(orgs.map(id => MyOrganization(id, "org", "Org", OrganizationRole.Owner)))
    override def createIfMissing(membership: OrganizationMembership): IO[Unit] = IO.unit
  }

  private final class RecordingAudit(fail: Boolean) extends AuditEventRepository[IO] {
    private var events: List[AuditEvent] = List.empty
    def saved: List[AuditEvent] = synchronized(events)
    override def save(event: AuditEvent): IO[Unit] =
      if (fail) IO.raiseError(new IllegalStateException("audit unavailable"))
      else IO(synchronized { events = events :+ event })
    override def saveAll(values: List[AuditEvent]): IO[Unit] = values.traverse_(save)
    override def listByOrganization(organizationId: UUID, before: Option[AuditCursor], limit: Int): IO[List[AuditEvent]] = IO.pure(List.empty)
  }

  /** Snapshots both stores before a program and restores them if it fails, modelling the database
    * transaction that wraps the compare-and-set, the revocation and the audit.
    */
  private final class TransactionalRunner(users: FakeUsers, sessions: FakeSessions) extends TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] =
      IO((users.snapshot(), sessions.snapshot())).flatMap { case (u, s) =>
        program.handleErrorWith(error => IO { users.restore(u); sessions.restore(s) } *> IO.raiseError(error))
      }
  }

  private final class FixedIds extends IdGenerator[IO] {
    private var counter = 0L
    override def nextId: IO[UUID] = IO { counter += 1; new UUID(0L, counter) }
  }

  private final class FixedTime extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(Instant.parse("2026-09-27T10:00:00Z"))
  }
}
