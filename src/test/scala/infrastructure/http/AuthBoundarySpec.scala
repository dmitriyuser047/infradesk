package ru.bitec.app.ops
package infrastructure.http

import application.auth.{Authentication, BCryptPasswordHasher, Login, SessionTokens}
import application.port.{AuthSessionRepository, MyOrganization, OrganizationMembershipRepository, TransactionRunner, UserAccountRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.{AuthSession, AuthenticatedUser, OrganizationMembership, OrganizationRole, UserAccount}
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._
import org.http4s.HttpRoutes
import org.typelevel.ci.CIString

import java.time.Instant
import java.util.UUID

final class AuthBoundarySpec extends FunSuite {
  import CirceEntityDecoder._
  import CirceEntityEncoder._

  private val userId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val orgA = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val orgB = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val now = Instant.now()
  private val passwordHasher = new BCryptPasswordHasher
  private val tokens = new SessionTokens
  private lazy val user = UserAccount(
    userId, "member@example.com", passwordHasher.hash("correct-password").unsafeRunSync(),
    "Member", true, now, now
  )

  test("login sets an opaque HttpOnly Strict cookie and logout revokes only that session") {
    val fixture = new AuthFixture(secure = true)
    val body = Json.obj("email" -> Json.fromString(user.email), "password" -> Json.fromString("correct-password"))
    val login = fixture.app.run(Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/auth/login")).withEntity(body)).unsafeRunSync()
    val cookie = login.headers.headers.find(_.name == CIString("Set-Cookie")).map(_.value).getOrElse(fail("Missing cookie"))
    val payload = login.as[Json].unsafeRunSync()
    val rawToken = cookie.split(';').head.split('=').last

    assertEquals(login.status, Status.Ok)
    assert(cookie.contains("HttpOnly"))
    assert(cookie.contains("SameSite=Strict"))
    assert(cookie.contains("Path=/"))
    assert(cookie.contains("Secure"))
    assertEquals(payload.hcursor.get[String]("email"), Right(user.email))
    assertEquals(payload.hcursor.get[String]("token").isLeft, true)
    assertEquals(fixture.sessions.values.head.tokenHash, tokens.hash(rawToken))
    assertNotEquals(fixture.sessions.values.head.tokenHash, rawToken)

    val me = fixture.app.run(cookieRequest(Method.GET, "/api/v1/me", rawToken)).unsafeRunSync()
    assertEquals(me.status, Status.Ok)
    val logout = fixture.app.run(cookieRequest(Method.POST, "/api/v1/auth/logout", rawToken)).unsafeRunSync()
    assertEquals(logout.status, Status.NoContent)
    assert(logout.headers.headers.exists(header => header.name == CIString("Set-Cookie") && header.value.contains("Max-Age=0")))
    assertEquals(fixture.app.run(cookieRequest(Method.GET, "/api/v1/me", rawToken)).unsafeRunSync().status, Status.Unauthorized)
    assertEquals(fixture.app.run(Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/auth/logout"))).unsafeRunSync().status, Status.NoContent)
  }

  test("invalid login cases share one response") {
    val fixture = new AuthFixture(secure = false)
    val wrong = fixture.login("member@example.com", "wrong")
    val unknown = fixture.login("missing@example.com", "correct-password")
    fixture.users.values = List(user.copy(isActive = false))
    val inactive = fixture.login("member@example.com", "correct-password")
    for (response <- List(wrong, unknown, inactive)) {
      assertEquals(response._1, Status.Unauthorized)
      assertEquals(response._2.hcursor.get[String]("code"), Right("INVALID_CREDENTIALS"))
    }
    assertEquals(fixture.sessions.values.length, 0)
  }

  test("expired, revoked, and inactive-user sessions return unauthenticated") {
    val fixture = new AuthFixture(secure = false)
    val raw = tokens.generate()
    val session = AuthSession(UUID.randomUUID(), userId, tokens.hash(raw), now, now.plusSeconds(3600), None)
    fixture.sessions.values = List(session.copy(expiresAt = now.minusSeconds(1)))
    val request = cookieRequest(Method.GET, "/api/v1/me", raw)
    assertEquals(fixture.app.run(request).unsafeRunSync().status, Status.Unauthorized)
    fixture.sessions.values = List(session.copy(revokedAt = Some(now)))
    assertEquals(fixture.app.run(request).unsafeRunSync().status, Status.Unauthorized)
    fixture.sessions.values = List(session)
    fixture.users.values = List(user.copy(isActive = false))
    assertEquals(fixture.app.run(request).unsafeRunSync().status, Status.Unauthorized)
  }

  test("organization boundary requires a session and active membership") {
    val fixture = new AuthFixture(secure = false)
    val raw = tokens.generate()
    fixture.sessions.values = List(AuthSession(UUID.randomUUID(), userId, tokens.hash(raw), now, now.plusSeconds(3600), None))
    fixture.memberships.values = List(
      OrganizationMembership(userId, orgA, OrganizationRole.Owner, true, now, now),
      OrganizationMembership(userId, orgB, OrganizationRole.Member, false, now, now)
    )
    val orgAPath = s"/api/v1/organizations/$orgA/projects"
    val orgBPath = s"/api/v1/organizations/$orgB/projects"

    assertEquals(fixture.app.run(Request[IO](Method.GET, Uri.unsafeFromString(orgAPath))).unsafeRunSync().status, Status.Unauthorized)
    assertEquals(fixture.app.run(cookieRequest(Method.GET, orgAPath, "unknown")).unsafeRunSync().status, Status.Unauthorized)
    assertEquals(fixture.app.run(cookieRequest(Method.GET, orgAPath, raw)).unsafeRunSync().status, Status.Ok)
    val denied = fixture.app.run(cookieRequest(Method.GET, orgBPath, raw)).unsafeRunSync()
    assertEquals(denied.status, Status.NotFound)
    assertEquals(denied.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("ORGANIZATION_NOT_FOUND"))
    fixture.memberships.values = fixture.memberships.values.map(_.copy(isActive = true))
    assertEquals(fixture.app.run(cookieRequest(Method.GET, orgBPath, raw)).unsafeRunSync().status, Status.Ok)
    fixture.memberships.activeOrganizations = Set(orgA)
    assertEquals(fixture.app.run(cookieRequest(Method.GET, orgBPath, raw)).unsafeRunSync().status, Status.NotFound)
  }

  test("my organizations excludes foreign and inactive rows and has deterministic roles") {
    val fixture = new AuthFixture(secure = false)
    val raw = tokens.generate()
    fixture.sessions.values = List(AuthSession(UUID.randomUUID(), userId, tokens.hash(raw), now, now.plusSeconds(3600), None))
    fixture.memberships.values = List(
      OrganizationMembership(userId, orgB, OrganizationRole.Member, true, now, now),
      OrganizationMembership(userId, orgA, OrganizationRole.Owner, true, now, now),
      OrganizationMembership(UUID.randomUUID(), UUID.randomUUID(), OrganizationRole.Owner, true, now, now)
    )
    val response = fixture.app.run(cookieRequest(Method.GET, "/api/v1/me/organizations", raw)).unsafeRunSync()
    val rows = response.as[Json].unsafeRunSync().asArray.getOrElse(fail("Expected organizations"))
    assertEquals(response.status, Status.Ok)
    assertEquals(rows.map(_.hcursor.get[String]("id").toOption), Vector(Some(orgA.toString), Some(orgB.toString)))
    assertEquals(rows.map(_.hcursor.get[String]("role").toOption), Vector(Some("OWNER"), Some("MEMBER")))
    fixture.memberships.activeOrganizations = Set(orgA)
    val activeRows = fixture.app.run(cookieRequest(Method.GET, "/api/v1/me/organizations", raw)).unsafeRunSync()
      .as[Json].unsafeRunSync().asArray.getOrElse(fail("Expected organizations"))
    assertEquals(activeRows.length, 1)
    fixture.memberships.activeOrganizations = Set(orgA, orgB)
    fixture.memberships.values = fixture.memberships.values.map(value =>
      if (value.organizationId == orgB) value.copy(isActive = false) else value
    )
    val membershipRows = fixture.app.run(cookieRequest(Method.GET, "/api/v1/me/organizations", raw)).unsafeRunSync()
      .as[Json].unsafeRunSync().asArray.getOrElse(fail("Expected organizations"))
    assertEquals(membershipRows.length, 1)
  }

  test("connection mutations require OWNER while MEMBER can read") {
    val fixture = new AuthFixture(secure = false)
    val raw = tokens.generate()
    fixture.sessions.values = List(AuthSession(UUID.randomUUID(), userId, tokens.hash(raw), now, now.plusSeconds(3600), None))
    fixture.memberships.values = List(OrganizationMembership(userId, orgA, OrganizationRole.Member, true, now, now))
    val listPath = s"/api/v1/organizations/$orgA/connections"
    assertEquals(fixture.app.run(cookieRequest(Method.GET, listPath, raw)).unsafeRunSync().status, Status.Ok)
    val denied = fixture.app.run(cookieRequest(Method.POST, listPath, raw)).unsafeRunSync()
    assertEquals(denied.status, Status.Forbidden)
    assertEquals(denied.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("FORBIDDEN"))
    val syncPath = s"$listPath/${UUID.randomUUID()}/sync"
    val historyPath = syncPath.replace("/sync", "/sync-sessions")
    assertEquals(fixture.app.run(cookieRequest(Method.POST, syncPath, raw)).unsafeRunSync().status, Status.Forbidden)
    assertEquals(fixture.app.run(cookieRequest(Method.GET, historyPath, raw)).unsafeRunSync().status, Status.Ok)
    fixture.memberships.values = fixture.memberships.values.map(_.copy(role = OrganizationRole.Owner))
    assertEquals(fixture.app.run(cookieRequest(Method.POST, listPath, raw)).unsafeRunSync().status, Status.Ok)
    assertEquals(fixture.app.run(cookieRequest(Method.POST, syncPath, raw)).unsafeRunSync().status, Status.Ok)
  }

  private def cookieRequest(method: Method, path: String, token: String): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path))
      .putHeaders(org.http4s.Header.Raw(CIString("Cookie"), s"infradesk_session=$token"))

  private val runner = new TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }

  private final class AuthFixture(secure: Boolean) {
    val users = new Users
    val sessions = new Sessions(users)
    val memberships = new Memberships
    val authentication = new Authentication[IO](sessions, memberships, runner, tokens)
    val loginService = new Login[IO](users, sessions, runner, passwordHasher, tokens, 3600)
    val authRoutes = new AuthRoutes(loginService, authentication, AuthSettings(3600, secure))
    val business = HttpRoutes.of[IO] {
      case GET -> Root / "api" / "v1" / "organizations" / _ / "projects" => Ok("reached")
      case GET -> Root / "api" / "v1" / "organizations" / _ / "connections" => Ok("reached")
      case POST -> Root / "api" / "v1" / "organizations" / _ / "connections" => Ok("reached")
      case POST -> Root / "api" / "v1" / "organizations" / _ / "connections" / _ / "sync" => Ok("reached")
      case GET -> Root / "api" / "v1" / "organizations" / _ / "connections" / _ / "sync-sessions" => Ok("reached")
    }.orNotFound
    val app = new AuthBoundary(authRoutes, authentication, business).app

    def login(email: String, password: String): (Status, Json) = {
      val body = Json.obj("email" -> Json.fromString(email), "password" -> Json.fromString(password))
      val response = app.run(Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/auth/login")).withEntity(body)).unsafeRunSync()
      response.status -> response.as[Json].unsafeRunSync()
    }
  }

  private final class Users extends UserAccountRepository[IO] {
    var values: List[UserAccount] = List(user)
    override def findByEmail(email: String): IO[Option[UserAccount]] = IO(values.find(_.email == email))
    override def findActiveById(id: UUID): IO[Option[UserAccount]] = IO(values.find(value => value.id == id && value.isActive))
    override def createIfMissing(value: UserAccount): IO[Unit] = IO { values = value :: values }
  }

  private final class Sessions(users: Users) extends AuthSessionRepository[IO] {
    var values: List[AuthSession] = Nil
    override def create(session: AuthSession): IO[Unit] = IO { values = session :: values }
    override def findAuthenticatedUserByTokenHash(hash: String, at: Instant): IO[Option[AuthenticatedUser]] = IO {
      values.find(session => session.tokenHash == hash && session.revokedAt.isEmpty && session.expiresAt.isAfter(at))
        .flatMap(session => users.values.find(value => value.id == session.userId && value.isActive))
        .map(value => AuthenticatedUser(value.id, value.email, value.displayName))
    }
    override def revokeByTokenHash(hash: String, at: Instant): IO[Unit] = IO {
      values = values.map(session => if (session.tokenHash == hash) session.copy(revokedAt = Some(at)) else session)
    }
  }

  private final class Memberships extends OrganizationMembershipRepository[IO] {
    var values: List[OrganizationMembership] = Nil
    var activeOrganizations: Set[UUID] = Set(orgA, orgB)
    override def hasActiveMembership(id: UUID, organizationId: UUID): IO[Boolean] = IO {
      activeOrganizations.contains(organizationId) && values.exists(value =>
        value.userId == id && value.organizationId == organizationId && value.isActive)
    }
    override def listActiveOrganizations(id: UUID): IO[List[MyOrganization]] = IO {
      values.filter(value => value.userId == id && value.isActive && activeOrganizations.contains(value.organizationId))
        .map(value => MyOrganization(value.organizationId, value.organizationId match {
          case `orgA` => "A"
          case _ => "B"
        }, value.organizationId match {
          case `orgA` => "A"
          case _ => "B"
        }, value.role))
        .sortBy(value => (value.name, value.id.toString))
    }
    override def findActiveRole(id: UUID, organizationId: UUID): IO[Option[OrganizationRole]] = IO {
      values.find(value => value.userId == id && value.organizationId == organizationId &&
        value.isActive && activeOrganizations.contains(value.organizationId)).map(_.role)
    }
    override def createIfMissing(value: OrganizationMembership): IO[Unit] = IO { values = value :: values }
  }
}
