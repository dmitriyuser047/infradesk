package ru.bitec.app.ops
package persistence.postgres

import application.auth.BCryptPasswordHasher
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.{AuthSession, UserAccount}
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The password compare-and-set and the session-revocation SQL against a real database: the two
  * pieces of concurrency hardening that a fake cannot prove.
  */
final class AccountSecurityIntegrationSpec extends FunSuite {

  private val hasher = new BCryptPasswordHasher

  test("compare-and-set applies the first change and refuses a second with a stale expected hash") {
    withSetup { fixture =>
      val h0 = hasher.hash("old-password-1").unsafeRunSync()
      val h1 = hasher.hash("new-password-A").unsafeRunSync()
      val h2 = hasher.hash("new-password-B").unsafeRunSync()

      for {
        _ <- fixture.run(fixture.users.createIfMissing(account(fixture.userId, h0)))
        // Two requests both verified h0; the first CAS wins.
        first <- fixture.run(fixture.users.compareAndSetPasswordHash(fixture.userId, h0, h1, fixture.now))
        second <- fixture.run(fixture.users.compareAndSetPasswordHash(fixture.userId, h0, h2, fixture.now))
        stored <- fixture.run(fixture.users.findActiveById(fixture.userId))
      } yield IO {
        assertEquals(first, true)
        // The second carried the stale expected hash, so it changed nothing.
        assertEquals(second, false)
        assertEquals(stored.map(_.passwordHash), Some(h1))
      }
    }
  }

  test("revoke-others ends every session but the current one, scoped to the user") {
    withSetup { fixture =>
      val current = UUID.randomUUID()
      val other = UUID.randomUUID()
      val strangerUser = UUID.randomUUID()
      val strangerSession = UUID.randomUUID()

      for {
        _ <- fixture.run(fixture.users.createIfMissing(account(fixture.userId, hasher.hash("p").unsafeRunSync())))
        _ <- fixture.run(fixture.users.createIfMissing(account(strangerUser, hasher.hash("p").unsafeRunSync())))
        _ <- fixture.run(fixture.sessions.create(session(current, fixture.userId, fixture.now)))
        _ <- fixture.run(fixture.sessions.create(session(other, fixture.userId, fixture.now)))
        _ <- fixture.run(fixture.sessions.create(session(strangerSession, strangerUser, fixture.now)))
        revoked <- fixture.run(fixture.sessions.revokeOthersForUser(fixture.userId, current, fixture.now))
        mine <- fixture.run(fixture.sessions.listActiveByUser(fixture.userId, fixture.now))
        strangers <- fixture.run(fixture.sessions.listActiveByUser(strangerUser, fixture.now))
        // A stranger's session is never this user's to revoke.
        crossUser <- fixture.run(fixture.sessions.revokeByIdForUser(fixture.userId, strangerSession, fixture.now))
      } yield IO {
        assertEquals(revoked, 1)
        assertEquals(mine.map(_.id), List(current))
        assertEquals(strangers.map(_.id), List(strangerSession))
        assertEquals(crossUser, false)
      }
    }
  }

  // -------------------------------------------------------------------------------------------

  private def account(id: UUID, hash: String): UserAccount =
    UserAccount(id, s"account-${id.toString.take(8)}@example.test", hash, "User", isActive = true,
      Instant.EPOCH, Instant.EPOCH)

  private def session(id: UUID, userId: UUID, now: Instant): AuthSession =
    AuthSession(id, userId, id.toString.replace("-", ""), now, now.plusSeconds(3600), None)

  private def withSetup(body: Setup => IO[IO[Unit]]): Unit = {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val fixture = new Setup(new DoobieTransactionRunner(xa))
      (body(fixture).flatten).guarantee(fixture.cleanUp)
    }.unsafeRunSync()
  }

  private final class Setup(runner: DoobieTransactionRunner) {
    val userId: UUID = UUID.randomUUID()
    val users = new PostgresUserAccountRepository
    val sessions = new PostgresAuthSessionRepository
    val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    // The tests create their own users, so cleanup removes every session and account they made.
    def cleanUp: IO[Unit] = run(for {
      _ <- sql"delete from auth_session where user_id in (select id from user_account where email like 'account-%@example.test')".update.run
      _ <- sql"delete from user_account where email like 'account-%@example.test'".update.run
    } yield ()).attempt.void
  }
}
