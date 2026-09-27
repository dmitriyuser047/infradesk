package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import application.auth.ListSecurityEvents
import domain.auth.{SecurityEvent, SecurityEventCursor, SecurityEventType, UserAccount}
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Real PostgreSQL coverage for account-owned security history: isolation, the two-part cursor,
  * retention deletion and the bounded indexed read path. */
final class SecurityEventIntegrationSpec extends FunSuite {
  test("pagination keeps every tied-timestamp event and never crosses user boundaries") {
    withSetup { setup =>
      val at = Instant.parse("2026-09-27T12:00:00Z")
      val mine = List.fill(3)(UUID.randomUUID()).map(id => SecurityEvent(id, setup.userId,
        SecurityEventType.LoginSucceeded, at, None, Some("203.0.113.10"), None))
      val other = SecurityEvent(UUID.randomUUID(), setup.otherUserId, SecurityEventType.PasswordChanged, at, None, None, None)
      for {
        _ <- setup.run(mine.traverse_(setup.events.save) *> setup.events.save(other))
        first <- setup.run(new ListSecurityEvents[ConnectionIO](setup.events).execute(setup.userId, None, 2))
        second <- setup.run(new ListSecurityEvents[ConnectionIO](setup.events).execute(setup.userId, first.nextCursor, 2))
      } yield {
        assertEquals(first.items.size, 2)
        assertEquals(first.nextCursor, first.items.lastOption.map(row => SecurityEventCursor(row.occurredAt, row.id)))
        assertEquals(second.items.size, 1)
        assertEquals((first.items ++ second.items).map(_.id).toSet, mine.map(_.id).toSet)
        assertEquals((first.items ++ second.items).map(_.id).distinct.size, 3)
        assertEquals((first.items ++ second.items).map(_.userId).toSet, Set(setup.userId))
      }
    }
  }

  test("retention deletes stale rows but retains recent events") {
    withSetup { setup =>
      val old = SecurityEvent(UUID.randomUUID(), setup.userId, SecurityEventType.PasswordChanged,
        Instant.parse("2026-01-01T00:00:00Z"), None, None, None)
      val recent = old.copy(id = UUID.randomUUID(), occurredAt = Instant.parse("2026-09-27T00:00:00Z"))
      for {
        _ <- setup.run(setup.events.save(old) *> setup.events.save(recent))
        _ <- setup.run(setup.events.deleteBefore(Instant.parse("2026-06-01T00:00:00Z")))
        rows <- setup.run(setup.events.listByUser(setup.userId, None, 10))
      } yield assertEquals(rows.map(_.id), List(recent.id))
    }
  }

  test("security event writes roll back with their transaction") {
    withSetup { setup =>
      val event = SecurityEvent(UUID.randomUUID(), setup.userId, SecurityEventType.PasswordChanged,
        Instant.parse("2026-09-27T12:00:00Z"), None, None, None)
      for {
        result <- setup.run(setup.events.save(event) *> setup.events.save(event)).attempt
        rows <- setup.run(setup.events.listByUser(setup.userId, None, 10))
      } yield {
        assert(result.isLeft)
        assertEquals(rows, Nil)
      }
    }
  }

  private def withSetup(body: Setup => IO[Unit]): Unit = {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"), "PostgreSQL integration tests disabled")
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val setup = new Setup(new DoobieTransactionRunner(xa)); setup.initialize *> body(setup).guarantee(setup.cleanUp)
    }.unsafeRunSync()
  }
  private final class Setup(runner: DoobieTransactionRunner) {
    val userId = UUID.randomUUID(); val otherUserId = UUID.randomUUID()
    val users = new PostgresUserAccountRepository; val events = new PostgresSecurityEventRepository
    def run[A](value: ConnectionIO[A]): IO[A] = runner.run(value)
    private def user(id: UUID) = UserAccount(id, s"security-${id}@example.test", "hash", "User", true, Instant.EPOCH, Instant.EPOCH)
    def initialize: IO[Unit] = run(users.createIfMissing(user(userId)) *> users.createIfMissing(user(otherUserId)))
    def cleanUp: IO[Unit] = run(List(userId, otherUserId).traverse_ { id =>
      sql"delete from account_security_event where user_id = $id".update.run *>
        sql"delete from user_account where id = $id".update.run
    }).attempt.void
  }
}
