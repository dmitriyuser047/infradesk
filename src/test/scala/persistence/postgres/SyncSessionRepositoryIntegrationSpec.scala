package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.sync.{SyncSession, SyncSessionStatus}
import infrastructure.database.{Database, DatabaseConfig, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
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

    Database.transactor(DatabaseConfig.load.unsafeRunSync()).use { xa =>
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
        SyncSession(id, organizationId, connectionId, started, None, SyncSessionStatus.Running)

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
            Some(at.plusSeconds(index + 4)), SyncSessionStatus.Completed))
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
}
