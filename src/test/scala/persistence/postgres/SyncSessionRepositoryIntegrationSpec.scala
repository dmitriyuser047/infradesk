package ru.bitec.app.ops
package persistence.postgres

import application.connector.{SyncFailure, SyncSessionPolicy}
import application.port.SyncSessionClaim
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.sync.{SyncSession, SyncSessionStatus}
import infrastructure.database.{Database, DatabaseConfig, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.free.{connection => FC}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class SyncSessionRepositoryIntegrationSpec extends FunSuite {
  test("PostgreSQL RUNNING index, failure metadata and scoped recent history") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")

    val org = UUID.randomUUID()
    val foreignOrg = UUID.randomUUID()
    val connection = UUID.randomUUID()
    val otherConnection = UUID.randomUUID()
    val foreignConnection = UUID.randomUUID()
    val at = Instant.parse("2026-09-23T10:00:00Z")

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val sessions = new PostgresSyncSessionRepository
      val setup: ConnectionIO[Unit] = for {
        _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'Sync test')".update.run
        _ <- sql"insert into organization (id, code, name) values ($foreignOrg, ${foreignOrg.toString}, 'Sync foreign test')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($connection, $org, 'ORGANIZATION', 'SSH', 'one', 'One')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($otherConnection, $org, 'ORGANIZATION', 'SSH', 'two', 'Two')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($foreignConnection, $foreignOrg, 'ORGANIZATION', 'SSH', 'foreign', 'Foreign')".update.run
      } yield ()
      val cleanup: ConnectionIO[Unit] = for {
        _ <- sql"delete from sync_session where organization_id in ($org, $foreignOrg)".update.run
        _ <- sql"delete from connection where organization_id in ($org, $foreignOrg)".update.run
        _ <- sql"delete from organization where id in ($org, $foreignOrg)".update.run
      } yield ()
      def running(id: UUID, organizationId: UUID, connectionId: UUID, started: Instant) =
        SyncSession(id, organizationId, connectionId, started, started.plusSeconds(900), None,
          SyncSessionStatus.Running)

      runner.run(setup) *> (for {
        first <- IO(UUID.randomUUID())
        second <- IO(UUID.randomUUID())
        other <- IO(UUID.randomUUID())
        foreign <- IO(UUID.randomUUID())
        created <- runner.run(sessions.tryCreate(running(first, org, connection, at)))
        duplicate <- runner.run(sessions.tryCreate(running(second, org, connection, at.plusSeconds(1))))
        otherCreated <- runner.run(sessions.tryCreate(running(other, org, otherConnection, at)))
        foreignCreated <- runner.run(sessions.tryCreate(running(foreign, foreignOrg, foreignConnection, at)))
        _ <- IO { assert(created); assert(!duplicate); assert(otherCreated); assert(foreignCreated) }
        _ <- runner.run(sessions.fail(org, first, at.plusSeconds(2), "SYNC_FAILED", "Synchronization failed"))
        failed <- runner.run(sessions.findById(org, connection, first))
        _ <- IO {
          assertEquals(failed.flatMap(_.errorCode), Some("SYNC_FAILED"))
          assertEquals(failed.flatMap(_.errorMessage), Some("Synchronization failed"))
          assertEquals(failed.map(_.status), Some(SyncSessionStatus.Failed))
        }
        more <- IO((1 to 22).map(_ => UUID.randomUUID()).toList)
        _ <- runner.run(more.zipWithIndex.traverse_ { case (id, index) =>
          sessions.create(SyncSession(id, org, connection, at.plusSeconds(index + 3),
            at.plusSeconds(index + 903), Some(at.plusSeconds(index + 4)),
            SyncSessionStatus.Completed))
        })
        recent <- runner.run(sessions.findRecentByConnection(org, connection, 20))
        scoped <- runner.run(sessions.findById(org, connection, foreign))
        _ <- IO {
          assertEquals(recent.length, 20)
          assert(recent.forall(s => s.organizationId == org && s.connectionId == connection))
          assertEquals(recent.map(_.startedAt), recent.map(_.startedAt).sortWith(_.isAfter(_)))
          assertEquals(scoped, None)
        }
      } yield ()).guarantee(runner.run(cleanup))
    }.unsafeRunSync()
  }

  test("PostgreSQL atomically recovers stale RUNNING and admits only one concurrent replacement") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")

    val org = UUID.randomUUID()
    val staleConnection = UUID.randomUUID()
    val boundaryConnection = UUID.randomUUID()
    val terminalConnection = UUID.randomUUID()
    val oldStaleId = UUID.randomUUID()
    val oldBoundaryId = UUID.randomUUID()
    val completedId = UUID.randomUUID()
    val failedId = UUID.randomUUID()
    val journalConnection = UUID.randomUUID()
    val firstNewId = UUID.randomUUID()
    val secondNewId = UUID.randomUUID()
    val longRunningConnection = UUID.randomUUID()
    val longRunningId = UUID.randomUUID()
    val now = Instant.parse("2026-09-23T10:00:00Z")

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val sessions = new PostgresSyncSessionRepository
      val setup: ConnectionIO[Unit] = for {
        _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'Stale recovery test')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($staleConnection, $org, 'ORGANIZATION', 'SSH', 'stale', 'Stale')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($boundaryConnection, $org, 'ORGANIZATION', 'SSH', 'boundary', 'Boundary')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($terminalConnection, $org, 'ORGANIZATION', 'SSH', 'terminal', 'Terminal')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($journalConnection, $org, 'ORGANIZATION', 'SSH', 'journal', 'Journal')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($longRunningConnection, $org, 'ORGANIZATION', 'SSH', 'long-running', 'Long running')".update.run
      } yield ()
      val cleanup: ConnectionIO[Unit] = for {
        _ <- sql"delete from sync_session where organization_id = $org".update.run
        _ <- sql"delete from connection where organization_id = $org".update.run
        _ <- sql"delete from organization where id = $org".update.run
      } yield ()
      /** A session with the deadline its own attempt was given when it started. */
      def running(
        id: UUID,
        connectionId: UUID,
        startedAt: Instant,
        recoverAfterAt: Instant = now.plusSeconds(900)
      ): SyncSession =
        SyncSession(id, org, connectionId, startedAt, recoverAfterAt, None,
          SyncSessionStatus.Running)
      def claim(session: SyncSession): IO[SyncSessionClaim] =
        runner.run(sessions.recoverStaleAndTryCreate(session, now,
          SyncFailure.Stale.code, SyncFailure.Stale.message))
      def recover(session: SyncSession): IO[Boolean] = claim(session).map(_.created)

      runner.run(setup) *> (for {
        // Its deadline has passed.
        _ <- runner.run(sessions.tryCreate(running(oldStaleId, staleConnection,
          now.minusSeconds(1800), now.minusSeconds(1))))
        // Its deadline falls exactly now: the boundary belongs to the session that holds it.
        _ <- runner.run(sessions.tryCreate(running(oldBoundaryId, boundaryConnection,
          now.minusSeconds(1800), now)))
        // Started long ago but with a long budget of its own, as a slow SSH inventory has.
        _ <- runner.run(sessions.tryCreate(running(longRunningId, longRunningConnection,
          now.minusSeconds(1800), now.plusSeconds(600))))
        _ <- runner.run(sessions.create(SyncSession(completedId, org, terminalConnection,
          now.minusSeconds(2000), now.minusSeconds(1100), Some(now.minusSeconds(1999)),
          SyncSessionStatus.Completed)))
        _ <- runner.run(sessions.tryCreate(running(failedId, terminalConnection,
          now.minusSeconds(1900), now.minusSeconds(1000))))
        _ <- runner.run(sessions.fail(org, failedId, now.minusSeconds(1899),
          SyncFailure.Generic.code, SyncFailure.Generic.message))

        claims <- (claim(running(firstNewId, staleConnection, now)),
          claim(running(secondNewId, staleConnection, now))).parTupled
        results = (claims._1.created, claims._2.created)
        staleHistory <- runner.run(sessions.findRecentByConnection(org, staleConnection, 10))
        activeCount <- runner.run(sql"select count(*) from sync_session where organization_id = $org and connection_id = $staleConnection and status = 'RUNNING'".query[Long].unique)
        freshCreated <- recover(running(UUID.randomUUID(), boundaryConnection, now))
        boundary <- runner.run(sessions.findById(org, boundaryConnection, oldBoundaryId))
        longRunningRejected <- recover(running(UUID.randomUUID(), longRunningConnection, now))
        longRunning <- runner.run(sessions.findById(org, longRunningConnection, longRunningId))
        terminalCreated <- recover(running(UUID.randomUUID(), terminalConnection, now))
        completed <- runner.run(sessions.findById(org, terminalConnection, completedId))
        failed <- runner.run(sessions.findById(org, terminalConnection, failedId))
        // The retirement and the fact that reports it are one transaction: a failing journal
        // leaves the abandoned session running and creates no replacement.
        journalFailureId <- IO(UUID.randomUUID())
        _ <- runner.run(sessions.tryCreate(running(journalFailureId, journalConnection,
          now.minusSeconds(1800), now.minusSeconds(60))))
        rolledBack <- runner.run(
          sessions.recoverStaleAndTryCreate(running(UUID.randomUUID(), journalConnection, now),
            now, SyncFailure.Stale.code, SyncFailure.Stale.message) *>
            FC.raiseError[Unit](new IllegalStateException("history unavailable"))
        ).attempt
        afterRollback <- runner.run(sessions.findById(org, journalConnection, journalFailureId))
        _ <- runner.run(sql"delete from sync_session where organization_id = $org and id = $journalFailureId".update.run)
        _ <- IO {
          assert(rolledBack.isLeft)
          assertEquals(afterRollback.map(_.status), Some(SyncSessionStatus.Running))
          assertEquals(List(results._1, results._2).sorted, List(false, true))
          // The claim hands the retired session back, so its fact can be journalled without
          // reading it again.
          assertEquals(List(claims._1, claims._2).flatMap(_.recovered).map(_.id), List(oldStaleId))
          assertEquals(List(claims._1, claims._2).flatMap(_.recovered).map(_.status),
            List(SyncSessionStatus.Failed))
          assertEquals(activeCount, 1L)
          assertEquals(staleHistory.length, 2)
          assert(staleHistory.exists(_.id == oldStaleId))
          assertEquals(staleHistory.count(s => s.id == firstNewId || s.id == secondNewId), 1)
          assertEquals(staleHistory.head.status, SyncSessionStatus.Running)
          val recovered = staleHistory.find(_.id == oldStaleId).get
          assertEquals(recovered.status, SyncSessionStatus.Failed)
          assertEquals(recovered.finishedAt, Some(now))
          assertEquals(recovered.errorCode, Some(SyncFailure.Stale.code))
          assertEquals(recovered.errorMessage, Some(SyncFailure.Stale.message))
          assertEquals(freshCreated, false)
          assertEquals(boundary.map(_.status), Some(SyncSessionStatus.Running))
          // A session whose own budget still covers it is never retired, however long ago it
          // started — this is what a twenty-minute SSH inventory relies on.
          assertEquals(longRunningRejected, false)
          assertEquals(longRunning.map(_.status), Some(SyncSessionStatus.Running))
          assertEquals(longRunning.flatMap(_.errorCode), None)
          assertEquals(terminalCreated, true)
          assertEquals(completed.map(_.status), Some(SyncSessionStatus.Completed))
          assertEquals(failed.flatMap(_.errorCode), Some(SyncFailure.Generic.code))
        }
      } yield ()).guarantee(runner.run(cleanup))
    }.unsafeRunSync()
  }
}
