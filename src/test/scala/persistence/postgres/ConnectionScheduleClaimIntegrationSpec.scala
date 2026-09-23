package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class ConnectionScheduleClaimIntegrationSpec extends FunSuite {
  test("leases persist, expire, and fence stale completion") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"), "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true")
    val org = UUID.randomUUID(); val connection = UUID.randomUUID(); val ownerA = UUID.randomUUID(); val ownerB = UUID.randomUUID()
    val due = Instant.parse("2026-09-23T10:00:00Z")
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa); val schedules = new PostgresConnectionScheduleRepository
      val setup = for {
        _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'claim test')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($connection, $org, 'ORGANIZATION', 'SSH', 'claim', 'Claim')".update.run
        _ <- sql"insert into connection_schedule (organization_id, connection_id, enabled, interval_seconds, next_run_at, consecutive_failures) values ($org, $connection, true, 60, $due, 0)".update.run
      } yield ()
      val cleanup = sql"delete from organization where id = $org".update.run.void
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
