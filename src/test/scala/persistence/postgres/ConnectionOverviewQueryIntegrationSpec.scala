package ru.bitec.app.ops
package persistence.postgres

import application.connection.RepositoryConnectionOverviewQuery
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.sync.{SyncSession, SyncSessionStatus}
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.doobie.util.log.{LogEvent, LogHandler}

import java.time.Instant
import java.util.UUID

/** The connection list read: the same overview as the per-connection repositories, in a fixed
  * number of statements however many connections and however long their history.
  */
final class ConnectionOverviewQueryIntegrationSpec extends FunSuite {
  test("the connection list is the latest session and schedule of each connection, in three statements") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")

    val org = UUID.randomUUID()
    val foreignOrg = UUID.randomUUID()
    val at = Instant.parse("2026-09-29T10:00:00Z")
    val connections = (1 to 25).toList.map(_ => UUID.randomUUID())
    val foreignConnection = UUID.randomUUID()

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val sessions = new PostgresSyncSessionRepository
      val query = new PostgresConnectionOverviewQuery(new PostgresConnectionRepository)
      val reference = RepositoryConnectionOverviewQuery[ConnectionIO](new PostgresConnectionRepository, sessions,
        new PostgresConnectionScheduleRepository)
      val setup: ConnectionIO[Unit] = for {
        _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'Overview')".update.run
        _ <- sql"insert into organization (id, code, name) values ($foreignOrg, ${foreignOrg.toString}, 'Overview foreign')".update.run
        _ <- connections.zipWithIndex.traverse_ { case (id, index) =>
          sql"""insert into connection (id, organization_id, scope_type, connector_type, code, name)
                values ($id, $org, 'ORGANIZATION', 'SSH', ${s"c$index"}, ${f"Connection $index%02d"})""".update.run
        }
        _ <- sql"""insert into connection (id, organization_id, scope_type, connector_type, code, name)
                   values ($foreignConnection, $foreignOrg, 'ORGANIZATION', 'SSH', 'foreign', 'Foreign')""".update.run
        // Every other connection has a schedule; each has a history of finished sessions, the first none.
        _ <- connections.zipWithIndex.filter(_._2 % 2 == 0).traverse_ { case (id, index) =>
          sql"""insert into connection_schedule (organization_id, connection_id, enabled, interval_seconds, next_run_at, consecutive_failures)
                values ($org, $id, true, ${60L + index}, $at, 0)""".update.run
        }
      } yield ()
      val cleanup: ConnectionIO[Unit] = for {
        _ <- sql"delete from sync_session where organization_id in ($org, $foreignOrg)".update.run
        _ <- sql"delete from connection_schedule where organization_id in ($org, $foreignOrg)".update.run
        _ <- sql"delete from connection where organization_id in ($org, $foreignOrg)".update.run
        _ <- sql"delete from organization where id in ($org, $foreignOrg)".update.run
      } yield ()
      def statements[A](program: ConnectionIO[A]): IO[(A, Int)] =
        Ref.of[IO, Int](0).flatMap { counter =>
          val handler = new LogHandler[IO] { override def run(event: LogEvent): IO[Unit] = counter.update(_ + 1) }
          val config = PostgresTestDatabase.config
          val logged = Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, Some(handler))
          new DoobieTransactionRunner(logged).run(program).flatMap(result => counter.get.map(result -> _))
        }

      runner.run(setup) *> (for {
        _ <- runner.run(connections.drop(1).zipWithIndex.traverse_ { case (connection, index) =>
          // Five completed sessions, then the latest one failed.
          (0 until 6).toList.traverse_ { n =>
            val started = at.plusSeconds(index * 100L + n)
            val (status, code, message) = if (n == 5) ("FAILED", Option("SYNC_FAILED"), Option("Synchronization failed"))
              else ("COMPLETED", None, None)
            sql"""insert into sync_session (id, organization_id, connection_id, started_at, recover_after_at, finished_at,
                    status, error_code, error_message)
                  values (${UUID.randomUUID()}, $org, $connection, $started, ${started.plusSeconds(900)},
                    ${started.plusSeconds(1)}, $status, $code, $message)""".update.run.void
          }
        })
        _ <- runner.run(sessions.create(SyncSession(UUID.randomUUID(), foreignOrg, foreignConnection, at.plusSeconds(99999),
          at.plusSeconds(100899), None, SyncSessionStatus.Running)))
        read <- statements(query.listByOrganization(org))
        (list, count) = read
        expected <- runner.run(reference.listByOrganization(org))
        one <- statements(query.listByOrganization(foreignOrg)).map(_._2)
        empty <- runner.run(query.listByOrganization(UUID.randomUUID()))
      } yield {
        assertEquals(list, expected)
        assertEquals(list.map(_.connection.id).toSet, connections.toSet)
        assertEquals(list.head.lastSync, None)
        // The latest of six sessions, never an older one.
        assert(list.drop(1).forall(_.lastSync.exists(_.status == SyncSessionStatus.Failed)))
        assertEquals(list.count(_.schedule.isDefined), 13)
        // Three statements for 25 connections and for one: the list does not grow with its rows.
        assertEquals((count, one), (3, 3))
        assertEquals(empty, Nil)
      }).guarantee(runner.run(cleanup))
    }.unsafeRunSync()
  }
}
