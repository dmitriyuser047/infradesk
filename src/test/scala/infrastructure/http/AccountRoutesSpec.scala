package ru.bitec.app.ops
package infrastructure.http

import application.account.{ChangePassword, UpdateAccountProfile}
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
import domain.audit.{AuditCursor, AuditEvent}
import domain.auth.{AuthenticatedUser, OrganizationMembership, OrganizationRole, UserAccount}
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID

/** The account HTTP surface: the session decides whose account, the body only says what to change,
  * and a refusal is a stable code with no internals.
  */
final class AccountRoutesSpec extends FunSuite {

  private val hasher = new BCryptPasswordHasher
  private val SessionUser = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val OtherUser = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.account")

  test("a change-password request acts on the session account and ignores a userId in the body") {
    val fixture = newSetup()
    // The body carries an extra userId naming another account; it must be ignored entirely.
    val body = Json.obj(
      "currentPassword" -> Json.fromString("old-password-1"),
      "newPassword" -> Json.fromString("new-strong-password-2"),
      "userId" -> Json.fromString(OtherUser.toString)
    )
    val response = fixture.routes.change(user(SessionUser),
      Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/account/change-password")).withEntity(body))
      .unsafeRunSync()

    assertEquals(response.status, Status.NoContent)
    // The session account changed; the account named in the body did not.
    assert(hasher.verify("new-strong-password-2", fixture.users.hashOf(SessionUser)).unsafeRunSync())
    assertEquals(fixture.users.hashOf(OtherUser), fixture.otherHash)
  }

  test("a wrong current password answers 400 with a stable code and no internals") {
    val fixture = newSetup()
    val response = fixture.routes.change(user(SessionUser),
      Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/account/change-password"))
        .withEntity(Json.obj(
          "currentPassword" -> Json.fromString("wrong"),
          "newPassword" -> Json.fromString("new-strong-password-2"))))
      .unsafeRunSync()

    assertEquals(response.status, Status.BadRequest)
    val json = response.as[Json].unsafeRunSync()
    assertEquals(json.hcursor.get[String]("code"), Right("CURRENT_PASSWORD_INVALID"))
    // The response says nothing about hashes, passwords or the account state.
    val text = json.noSpaces.toLowerCase
    assert(!text.contains("hash") && !text.contains("bcrypt"), text)
  }

  test("a malformed body is a bad request") {
    val fixture = newSetup()
    val response = fixture.routes.change(user(SessionUser),
      Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/account/change-password"))
        .withEntity(Json.obj("currentPassword" -> Json.fromString("x"))))
      .unsafeRunSync()

    assertEquals(response.status, Status.BadRequest)
    assertEquals(response.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("INVALID_REQUEST"))
  }

  test("a display-name update answers with the account and never a password hash") {
    val fixture = newSetup()
    val response = fixture.routes.updateDisplayName(user(SessionUser),
      Request[IO](Method.PATCH, Uri.unsafeFromString("/api/v1/account"))
        .withEntity(Json.obj("displayName" -> Json.fromString("New Name"))))
      .unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    val json = response.as[Json].unsafeRunSync()
    assertEquals(json.hcursor.get[String]("displayName"), Right("New Name"))
    assertEquals(json.hcursor.get[String]("id"), Right(SessionUser.toString))
    assert(json.hcursor.get[String]("passwordHash").isLeft, "the response must not carry a hash")
  }

  private def user(id: UUID): AuthenticatedUser = AuthenticatedUser(id, s"$id@example.test", "User")

  private def newSetup(): Setup = {
    val sessionHash = hasher.hash("old-password-1").unsafeRunSync()
    val otherHash = hasher.hash("someone-else").unsafeRunSync()
    val users = new FakeUsers(Map(
      SessionUser -> account(SessionUser, sessionHash),
      OtherUser -> account(OtherUser, otherHash)
    ))
    val memberships = new FakeMemberships
    val recorder = new AuditRecorder[IO](new NoAudit, new FixedIds, new FixedTime)
    val runner = new DirectRunner
    val routes = new AccountRoutes[IO](
      new ChangePassword[IO](users, memberships, recorder, runner, hasher, new FixedTime),
      new UpdateAccountProfile[IO](users, memberships, recorder, runner, new FixedTime),
      logger
    )
    Setup(routes, users, otherHash)
  }

  private def account(id: UUID, hash: String): UserAccount =
    UserAccount(id, s"$id@example.test", hash, "User", isActive = true, Instant.EPOCH, Instant.EPOCH)

  private final case class Setup(routes: AccountRoutes[IO], users: FakeUsers, otherHash: String)

  private final class FakeUsers(initial: Map[UUID, UserAccount]) extends UserAccountRepository[IO] {
    private var state: Map[UUID, UserAccount] = initial
    def hashOf(id: UUID): String = synchronized(state(id).passwordHash)
    override def findByEmail(email: String): IO[Option[UserAccount]] = IO.pure(None)
    override def findActiveById(id: UUID): IO[Option[UserAccount]] = IO(synchronized(state.get(id).filter(_.isActive)))
    override def createIfMissing(user: UserAccount): IO[Unit] = IO.unit
    override def updatePasswordHash(id: UUID, passwordHash: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized(state.get(id).filter(_.isActive) match {
        case Some(u) => state = state.updated(id, u.copy(passwordHash = passwordHash)); true
        case None => false
      }))
    override def updateDisplayName(id: UUID, displayName: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized(state.get(id).filter(_.isActive) match {
        case Some(u) => state = state.updated(id, u.copy(displayName = displayName)); true
        case None => false
      }))
  }

  private final class FakeMemberships extends OrganizationMembershipRepository[IO] {
    private val org = UUID.fromString("20000000-0000-0000-0000-00000000000a")
    override def findActiveRole(userId: UUID, organizationId: UUID): IO[Option[OrganizationRole]] = IO.pure(None)
    override def hasActiveMembership(userId: UUID, organizationId: UUID): IO[Boolean] = IO.pure(true)
    override def listActiveOrganizations(userId: UUID): IO[List[MyOrganization]] =
      IO.pure(List(MyOrganization(org, "org", "Org", OrganizationRole.Owner)))
    override def createIfMissing(membership: OrganizationMembership): IO[Unit] = IO.unit
  }

  private final class NoAudit extends AuditEventRepository[IO] {
    override def save(event: AuditEvent): IO[Unit] = IO.unit
    override def saveAll(values: List[AuditEvent]): IO[Unit] = IO.unit
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
