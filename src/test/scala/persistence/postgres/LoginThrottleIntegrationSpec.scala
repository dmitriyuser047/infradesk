package ru.bitec.app.ops
package persistence.postgres

import application.port.LoginThrottleKey
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel._
import domain.auth.LoginThrottleScope
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.duration._

/** The login throttle against a real database: the atomic increment under concurrency, the block
  * computation, the window reset and the cleanup — the parts a fake cannot prove.
  */
final class LoginThrottleIntegrationSpec extends FunSuite {

  test("concurrent failures all count: the atomic upsert loses no increment") {
    withSetup { setup =>
      val key = setup.identifierKey()
      val attempts = 25
      for {
        _ <- (1 to attempts).toList.parTraverse(_ =>
          setup.run(setup.throttle.recordFailure(key, setup.now, 10.minutes, 1000, 5.minutes)))
        count <- setup.run(setup.failureCount(key))
      } yield IO {
        // A read-modify-write would have lost some of the 25 concurrent increments.
        assertEquals(count, Some(attempts))
      }
    }
  }

  test("reaching the maximum sets a block that blockedUntil reports, and it is not exceeded early") {
    withSetup { setup =>
      val key = setup.identifierKey()
      for {
        _ <- setup.run(setup.throttle.recordFailure(key, setup.now, 10.minutes, 2, 5.minutes))
        beforeBlock <- setup.run(setup.throttle.blockedUntil(List(key), setup.now))
        _ <- setup.run(setup.throttle.recordFailure(key, setup.now, 10.minutes, 2, 5.minutes))
        afterBlock <- setup.run(setup.throttle.blockedUntil(List(key), setup.now))
      } yield IO {
        assertEquals(beforeBlock, None, "one failure below the maximum does not block")
        assert(afterBlock.exists(!_.isBefore(setup.now.plusSeconds(5.minutes.toSeconds - 5))),
          s"the second failure blocks for about the configured duration: $afterBlock")
      }
    }
  }

  test("a threshold of one blocks the initial insert too") {
    withSetup { setup =>
      val key = setup.identifierKey()
      for {
        _ <- setup.run(setup.throttle.recordFailure(key, setup.now, 10.minutes, 1, 5.minutes))
        blocked <- setup.run(setup.throttle.blockedUntil(List(key), setup.now))
      } yield IO(assert(blocked.isDefined, "the initial failure must honor a threshold of one"))
    }
  }

  test("a failure after the window has elapsed starts the count over") {
    withSetup { setup =>
      val key = setup.identifierKey()
      val later = setup.now.plusSeconds(20.minutes.toSeconds)
      for {
        _ <- setup.run(setup.throttle.recordFailure(key, setup.now, 10.minutes, 5, 5.minutes))
        _ <- setup.run(setup.throttle.recordFailure(key, setup.now, 10.minutes, 5, 5.minutes))
        _ <- setup.run(setup.throttle.recordFailure(key, later, 10.minutes, 5, 5.minutes))
        count <- setup.run(setup.failureCount(key))
      } yield IO {
        // The third failure is outside the original 10-minute window, so the count resets to 1.
        assertEquals(count, Some(1))
      }
    }
  }

  test("cleanup removes a stale row but keeps a fresh one and a blocked one") {
    withSetup { setup =>
      val fresh = setup.identifierKey()
      val stale = setup.identifierKey()
      val blocked = setup.identifierKey()
      val old = setup.now.minusSeconds(2.days.toSeconds)
      for {
        _ <- setup.run(setup.throttle.recordFailure(fresh, setup.now, 10.minutes, 1000, 5.minutes))
        _ <- setup.run(setup.throttle.recordFailure(stale, old, 10.minutes, 1000, 5.minutes))
        // A stale-by-timestamp row that is still blocked must be kept.
        _ <- setup.run(setup.throttle.recordFailure(blocked, old, 10.minutes, 1, 5.days))
        deleted <- setup.run(setup.throttle.deleteStale(setup.now.minusSeconds(1.day.toSeconds), setup.now))
        freshCount <- setup.run(setup.failureCount(fresh))
        staleCount <- setup.run(setup.failureCount(stale))
        blockedCount <- setup.run(setup.failureCount(blocked))
      } yield IO {
        assertEquals(deleted, 1)
        assert(freshCount.isDefined, "the fresh row is kept")
        assertEquals(staleCount, None, "the stale, unblocked row is removed")
        assert(blockedCount.isDefined, "the stale but still-blocked row is kept")
      }
    }
  }

  private def withSetup(body: Setup => IO[IO[Unit]]): Unit = {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val setup = new Setup(new DoobieTransactionRunner(xa))
      (body(setup).flatten).guarantee(setup.cleanUp)
    }.unsafeRunSync()
  }

  private final class Setup(runner: DoobieTransactionRunner) {
    val throttle = new PostgresLoginThrottleRepository
    val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    // A per-run prefix so this suite's rows never collide with a parallel suite's, and cleanup
    // removes only what this run created.
    private val prefix: String = UUID.randomUUID().toString.replace("-", "")

    def identifierKey(): LoginThrottleKey =
      LoginThrottleKey(LoginThrottleScope.Identifier, prefix + UUID.randomUUID().toString.replace("-", "").take(16))

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def failureCount(key: LoginThrottleKey): ConnectionIO[Option[Int]] =
      sql"select failure_count from auth_login_throttle where scope = ${key.scope.code} and key_hash = ${key.keyHash}"
        .query[Int].option

    def cleanUp: IO[Unit] =
      run(sql"delete from auth_login_throttle where key_hash like ${prefix + "%"}".update.run).attempt.void
  }
}
