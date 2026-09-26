package ru.bitec.app.ops
package application.account

import application.audit.AuditRecorder
import application.auth.BCryptPasswordHasher
import application.port.{
  AuditEventRepository,
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
import domain.auth.{OrganizationMembership, OrganizationRole, UserAccount}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

/** Changing one's own password: the current one is checked, the new one is validated and hashed by
  * the real BCrypt path, and nothing but the account it names is touched.
  */
final class ChangePasswordSpec extends FunSuite {

  // The real hasher, so "the new password verifies and the old one no longer does" is genuine.
  private val hasher = new BCryptPasswordHasher
  private val OldPassword = "old-password-1"
  private val NewPassword = "new-strong-password-2"
  private val UserId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val OtherUserId = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val OrgA = UUID.fromString("20000000-0000-0000-0000-00000000000a")
  private val OrgB = UUID.fromString("20000000-0000-0000-0000-00000000000b")

  test("the current password is checked, the new one is hashed, and the old one stops working") {
    val fixture = newSetup()
    val result = fixture.changePassword.execute(UserId, OldPassword, NewPassword).unsafeRunSync()

    assertEquals(result, Right(()))
    val stored = fixture.users.hashOf(UserId)
    assertNotEquals(stored, fixture.originalHash)
    assert(hasher.verify(NewPassword, stored).unsafeRunSync(), "the new password must verify")
    assert(!hasher.verify(OldPassword, stored).unsafeRunSync(), "the old password must not verify")
  }

  test("a security audit event is written for each organization, and carries no secret") {
    val fixture = newSetup(orgs = List(OrgA, OrgB))
    fixture.changePassword.execute(UserId, OldPassword, NewPassword).unsafeRunSync()

    val events = fixture.audit.saved
    assertEquals(events.map(_.action), List(AuditAction.AccountPasswordChanged, AuditAction.AccountPasswordChanged))
    assertEquals(events.map(_.organizationId).toSet, Set(OrgA, OrgB))
    assertEquals(events.map(_.targetType).distinct, List(AuditTargetType.Account))
    assertEquals(events.map(_.targetId).distinct, List(Some(UserId)))
    // The event type has no field that could carry a password or a hash; assert nothing leaked
    // into any string it does carry.
    val text = events.map(_.toString).mkString
    assert(!text.contains(OldPassword) && !text.contains(NewPassword), text)
    assert(!text.contains(fixture.users.hashOf(UserId)), "the hash must not appear in the journal")
  }

  test("a wrong current password changes nothing and writes no audit") {
    val fixture = newSetup()
    val result = fixture.changePassword.execute(UserId, "not-the-current-one", NewPassword).unsafeRunSync()

    assertEquals(result, Left(AccountError.CurrentPasswordInvalid))
    assertEquals(fixture.users.hashOf(UserId), fixture.originalHash)
    assertEquals(fixture.audit.saved, List.empty)
  }

  test("a new password that is too short is refused and changes nothing") {
    val fixture = newSetup()
    val result = fixture.changePassword.execute(UserId, OldPassword, "short").unsafeRunSync()

    assertEquals(result, Left(AccountError.PasswordTooShort))
    assertEquals(fixture.users.hashOf(UserId), fixture.originalHash)
    assertEquals(fixture.audit.saved, List.empty)
  }

  test("a new password equal to the current one is refused") {
    val fixture = newSetup()
    val result = fixture.changePassword.execute(UserId, OldPassword, OldPassword).unsafeRunSync()

    assertEquals(result, Left(AccountError.PasswordSameAsCurrent))
    assertEquals(fixture.users.hashOf(UserId), fixture.originalHash)
  }

  test("only the named account is touched: another user's password is left alone") {
    val fixture = newSetup()
    val othersHashBefore = fixture.users.hashOf(OtherUserId)
    fixture.changePassword.execute(UserId, OldPassword, NewPassword).unsafeRunSync()

    assertEquals(fixture.users.hashOf(OtherUserId), othersHashBefore)
  }

  test("an audit failure rolls the password change back") {
    val fixture = newSetup(failAudit = true)
    val outcome = fixture.changePassword.execute(UserId, OldPassword, NewPassword).attempt.unsafeRunSync()

    assert(outcome.isLeft, "the change must fail when its audit entry cannot be written")
    // The write ran inside one transaction, so the update is undone with the audit.
    assertEquals(fixture.users.hashOf(UserId), fixture.originalHash)
  }

  // -------------------------------------------------------------------------------------------

  private def newSetup(orgs: List[UUID] = List(OrgA), failAudit: Boolean = false): Setup = {
    val originalHash = hasher.hash(OldPassword).unsafeRunSync()
    val users = new FakeUsers(Map(
      UserId -> account(UserId, originalHash),
      OtherUserId -> account(OtherUserId, hasher.hash("someone-elses-pw").unsafeRunSync())
    ))
    val memberships = new FakeMemberships(orgs)
    val auditRepo = new RecordingAudit(failAudit)
    val recorder = new AuditRecorder[IO](auditRepo, new FixedIds, new FixedTime)
    val runner = new TransactionalRunner(users)
    val changePassword = new ChangePassword[IO](users, memberships, recorder, runner, hasher, new FixedTime)
    Setup(changePassword, users, auditRepo, originalHash)
  }

  private def account(id: UUID, hash: String): UserAccount =
    UserAccount(id, s"$id@example.test", hash, "User", isActive = true, Instant.EPOCH, Instant.EPOCH)

  private final case class Setup(
    changePassword: ChangePassword[IO],
    users: FakeUsers,
    audit: RecordingAudit,
    originalHash: String
  )

  /** In-memory accounts with a snapshot the transactional runner uses to model rollback. */
  private final class FakeUsers(initial: Map[UUID, UserAccount]) extends UserAccountRepository[IO] {
    private var state: Map[UUID, UserAccount] = initial
    def hashOf(id: UUID): String = synchronized(state(id).passwordHash)
    def snapshot(): Map[UUID, UserAccount] = synchronized(state)
    def restore(value: Map[UUID, UserAccount]): Unit = synchronized { state = value }

    override def findByEmail(email: String): IO[Option[UserAccount]] =
      IO(synchronized(state.values.find(_.email == email)))
    override def findActiveById(id: UUID): IO[Option[UserAccount]] =
      IO(synchronized(state.get(id).filter(_.isActive)))
    override def createIfMissing(user: UserAccount): IO[Unit] = IO.unit
    override def updatePasswordHash(id: UUID, passwordHash: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized {
        state.get(id).filter(_.isActive) match {
          case Some(user) => state = state.updated(id, user.copy(passwordHash = passwordHash, updatedAt = updatedAt)); true
          case None => false
        }
      })
    override def updateDisplayName(id: UUID, displayName: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized {
        state.get(id).filter(_.isActive) match {
          case Some(user) => state = state.updated(id, user.copy(displayName = displayName, updatedAt = updatedAt)); true
          case None => false
        }
      })
  }

  private final class FakeMemberships(orgs: List[UUID]) extends OrganizationMembershipRepository[IO] {
    override def findActiveRole(userId: UUID, organizationId: UUID): IO[Option[OrganizationRole]] =
      IO.pure(Option.when(orgs.contains(organizationId))(OrganizationRole.Owner))
    override def hasActiveMembership(userId: UUID, organizationId: UUID): IO[Boolean] =
      IO.pure(orgs.contains(organizationId))
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
    override def listByOrganization(organizationId: UUID, before: Option[AuditCursor], limit: Int): IO[List[AuditEvent]] =
      IO.pure(List.empty)
  }

  /** Snapshots the account state before a program and restores it if the program fails, so a
    * failing audit undoes the password update exactly as a database transaction would.
    */
  private final class TransactionalRunner(users: FakeUsers) extends TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] =
      IO(users.snapshot()).flatMap(before =>
        program.handleErrorWith(error => IO(users.restore(before)) *> IO.raiseError(error)))
  }

  private final class FixedIds extends IdGenerator[IO] {
    private var counter = 0L
    override def nextId: IO[UUID] = IO { counter += 1; new UUID(0L, counter) }
  }

  private final class FixedTime extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(Instant.parse("2026-09-27T10:00:00Z"))
  }
}
