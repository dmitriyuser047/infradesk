package ru.bitec.app.ops
package application.auth

import application.port.{AuthSessionRepository, LoginThrottleKey, TimeProvider, TransactionRunner, UserAccountRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.{AuthSession, AuthenticatedSession, LoginThrottleScope, UserAccount, UserSessionSummary}
import munit.FunSuite
import support.InMemoryLoginThrottle

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** Login brute-force protection: throttling per identifier and per source, the dummy-hash path for
  * unknown accounts, and the success-reset policy.
  */
final class LoginBruteForceSpec extends FunSuite {

  private val RealHash = "bcrypt$real"
  private val UserId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val Email = "member@example.com"
  private val SourceA = "203.0.113.7"
  private val SourceB = "198.51.100.9"
  // A short, sharp policy so the tests reach thresholds quickly and deterministically.
  private val limits = AuthRateLimitSettings(
    identifierWindow = 10.minutes, identifierMaxFailures = 3, identifierBlock = 5.minutes,
    sourceWindow = 10.minutes, sourceMaxFailures = 5, sourceBlock = 5.minutes, retention = 1.day)
  private val hasher = new LoginThrottleHasher(Array.fill(32)(9.toByte))
  private def idKey(email: String) = LoginThrottleKey(LoginThrottleScope.Identifier, hasher.hash(UserAccount.normalizeEmail(email)))
  private def srcKey(ip: String) = LoginThrottleKey(LoginThrottleScope.Source, hasher.hash(ip))

  test("failures increment the identifier and trip a block that turns the next attempt away") {
    val setup = newSetup()
    (1 to limits.identifierMaxFailures).foreach { _ =>
      assertEquals(setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync(), LoginOutcome.InvalidCredentials)
    }
    assertEquals(setup.throttle.failureCount(idKey(Email)), limits.identifierMaxFailures)
    // The attempt after the threshold is rejected before any credential check.
    setup.hasher.reset()
    setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync() match {
      case LoginOutcome.RateLimited(retryAfter) => assert(retryAfter > 0)
      case other => fail(s"expected rate limited, got $other")
    }
    assertEquals(setup.hasher.verifiedWith, List.empty, "a blocked attempt must not run BCrypt")
    assertEquals(setup.sessions.created, 0)
  }

  test("a block expires on its own, and the attempt after it proceeds again") {
    val setup = newSetup()
    (1 to limits.identifierMaxFailures).foreach(_ => setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync())
    assert(setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync().isInstanceOf[LoginOutcome.RateLimited])
    // Move past the block window; nothing needs unlocking by hand.
    setup.clock.advance(limits.identifierBlock + 1.second)
    assertEquals(setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync(), LoginOutcome.InvalidCredentials)
  }

  test("an unknown account is throttled too, through the dummy-hash path") {
    val setup = newSetup()
    setup.login.execute("ghost@example.com", "whatever", Some(SourceA)).unsafeRunSync()
    // Unknown account: exactly one verify, against the dummy hash, and the identifier is counted.
    assertEquals(setup.hasher.verifiedWith, List(setup.hasher.DummyHash))
    assertEquals(setup.throttle.failureCount(idKey("ghost@example.com")), 1)
  }

  test("a known account with a wrong password verifies once against the real hash") {
    val setup = newSetup()
    setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync()
    assertEquals(setup.hasher.verifiedWith, List(RealHash))
  }

  test("a successful login clears the identifier throttle but not the source's") {
    val setup = newSetup()
    setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync()
    setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync()
    assertEquals(setup.throttle.failureCount(idKey(Email)), 2)
    val sourceBefore = setup.throttle.failureCount(srcKey(SourceA))

    assert(setup.login.execute(Email, "correct-password", Some(SourceA)).unsafeRunSync().isInstanceOf[LoginOutcome.Succeeded])

    assertEquals(setup.throttle.failureCount(idKey(Email)), 0, "identifier throttle reset on success")
    assertEquals(setup.throttle.failureCount(srcKey(SourceA)), sourceBefore, "source history is not erased by one success")
    assertEquals(setup.sessions.created, 1)
  }

  test("the source limiter accumulates across different identifiers") {
    val setup = newSetup()
    (1 to limits.sourceMaxFailures).foreach { index =>
      setup.login.execute(s"user$index@example.com", "wrong", Some(SourceA)).unsafeRunSync()
    }
    assertEquals(setup.throttle.failureCount(srcKey(SourceA)), limits.sourceMaxFailures)
    // The source is now blocked; a fresh identifier from the same source is turned away.
    assert(setup.login.execute("brand-new@example.com", "wrong", Some(SourceA)).unsafeRunSync()
      .isInstanceOf[LoginOutcome.RateLimited])
  }

  test("the identifier limiter accumulates across different sources") {
    val setup = newSetup()
    setup.login.execute(Email, "wrong", Some(SourceA)).unsafeRunSync()
    setup.login.execute(Email, "wrong", Some(SourceB)).unsafeRunSync()
    assertEquals(setup.throttle.failureCount(idKey(Email)), 2)
    assertEquals(setup.throttle.failureCount(srcKey(SourceA)), 1)
    assertEquals(setup.throttle.failureCount(srcKey(SourceB)), 1)
  }

  test("with no trustworthy source only the identifier is throttled") {
    val setup = newSetup()
    setup.login.execute(Email, "wrong", None).unsafeRunSync()
    assertEquals(setup.throttle.failureCount(idKey(Email)), 1)
    assertEquals(setup.throttle.size, 1)
  }

  private def newSetup(): Setup = {
    val throttle = new InMemoryLoginThrottle
    val recording = new RecordingHasher
    val users = new FakeUsers
    val sessions = new FakeSessions
    val clock = new MutableClock(Instant.parse("2026-09-27T10:00:00Z"))
    val login = new Login[IO](users, sessions, throttle, hasher, limits, new DirectRunner, recording,
      new SessionTokens, clock, 3600)
    Setup(login, throttle, recording, sessions, clock)
  }

  private final case class Setup(login: Login[IO], throttle: InMemoryLoginThrottle, hasher: RecordingHasher, sessions: FakeSessions, clock: MutableClock)

  /** Records the hash each verify ran against, so the dummy vs real path is checked structurally. */
  private final class RecordingHasher extends PasswordHasher {
    val DummyHash = "bcrypt$dummy"
    private var seen: List[String] = List.empty
    def verifiedWith: List[String] = synchronized(seen)
    def reset(): Unit = synchronized { seen = List.empty }
    override def hash(password: String): IO[String] = IO.pure(s"bcrypt$$$password")
    override def verify(password: String, hash: String): IO[Boolean] =
      IO(synchronized { seen = seen :+ hash }) *> IO.pure(password == "correct-password" && hash == RealHash)
    override def verifyDummy(password: String): IO[Unit] = verify(password, DummyHash).void
  }

  private final class FakeUsers extends UserAccountRepository[IO] {
    override def findByEmail(email: String): IO[Option[UserAccount]] =
      IO.pure(Option.when(email == Email)(UserAccount(UserId, Email, RealHash, "Member", isActive = true, Instant.EPOCH, Instant.EPOCH)))
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

  private final class MutableClock(start: Instant) extends TimeProvider[IO] {
    private var current: Instant = start
    def advance(by: FiniteDuration): Unit = synchronized { current = current.plusSeconds(by.toSeconds) }
    override def now: IO[Instant] = IO(synchronized(current))
  }

  private final class DirectRunner extends TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }
}
