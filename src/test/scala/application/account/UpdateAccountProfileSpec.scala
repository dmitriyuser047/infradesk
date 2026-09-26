package ru.bitec.app.ops
package application.account

import application.audit.AuditRecorder
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

/** Renaming one's own account: the value is trimmed and validated, the change is journalled, and a
  * bad value changes nothing.
  */
final class UpdateAccountProfileSpec extends FunSuite {

  private val UserId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val Org = UUID.fromString("20000000-0000-0000-0000-00000000000a")

  test("a valid name is trimmed, stored, journalled and returned") {
    val fixture = newSetup()
    val result = fixture.update.execute(UserId, "  Dmitriy Ulyanov  ").unsafeRunSync()

    assertEquals(result, Right("Dmitriy Ulyanov"))
    assertEquals(fixture.users.nameOf(UserId), "Dmitriy Ulyanov")
    assertEquals(fixture.audit.saved.map(_.action), List(AuditAction.AccountProfileUpdated))
    assertEquals(fixture.audit.saved.map(_.targetType), List(AuditTargetType.Account))
    assertEquals(fixture.audit.saved.map(_.targetId), List(Some(UserId)))
  }

  test("an empty name is refused and changes nothing") {
    val fixture = newSetup()
    val result = fixture.update.execute(UserId, "   ").unsafeRunSync()

    assertEquals(result, Left(AccountError.DisplayNameEmpty))
    assertEquals(fixture.users.nameOf(UserId), "Original")
    assertEquals(fixture.audit.saved, List.empty)
  }

  test("a name with control characters is refused") {
    val fixture = newSetup()
    val result = fixture.update.execute(UserId, "bad\nname").unsafeRunSync()

    assertEquals(result, Left(AccountError.DisplayNameInvalidCharacters))
    assertEquals(fixture.users.nameOf(UserId), "Original")
  }

  test("a name past the maximum length is refused") {
    val fixture = newSetup()
    val result = fixture.update.execute(UserId, "x" * (AccountValidation.MaxDisplayNameLength + 1)).unsafeRunSync()

    assertEquals(result, Left(AccountError.DisplayNameTooLong))
  }

  private def newSetup(): Setup = {
    val users = new FakeUsers(UserAccount(UserId, "u@example.test", "hash", "Original",
      isActive = true, Instant.EPOCH, Instant.EPOCH))
    val auditRepo = new RecordingAudit
    val recorder = new AuditRecorder[IO](auditRepo, new FixedIds, new FixedTime)
    val update = new UpdateAccountProfile[IO](users, new FakeMemberships(Org), recorder,
      new DirectRunner, new FixedTime)
    Setup(update, users, auditRepo)
  }

  private final case class Setup(update: UpdateAccountProfile[IO], users: FakeUsers, audit: RecordingAudit)

  private final class FakeUsers(initial: UserAccount) extends UserAccountRepository[IO] {
    private var user: UserAccount = initial
    def nameOf(id: UUID): String = synchronized(user.displayName)
    override def findByEmail(email: String): IO[Option[UserAccount]] = IO.pure(None)
    override def findActiveById(id: UUID): IO[Option[UserAccount]] = IO(synchronized(Option(user).filter(_.id == id)))
    override def createIfMissing(u: UserAccount): IO[Unit] = IO.unit
    override def updatePasswordHash(id: UUID, passwordHash: String, updatedAt: Instant): IO[Boolean] = IO.pure(false)
    override def updateDisplayName(id: UUID, displayName: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized {
        if (user.id == id && user.isActive) { user = user.copy(displayName = displayName, updatedAt = updatedAt); true }
        else false
      })
  }

  private final class FakeMemberships(org: UUID) extends OrganizationMembershipRepository[IO] {
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
    override def listByOrganization(organizationId: UUID, before: Option[AuditCursor], limit: Int): IO[List[AuditEvent]] =
      IO.pure(List.empty)
  }

  private final class DirectRunner extends TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }

  private final class FixedIds extends IdGenerator[IO] {
    private var counter = 0L
    override def nextId: IO[UUID] = IO { counter += 1; new UUID(0L, counter) }
  }

  private final class FixedTime extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(Instant.parse("2026-09-27T10:00:00Z"))
  }
}
