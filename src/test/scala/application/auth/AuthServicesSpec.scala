package ru.bitec.app.ops
package application.auth

import application.port.{AuthSessionRepository, TransactionRunner, UserAccountRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.{AuthSession, AuthenticatedSession, AuthenticatedUser, UserAccount, UserSessionSummary}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class AuthServicesSpec extends FunSuite {
  private val now = Instant.parse("2026-09-22T12:00:00Z")
  private val userId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val passwords = new BCryptPasswordHasher
  private val tokens = new SessionTokens
  private lazy val passwordHash = passwords.hash("correct-password").unsafeRunSync()

  test("BCrypt accepts the correct password and rejects a wrong one") {
    assert(passwords.verify("correct-password", passwordHash).unsafeRunSync())
    assert(!passwords.verify("wrong-password", passwordHash).unsafeRunSync())
  }

  test("session token has 32 random bytes and a deterministic non-plaintext hash") {
    val raw = tokens.generate()
    assertEquals(java.util.Base64.getUrlDecoder.decode(raw).length, 32)
    assertNotEquals(raw, tokens.hash(raw))
    assertEquals(tokens.hash(raw), tokens.hash(raw))
    assertNotEquals(tokens.generate(), raw)
  }

  test("login creates a hashed session only for active users with the right password") {
    val user = UserAccount(userId, "member@example.com", passwordHash, "Member", true, now, now)
    val users = new MemoryUsers(List(user))
    val sessions = new MemorySessions(users)
    val login = new Login[IO](users, sessions, identityRunner, passwords, tokens, 3600)

    val success = login.execute(" MEMBER@EXAMPLE.COM ", "correct-password").unsafeRunSync()
      .getOrElse(fail("Expected successful login"))
    assertEquals(success.user, AuthenticatedUser(userId, user.email, user.displayName))
    assertEquals(sessions.values.length, 1)
    assertEquals(sessions.values.head.tokenHash, tokens.hash(success.rawToken))
    assertNotEquals(sessions.values.head.tokenHash, success.rawToken)

    assertEquals(login.execute("missing@example.com", "correct-password").unsafeRunSync(), None)
    assertEquals(login.execute(user.email, "wrong-password").unsafeRunSync(), None)
    users.values = List(user.copy(isActive = false))
    assertEquals(login.execute(user.email, "correct-password").unsafeRunSync(), None)
    assertEquals(sessions.values.length, 1)
  }

  test("session authentication rejects unknown, expired, revoked, and inactive users") {
    val user = UserAccount(userId, "member@example.com", passwordHash, "Member", true, now, now)
    val users = new MemoryUsers(List(user))
    val sessions = new MemorySessions(users)
    val authentication = new Authentication[IO](sessions, new EmptyMemberships, identityRunner, tokens)
    val raw = tokens.generate()
    val hash = tokens.hash(raw)
    val current = Instant.now()
    sessions.values = List(AuthSession(UUID.randomUUID(), userId, hash, current, current.plusSeconds(3600), None))

    assertEquals(authentication.authenticate(raw).unsafeRunSync().map(_.user),
      Some(AuthenticatedUser(userId, user.email, user.displayName)))
    assertEquals(authentication.authenticate("unknown").unsafeRunSync(), None)
    sessions.values = sessions.values.map(_.copy(expiresAt = current.minusSeconds(1)))
    assertEquals(authentication.authenticate(raw).unsafeRunSync(), None)
    sessions.values = sessions.values.map(_.copy(expiresAt = current.plusSeconds(3600), revokedAt = Some(current)))
    assertEquals(authentication.authenticate(raw).unsafeRunSync(), None)
    sessions.values = sessions.values.map(_.copy(revokedAt = None))
    users.values = List(user.copy(isActive = false))
    assertEquals(authentication.authenticate(raw).unsafeRunSync(), None)
  }

  private val identityRunner = new TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }

  private final class MemoryUsers(var values: List[UserAccount]) extends UserAccountRepository[IO] {
    override def findByEmail(email: String): IO[Option[UserAccount]] =
      IO(values.find(_.email == email))
    override def findActiveById(id: UUID): IO[Option[UserAccount]] =
      IO(values.find(user => user.id == id && user.isActive))
    override def createIfMissing(user: UserAccount): IO[Unit] = IO {
      if (!values.exists(_.email == user.email)) values = user :: values
    }
    override def compareAndSetPasswordHash(id: UUID, expectedPasswordHash: String, newPasswordHash: String, updatedAt: java.time.Instant): IO[Boolean] =
      IO(values.exists(user => user.id == id && user.isActive && user.passwordHash == expectedPasswordHash))
    override def updateDisplayName(id: UUID, displayName: String, updatedAt: java.time.Instant): IO[Boolean] =
      IO(values.exists(user => user.id == id && user.isActive))
  }

  private final class MemorySessions(users: MemoryUsers) extends AuthSessionRepository[IO] {
    var values: List[AuthSession] = Nil
    override def create(session: AuthSession): IO[Unit] = IO { values = session :: values }
    override def findAuthenticatedSessionByTokenHash(hash: String, at: Instant): IO[Option[AuthenticatedSession]] = IO {
      values.find(session => session.tokenHash == hash && session.revokedAt.isEmpty && session.expiresAt.isAfter(at))
        .flatMap(session => users.values.find(user => user.id == session.userId && user.isActive).map(session -> _))
        .map { case (session, user) => AuthenticatedSession(AuthenticatedUser(user.id, user.email, user.displayName), session.id) }
    }
    override def revokeByTokenHash(hash: String, at: Instant): IO[Unit] = IO {
      values = values.map(session => if (session.tokenHash == hash) session.copy(revokedAt = Some(at)) else session)
    }
    override def listActiveByUser(userId: UUID, at: Instant): IO[List[UserSessionSummary]] = IO {
      values.filter(s => s.userId == userId && s.revokedAt.isEmpty && s.expiresAt.isAfter(at))
        .sortBy(_.createdAt).reverse.map(s => UserSessionSummary(s.id, s.createdAt, s.expiresAt))
    }
    override def revokeByIdForUser(userId: UUID, sessionId: UUID, at: Instant): IO[Boolean] = IO {
      val target = values.find(s => s.id == sessionId && s.userId == userId && s.revokedAt.isEmpty)
      values = values.map(s => if (target.exists(_.id == s.id)) s.copy(revokedAt = Some(at)) else s)
      target.isDefined
    }
    override def revokeOthersForUser(userId: UUID, exceptSessionId: UUID, at: Instant): IO[Int] = IO {
      val hit = values.filter(s => s.userId == userId && s.id != exceptSessionId && s.revokedAt.isEmpty)
      values = values.map(s => if (hit.exists(_.id == s.id)) s.copy(revokedAt = Some(at)) else s)
      hit.size
    }
    override def revokeAllForUser(userId: UUID, at: Instant): IO[Int] = IO {
      val hit = values.filter(s => s.userId == userId && s.revokedAt.isEmpty)
      values = values.map(s => if (hit.exists(_.id == s.id)) s.copy(revokedAt = Some(at)) else s)
      hit.size
    }
  }

  private final class EmptyMemberships extends application.port.OrganizationMembershipRepository[IO] {
    override def findActiveRole(userId: UUID, organizationId: UUID): IO[Option[domain.auth.OrganizationRole]] = IO.pure(None)
    override def hasActiveMembership(userId: UUID, organizationId: UUID): IO[Boolean] = IO.pure(false)
    override def listActiveOrganizations(userId: UUID): IO[List[application.port.MyOrganization]] = IO.pure(Nil)
    override def createIfMissing(membership: domain.auth.OrganizationMembership): IO[Unit] = IO.unit
  }
}
