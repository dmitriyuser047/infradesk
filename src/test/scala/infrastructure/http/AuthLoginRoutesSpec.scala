package ru.bitec.app.ops
package infrastructure.http

import application.auth.{AuthRateLimitSettings, Authentication, Login, LoginThrottleHasher, PasswordHasher, SessionTokens}
import application.port.{AuthSessionRepository, LoginThrottleKey, MyOrganization, OrganizationMembershipRepository, TimeProvider, TransactionRunner, UserAccountRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.{AuthSession, AuthenticatedSession, LoginThrottleScope, OrganizationMembership, OrganizationRole, UserAccount, UserSessionSummary}
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.{Header, Method, Request, Status, Uri}
import org.typelevel.ci.CIString
import support.InMemoryLoginThrottle

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** The public login endpoint: one response for unknown and wrong, a rate-limit response after too
  * many failures, a session only on success, and a forwarded source honoured only when trusted.
  */
final class AuthLoginRoutesSpec extends FunSuite {

  private val Email = "member@example.com"
  private val hasher = new LoginThrottleHasher(Array.fill(32)(5.toByte))
  private def srcKey(ip: String) = LoginThrottleKey(LoginThrottleScope.Source, hasher.hash(ip))
  private val limits = AuthRateLimitSettings(10.minutes, 3, 5.minutes, 10.minutes, 20, 5.minutes, 1.day)

  test("an unknown email and a wrong password give the same 401 invalid-credentials response") {
    val setup = newSetup()
    val unknown = setup.post(Json.obj("email" -> Json.fromString("ghost@example.com"), "password" -> Json.fromString("x")))
    val wrong = setup.post(Json.obj("email" -> Json.fromString(Email), "password" -> Json.fromString("wrong")))

    assertEquals(unknown._1, Status.Unauthorized)
    assertEquals(wrong._1, Status.Unauthorized)
    assertEquals(unknown._2.hcursor.get[String]("code"), Right("INVALID_CREDENTIALS"))
    assertEquals(wrong._2.hcursor.get[String]("code"), Right("INVALID_CREDENTIALS"))
    assertEquals(unknown._2, wrong._2)
    assertEquals(setup.sessions.created, 0)
  }

  test("a valid login returns the account and sets the session cookie") {
    val setup = newSetup()
    val (status, body, response) = setup.postFull(
      Json.obj("email" -> Json.fromString(Email), "password" -> Json.fromString("correct-password")))
    assertEquals(status, Status.Ok)
    assertEquals(body.hcursor.get[String]("email"), Right(Email))
    assert(response.cookies.exists(_.name == AuthRoutes.CookieName), "a session cookie must be set")
    assert(body.hcursor.get[String]("passwordHash").isLeft && body.hcursor.get[String]("token").isLeft)
    assertEquals(setup.sessions.created, 1)
  }

  test("too many failures answer 429 without creating a session") {
    val setup = newSetup()
    (1 to limits.identifierMaxFailures).foreach(_ =>
      setup.post(Json.obj("email" -> Json.fromString(Email), "password" -> Json.fromString("wrong"))))
    val blocked = setup.post(Json.obj("email" -> Json.fromString(Email), "password" -> Json.fromString("wrong")))
    // Even the correct password is turned away while the block holds.
    val blockedValid = setup.post(Json.obj("email" -> Json.fromString(Email), "password" -> Json.fromString("correct-password")))

    assertEquals(blocked._1, Status.TooManyRequests)
    assertEquals(blocked._2.hcursor.get[String]("code"), Right("LOGIN_RATE_LIMITED"))
    assertEquals(blockedValid._1, Status.TooManyRequests)
    assertEquals(setup.sessions.created, 0)
  }

  test("a forwarded source is ignored unless the deployment trusts the proxy") {
    val untrusted = newSetup(trustForwardedFor = false)
    untrusted.postFrom(Json.obj("email" -> Json.fromString(Email), "password" -> Json.fromString("wrong")), "9.9.9.9")
    // The spoofed header did not create a source bucket.
    assertEquals(untrusted.throttle.failureCount(srcKey("9.9.9.9")), 0)

    val trusted = newSetup(trustForwardedFor = true)
    trusted.postFrom(Json.obj("email" -> Json.fromString(Email), "password" -> Json.fromString("wrong")), "9.9.9.9")
    assertEquals(trusted.throttle.failureCount(srcKey("9.9.9.9")), 1)
  }

  private def newSetup(trustForwardedFor: Boolean = true): Setup = {
    val throttle = new InMemoryLoginThrottle
    val users = new FakeUsers
    val sessions = new FakeSessions
    val tokens = new SessionTokens
    val clock = new TimeProvider[IO] { override def now: IO[Instant] = IO(Instant.now()) }
    val login = new Login[IO](users, sessions, throttle, hasher, limits, new DirectRunner,
      new FakeHasher, tokens, clock, 3600)
    val authentication = new Authentication[IO](sessions, new EmptyMemberships, new DirectRunner, tokens)
    val routes = new AuthRoutes(login, authentication, AuthSettings(3600, secureCookie = false, trustForwardedFor))
    Setup(routes, throttle, sessions)
  }

  private final case class Setup(routes: AuthRoutes[IO], throttle: InMemoryLoginThrottle, sessions: FakeSessions) {
    private def run(body: Json, headers: List[Header.ToRaw]): (Status, Json, org.http4s.Response[IO]) = {
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/auth/login"))
        .withEntity(body).putHeaders(headers: _*)
      val response = routes.public.orNotFound.run(request).unsafeRunSync()
      (response.status, response.as[Json].unsafeRunSync(), response)
    }
    def post(body: Json): (Status, Json) = { val (s, j, _) = run(body, Nil); (s, j) }
    def postFull(body: Json): (Status, Json, org.http4s.Response[IO]) = run(body, Nil)
    def postFrom(body: Json, ip: String): (Status, Json) = {
      val (s, j, _) = run(body, List(Header.Raw(CIString("X-Real-IP"), ip))); (s, j)
    }
  }

  private final class FakeHasher extends PasswordHasher {
    override def hash(password: String): IO[String] = IO.pure(s"bcrypt$$$password")
    override def verify(password: String, hash: String): IO[Boolean] = IO.pure(password == "correct-password" && hash == "real")
    override def verifyDummy(password: String): IO[Unit] = IO.unit
  }

  private final class FakeUsers extends UserAccountRepository[IO] {
    override def findByEmail(email: String): IO[Option[UserAccount]] =
      IO.pure(Option.when(email == Email)(UserAccount(UUID.randomUUID(), Email, "real", "Member", isActive = true, Instant.EPOCH, Instant.EPOCH)))
    override def findActiveById(id: UUID): IO[Option[UserAccount]] = IO.pure(None)
    override def createIfMissing(user: UserAccount): IO[Unit] = IO.unit
    override def compareAndSetPasswordHash(id: UUID, expectedPasswordHash: String, newPasswordHash: String, updatedAt: Instant): IO[Boolean] = IO.pure(false)
    override def updateDisplayName(id: UUID, displayName: String, updatedAt: Instant): IO[Boolean] = IO.pure(false)
  }

  private final class FakeSessions extends AuthSessionRepository[IO] {
    private var count = 0
    def created: Int = synchronized(count)
    override def create(session: AuthSession): IO[Unit] = IO(synchronized { count += 1 })
    override def findAuthenticatedSessionByTokenHash(tokenHash: String, now: Instant): IO[Option[AuthenticatedSession]] = IO.pure(None)
    override def revokeByTokenHash(tokenHash: String, now: Instant): IO[Unit] = IO.unit
    override def listActiveByUser(userId: UUID, now: Instant): IO[List[UserSessionSummary]] = IO.pure(List.empty)
    override def revokeByIdForUser(userId: UUID, sessionId: UUID, now: Instant): IO[Boolean] = IO.pure(false)
    override def revokeOthersForUser(userId: UUID, exceptSessionId: UUID, now: Instant): IO[Int] = IO.pure(0)
    override def revokeAllForUser(userId: UUID, now: Instant): IO[Int] = IO.pure(0)
  }

  private final class EmptyMemberships extends OrganizationMembershipRepository[IO] {
    override def findActiveRole(userId: UUID, organizationId: UUID): IO[Option[OrganizationRole]] = IO.pure(None)
    override def hasActiveMembership(userId: UUID, organizationId: UUID): IO[Boolean] = IO.pure(false)
    override def listActiveOrganizations(userId: UUID): IO[List[MyOrganization]] = IO.pure(List.empty)
    override def createIfMissing(membership: OrganizationMembership): IO[Unit] = IO.unit
  }

  private final class DirectRunner extends TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }
}
