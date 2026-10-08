package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.doobie.free.connection

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration._

final class ConnectionScheduleClaimIntegrationSpec extends FunSuite {
  test("claimDue skips rows locked by a concurrent transaction") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"), "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true")
    val org = UUID.randomUUID(); val ids = List.fill(4)(UUID.randomUUID()); val ownerA = UUID.randomUUID(); val ownerB = UUID.randomUUID(); val due = Instant.parse("2026-09-23T10:00:00Z")
    PostgresTestDatabase.isolatedTransactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa); val schedules = new PostgresConnectionScheduleRepository
      val setup = for {
        _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'skip locked')".update.run
        _ <- ids.traverse_(id => sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($id, $org, 'ORGANIZATION', 'SSH', ${id.toString}, 'Claim')".update.run *> sql"insert into connection_schedule (organization_id, connection_id, enabled, interval_seconds, next_run_at, consecutive_failures) values ($org, $id, true, 60, $due, 0)".update.run)
      } yield ()
      val started = new CountDownLatch(1); val release = new CountDownLatch(1)
      val heldClaim = schedules.claimDue(ownerA, 2, 900).flatTap(_ => connection.delay(started.countDown())) *> connection.delay(release.await())
      val cleanup = for {
        _ <- sql"delete from connection_schedule where organization_id = $org".update.run
        _ <- sql"delete from connection where organization_id = $org".update.run
        _ <- sql"delete from organization where id = $org".update.run
      } yield ()
      runner.run(setup) *> (for {
        fiber <- heldClaim.transact(xa).start
        _ <- IO.blocking(started.await())
        rowsB <- runner.run(schedules.claimDue(ownerB, 2, 900)).timeout(3.seconds)
        _ <- IO { assertEquals(rowsB.size, 2); assert(rowsB.forall(_.claimedBy == ownerB)) }
        _ <- IO(release.countDown()) *> fiber.joinWithNever
      } yield ()).guarantee(runner.run(cleanup))
    }.unsafeRunSync()
  }

  test("leases persist, expire, and fence stale completion") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"), "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true")
    val org = UUID.randomUUID(); val connection = UUID.randomUUID(); val ownerA = UUID.randomUUID(); val ownerB = UUID.randomUUID()
    val due = Instant.parse("2026-09-23T10:00:00Z")
    PostgresTestDatabase.isolatedTransactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa); val schedules = new PostgresConnectionScheduleRepository
      val setup = for {
        _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'claim test')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($connection, $org, 'ORGANIZATION', 'SSH', 'claim', 'Claim')".update.run
        _ <- sql"insert into connection_schedule (organization_id, connection_id, enabled, interval_seconds, next_run_at, consecutive_failures) values ($org, $connection, true, 60, $due, 0)".update.run
      } yield ()
      val cleanup = for {
        _ <- sql"delete from connection_schedule where organization_id = $org".update.run
        _ <- sql"delete from connection where organization_id = $org".update.run
        _ <- sql"delete from organization where id = $org".update.run
      } yield ()
      runner.run(setup) *> (for {
        a <- runner.run(schedules.claimDue(ownerA, 2, 900)); _ <- IO(assertEquals(a.map(_.schedule.connectionId), List(connection)))
        blocked <- runner.run(schedules.claimDue(ownerB, 2, 900)); _ <- IO(assertEquals(blocked, Nil))
        _ <- runner.run(sql"update connection_schedule set claimed_until = current_timestamp - interval '1 second' where organization_id = $org and connection_id = $connection".update.run.void)
        b <- runner.run(schedules.claimDue(ownerB, 2, 900)); _ <- IO(assertEquals(b.map(_.claimedBy), List(ownerB)))
        stale <- runner.run(schedules.completeClaimedRun(org, connection, ownerA, due.plusSeconds(10), 9)); _ <- IO(assert(!stale))
        completed <- runner.run(schedules.completeClaimedRun(org, connection, ownerB, due.plusSeconds(20), 2)); _ <- IO(assert(completed))
        row <- runner.run(sql"select claimed_by, claimed_until, next_run_at, consecutive_failures from connection_schedule where organization_id = $org and connection_id = $connection".query[(Option[UUID], Option[Instant], Instant, Long)].unique)
        _ <- IO { assertEquals(row._1, None); assertEquals(row._2, None); assertEquals(row._3, due.plusSeconds(20)); assertEquals(row._4, 2L) }
      } yield ()).guarantee(runner.run(cleanup))
    }.unsafeRunSync()
  }
}
