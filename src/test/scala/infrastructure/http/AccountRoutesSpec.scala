package ru.bitec.app.ops
package infrastructure.http

import application.account.{
  AccountAudit,
  ChangePassword,
  ListUserSessions,
  RevokeAllUserSessions,
  RevokeOtherUserSessions,
  RevokeUserSession,
  UpdateAccountProfile
}
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
import domain.audit.{AuditCursor, AuditEvent}
import domain.auth.{AuthSession, AuthenticatedSession, AuthenticatedUser, OrganizationMembership, OrganizationRole, UserAccount, UserSessionSummary}
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID

/** The account HTTP surface: the session decides whose account and which is current, the body only
  * says what to change, and a refusal is a stable code with no internals or tokens.
  */
final class AccountRoutesSpec extends FunSuite {

  private val hasher = new BCryptPasswordHasher
  private val SessionUser = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val OtherUser = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private val CurrentSession = UUID.fromString("50000000-0000-0000-0000-000000000001")
  private val OtherSession = UUID.fromString("50000000-0000-0000-0000-000000000002")
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.account")

  test("a change-password request acts on the session account and ignores a userId in the body") {
    val setup = newSetup()
    val body = Json.obj(
      "currentPassword" -> Json.fromString("old-password-1"),
      "newPassword" -> Json.fromString("new-strong-password-2"),
      "userId" -> Json.fromString(OtherUser.toString))
    val response = setup.routes.change(session(),
      Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/account/change-password")).withEntity(body))
      .unsafeRunSync()

    assertEquals(response.status, Status.NoContent)
    assert(hasher.verify("new-strong-password-2", setup.users.hashOf(SessionUser)).unsafeRunSync())
    assertEquals(setup.users.hashOf(OtherUser), setup.otherHash)
  }

  test("a wrong current password answers 400 with a stable code and no internals") {
    val setup = newSetup()
    val response = setup.routes.change(session(),
      Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/account/change-password"))
        .withEntity(Json.obj("currentPassword" -> Json.fromString("wrong"),
          "newPassword" -> Json.fromString("new-strong-password-2")))).unsafeRunSync()

    assertEquals(response.status, Status.BadRequest)
    val json = response.as[Json].unsafeRunSync()
    assertEquals(json.hcursor.get[String]("code"), Right("CURRENT_PASSWORD_INVALID"))
    val text = json.noSpaces.toLowerCase
    assert(!text.contains("hash") && !text.contains("bcrypt"), text)
  }

  test("a malformed body is a bad request") {
    val setup = newSetup()
    val response = setup.routes.change(session(),
      Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/account/change-password"))
        .withEntity(Json.obj("currentPassword" -> Json.fromString("x")))).unsafeRunSync()

    assertEquals(response.status, Status.BadRequest)
    assertEquals(response.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("INVALID_REQUEST"))
  }

  test("a display-name update answers with the account and never a password hash") {
    val setup = newSetup()
    val response = setup.routes.updateDisplayName(user(SessionUser),
      Request[IO](Method.PATCH, Uri.unsafeFromString("/api/v1/account"))
        .withEntity(Json.obj("displayName" -> Json.fromString("New Name")))).unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    val json = response.as[Json].unsafeRunSync()
    assertEquals(json.hcursor.get[String]("displayName"), Right("New Name"))
    assert(json.hcursor.get[String]("passwordHash").isLeft, "the response must not carry a hash")
  }

  test("the session list marks the current session and carries no token") {
    val setup = newSetup()
    val response = setup.routes.sessions(session()).unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    val rows = response.as[Json].unsafeRunSync().asArray.getOrElse(Vector.empty)
    val current = rows.map(r => (r.hcursor.get[String]("id").toOption, r.hcursor.get[Boolean]("current").toOption))
    assertEquals(current.toSet, Set[(Option[String], Option[Boolean])](
      (Some(CurrentSession.toString), Some(true)),
      (Some(OtherSession.toString), Some(false))))
    // No token field of any kind leaves the API.
    assert(rows.forall(r => r.hcursor.get[String]("tokenHash").isLeft && r.hcursor.get[String]("token").isLeft))
  }

  test("revoking another session succeeds and revoking the current one is refused") {
    val setup = newSetup()
    val other = setup.routes.revoke(session(), OtherSession.toString).unsafeRunSync()
    assertEquals(other.status, Status.NoContent)

    val current = setup.routes.revoke(session(), CurrentSession.toString).unsafeRunSync()
    assertEquals(current.status, Status.BadRequest)
    assertEquals(current.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("SESSION_IS_CURRENT"))
  }

  test("an unknown or malformed session id is a not-found without revealing existence") {
    val setup = newSetup()
    assertEquals(setup.routes.revoke(session(), "not-a-uuid").unsafeRunSync().status, Status.NotFound)
    assertEquals(setup.routes.revoke(session(), UUID.randomUUID().toString).unsafeRunSync().status, Status.NotFound)
  }

  test("revoke-others answers 204 and keeps the current cookie") {
    val setup = newSetup()
    val response = setup.routes.revokeOthers(session()).unsafeRunSync()
    assertEquals(response.status, Status.NoContent)
    assert(response.cookies.forall(_.name != AuthRoutes.CookieName), "revoke-others must not clear the cookie")
  }

  test("revoke-all answers 204 and clears the session cookie") {
    val setup = newSetup()
    val response = setup.routes.revokeAll(session()).unsafeRunSync()
    assertEquals(response.status, Status.NoContent)
    val cleared = response.cookies.find(_.name == AuthRoutes.CookieName)
    assert(cleared.exists(_.content == ""), "the session cookie must be cleared")
  }

  private def session(): AuthenticatedSession = AuthenticatedSession(user(SessionUser), CurrentSession)
  private def user(id: UUID): AuthenticatedUser = AuthenticatedUser(id, s"$id@example.test", "User")

  private def newSetup(): Setup = {
    val sessionHash = hasher.hash("old-password-1").unsafeRunSync()
    val otherHash = hasher.hash("someone-else").unsafeRunSync()
    val users = new FakeUsers(Map(SessionUser -> account(SessionUser, sessionHash), OtherUser -> account(OtherUser, otherHash)))
    val sessions = new FakeSessions(SessionUser, List(CurrentSession, OtherSession))
    val memberships = new FakeMemberships
    val accountAudit = new AccountAudit[IO](new AuditRecorder[IO](new NoAudit, new FixedIds, new FixedTime), memberships)
    val runner = new DirectRunner
    val time = new FixedTime
    val routes = new AccountRoutes[IO](
      new ChangePassword[IO](users, sessions, accountAudit, runner, hasher, time),
      new UpdateAccountProfile[IO](users, accountAudit, runner, time),
      new ListUserSessions[IO](sessions, runner, time),
      new RevokeUserSession[IO](sessions, accountAudit, runner, time),
      new RevokeOtherUserSessions[IO](sessions, accountAudit, runner, time),
      new RevokeAllUserSessions[IO](sessions, accountAudit, runner, time),
      AuthSettings(3600, secureCookie = false),
      logger)
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
    override def compareAndSetPasswordHash(id: UUID, expectedPasswordHash: String, newPasswordHash: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized(state.get(id).filter(u => u.isActive && u.passwordHash == expectedPasswordHash) match {
        case Some(u) => state = state.updated(id, u.copy(passwordHash = newPasswordHash)); true
        case None => false
      }))
    override def updateDisplayName(id: UUID, displayName: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized(state.get(id).filter(_.isActive) match {
        case Some(u) => state = state.updated(id, u.copy(displayName = displayName)); true
        case None => false
      }))
  }

  private final class FakeSessions(ownerId: UUID, ids: List[UUID]) extends AuthSessionRepository[IO] {
    override def create(session: AuthSession): IO[Unit] = IO.unit
    override def findAuthenticatedSessionByTokenHash(tokenHash: String, now: Instant): IO[Option[AuthenticatedSession]] = IO.pure(None)
    override def revokeByTokenHash(tokenHash: String, now: Instant): IO[Unit] = IO.unit
    override def listActiveByUser(userId: UUID, now: Instant): IO[List[UserSessionSummary]] =
      IO.pure(if (userId == ownerId) ids.map(id => UserSessionSummary(id, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600))) else List.empty)
    override def revokeByIdForUser(userId: UUID, sessionId: UUID, now: Instant): IO[Boolean] =
      IO.pure(userId == ownerId && ids.contains(sessionId))
    override def revokeOthersForUser(userId: UUID, exceptSessionId: UUID, now: Instant): IO[Int] = IO.pure(1)
    override def revokeAllForUser(userId: UUID, now: Instant): IO[Int] = IO.pure(2)
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
    override def now: IO[Instant] = IO.pure(Instant.parse("2026-09-27T10:00:00Z"))
  }
}
