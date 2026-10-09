package ru.bitec.app.ops
package persistence.postgres

import application.port.ConnectionDeletion
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class ConnectionLifecycleIntegrationSpec extends FunSuite {
  private val now = Instant.parse("2026-10-09T12:00:00Z")

  test("deleting a connection tombstones it, frees its code and takes only the servers no live connection still sees") {
    withWorld { w =>
      for {
        doomed <- w.connection("doomed")
        other <- w.connection("other")
        alone <- w.resource("NODE", None)
        child <- w.resource("CONTAINER", Some(alone))
        shared <- w.resource("NODE", None)
        sharedChild <- w.resource("CONTAINER", Some(shared))
        _ <- w.discover(doomed, alone)
        _ <- w.discover(doomed, shared)
        _ <- w.discover(other, shared)
        result <- w.run(w.repo.delete(w.org, doomed, now))
        stored <- w.run(sql"select is_active, deleted_at from connection where id = $doomed".query[(Boolean, Option[Instant])].unique)
        schedule <- w.run(sql"select enabled from connection_schedule where connection_id = $doomed".query[Boolean].unique)
        active <- w.run(sql"select id, is_active from resource where organization_id = ${w.org}".query[(UUID, Boolean)].to[List]).map(_.toMap)
        visible <- w.run(w.connections.findById(w.org, doomed))
        listed <- w.run(w.connections.findByOrganization(w.org)).map(_.map(_.id))
        again <- w.run(w.repo.delete(w.org, doomed, now))
        // The code of the deleted connection can be used again.
        reused <- w.connection("doomed").attempt
      } yield {
        assertEquals(result, ConnectionDeletion.Deleted(Some(s"db:${w.secretOf(doomed)}"), 2))
        assertEquals(stored, (false, Some(now)))
        assertEquals(schedule, false)
        assertEquals(active, Map(alone -> false, child -> false, shared -> true, sharedChild -> true))
        assertEquals(visible, None)
        assertEquals(listed, List(other))
        assertEquals(again, ConnectionDeletion.NotFound)
        assert(reused.isRight, clues(reused))
      }
    }
  }

  test("a server seen by an already deleted connection goes with the last live one") {
    withWorld { w =>
      for {
        first <- w.connection("first")
        second <- w.connection("second")
        node <- w.resource("NODE", None)
        _ <- w.discover(first, node)
        _ <- w.discover(second, node)
        _ <- w.run(w.repo.delete(w.org, first, now))
        stillActive <- w.run(sql"select is_active from resource where id = $node".query[Boolean].unique)
        _ <- w.run(w.repo.delete(w.org, second, now))
        finallyActive <- w.run(sql"select is_active from resource where id = $node".query[Boolean].unique)
      } yield {
        assertEquals(stillActive, true)
        assertEquals(finallyActive, false)
      }
    }
  }

  test("running work blocks deletion and changes nothing; a finished run does not block") {
    withWorld { w =>
      for {
        id <- w.connection("busy")
        node <- w.resource("NODE", None)
        _ <- w.discover(id, node)
        session = UUID.randomUUID()
        _ <- w.run(sql"""insert into sync_session (id, organization_id, connection_id, started_at, recover_after_at, status)
          values ($session, ${w.org}, $id, $now, ${now.plusSeconds(600)}, 'RUNNING')""".update.run)
        blocked <- w.run(w.repo.delete(w.org, id, now))
        untouched <- w.run(sql"select is_active, deleted_at is null from connection where id = $id".query[(Boolean, Boolean)].unique)
        nodeActive <- w.run(sql"select is_active from resource where id = $node".query[Boolean].unique)
        _ <- w.run(sql"""update sync_session set status = 'FAILED', finished_at = $now, error_code = 'X', error_message = 'x'
          where id = $session""".update.run)
        deleted <- w.run(w.repo.delete(w.org, id, now))
      } yield {
        assertEquals(blocked, ConnectionDeletion.Busy)
        assertEquals(untouched, (true, true))
        assertEquals(nodeActive, true)
        assert(deleted.isInstanceOf[ConnectionDeletion.Deleted], clues(deleted))
      }
    }
  }

  test("another organization cannot delete the connection") {
    withWorld { w =>
      for {
        id <- w.connection("tenant")
        foreign <- w.run(w.repo.delete(UUID.randomUUID(), id, now))
        stored <- w.run(sql"select deleted_at is null from connection where id = $id".query[Boolean].unique)
      } yield {
        assertEquals(foreign, ConnectionDeletion.NotFound)
        assert(stored)
      }
    }
  }

  test("a deleted connection is always inactive") {
    withWorld { w =>
      w.connection("constraint").flatMap { id =>
        w.run(sql"update connection set deleted_at = $now where id = $id".update.run).attempt.map(result =>
          assert(result.isLeft, "an active tombstone must be rejected"))
      }
    }
  }

  private def withWorld(body: World => IO[Unit]): Unit = {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"), "PostgreSQL integration tests disabled")
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val world = new World(xa)
      (world.setUp *> body(world)).guarantee(world.cleanUp)
    }.unsafeRunSync()
  }

  private final class World(xa: Transactor[IO]) {
    private val runner = new DoobieTransactionRunner(xa)
    val repo = new PostgresConnectionLifecycleRepository
    val connections = new PostgresConnectionRepository
    val org: UUID = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val environment = UUID.randomUUID()
    private val secrets = scala.collection.mutable.Map.empty[UUID, UUID]

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)
    def secretOf(connection: UUID): UUID = secrets(connection)

    def setUp: IO[Unit] = run(for {
      _ <- sql"insert into organization (id, code, name) values ($org, ${s"lifecycle-${org.toString.take(8)}"}, 'Lifecycle')".update.run
      _ <- sql"insert into project (id, organization_id, code, name) values ($project, $org, 'p', 'Project')".update.run
      _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($environment, $org, $project, 'e', 'Production', 'PROD')".update.run
    } yield ())

    def cleanUp: IO[Unit] = run(for {
      _ <- sql"delete from sync_session where organization_id = $org".update.run
      _ <- sql"delete from external_ref where organization_id = $org".update.run
      _ <- sql"delete from resource where organization_id = $org and parent_resource_id is not null".update.run
      _ <- sql"delete from resource where organization_id = $org".update.run
      _ <- sql"delete from connection_schedule where organization_id = $org".update.run
      _ <- sql"delete from connection where organization_id = $org".update.run
      _ <- sql"delete from environment where organization_id = $org".update.run
      _ <- sql"delete from project where organization_id = $org".update.run
      _ <- sql"delete from organization where id = $org".update.run
    } yield ())

    def connection(code: String): IO[UUID] = {
      val id = UUID.randomUUID()
      val secret = UUID.randomUUID()
      secrets.update(id, secret)
      run(for {
        _ <- sql"""insert into connection (id, organization_id, scope_type, connector_type, code, name, secret_ref)
          values ($id, $org, 'ORGANIZATION', 'SSH', $code, $code, ${s"db:$secret"})""".update.run
        _ <- sql"""insert into connection_schedule (organization_id, connection_id, enabled, interval_seconds, next_run_at)
          values ($org, $id, true, 600, $now)""".update.run
      } yield id)
    }

    def resource(typeCode: String, parent: Option[UUID]): IO[UUID] = {
      val id = UUID.randomUUID()
      run(sql"""insert into resource (id, organization_id, environment_id, resource_type_id, parent_resource_id, code, name)
        values ($id, $org, $environment, (select id from resource_type where code = $typeCode), $parent,
          ${id.toString}, ${id.toString})""".update.run).as(id)
    }

    def discover(connection: UUID, resource: UUID): IO[Unit] =
      run(sql"""insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id)
        values (${UUID.randomUUID()}, $org, $connection, 'NODE', ${UUID.randomUUID().toString}, $resource)""".update.run).void
  }
}
