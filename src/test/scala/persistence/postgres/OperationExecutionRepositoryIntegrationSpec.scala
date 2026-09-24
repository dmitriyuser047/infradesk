package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.operation._
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class OperationExecutionRepositoryIntegrationSpec extends FunSuite {
  test("operation execution enforces concurrency, lifecycle, stale recovery, history and target resolution") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")
    val org = UUID.randomUUID(); val project = UUID.randomUUID(); val env = UUID.randomUUID()
    val actor = UUID.randomUUID(); val connection = UUID.randomUUID(); val secondConnection = UUID.randomUUID()
    val resource = UUID.randomUUID(); val otherResource = UUID.randomUUID()
    val resourceType = UUID.fromString("10000000-0000-0000-0000-000000000002")
    val at = Instant.parse("2026-09-24T10:00:00Z")

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val repository = new PostgresOperationExecutionRepository
      val targetQuery = new PostgresResourceOperationTargetQuery
      val setup = for {
        _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'Operations')".update.run
        _ <- sql"insert into project (id, organization_id, code, name) values ($project, $org, 'operations', 'Operations')".update.run
        _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($env, $org, $project, 'operations', 'Operations', 'TEST')".update.run
        _ <- sql"insert into user_account (id, email, password_hash, display_name, created_at, updated_at) values ($actor, ${s"$actor@example.test"}, 'x', 'Actor', current_timestamp, current_timestamp)".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($connection, $org, 'ORGANIZATION', 'SSH', 'ssh-one', 'SSH one')".update.run
        _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($secondConnection, $org, 'ORGANIZATION', 'SSH', 'ssh-two', 'SSH two')".update.run
        _ <- sql"insert into resource (id, organization_id, environment_id, resource_type_id, code, name) values ($resource, $org, $env, $resourceType, 'one', 'One')".update.run
        _ <- sql"insert into resource (id, organization_id, environment_id, resource_type_id, code, name) values ($otherResource, $org, $env, $resourceType, 'two', 'Two')".update.run
        _ <- sql"insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id) values (${UUID.randomUUID()}, $org, $connection, 'CONTAINER', '73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d', $resource)".update.run
      } yield ()
      val cleanup = for {
        _ <- sql"delete from operation_execution where organization_id = $org".update.run
        _ <- sql"delete from external_ref where organization_id = $org".update.run
        _ <- sql"delete from resource where organization_id = $org".update.run
        _ <- sql"delete from connection where organization_id = $org".update.run
        _ <- sql"delete from organization_membership where organization_id = $org".update.run
        _ <- sql"delete from user_account where id = $actor".update.run
        _ <- sql"delete from environment where id = $env".update.run
        _ <- sql"delete from project where id = $project".update.run
        _ <- sql"delete from organization where id = $org".update.run
      } yield ()
      def running(id: UUID, targetResource: UUID, startedAt: Instant) = OperationExecution(id, org,
        targetResource, actor, ResourceOperationCode.ContainerRestart, connection, "CONTAINER",
        "73ac69cf50927aadda817a4a31fdcf6b56f2d3cfe782dabeab65b961c230fc6d",
        OperationExecutionStatus.Running, startedAt, None, None, None, startedAt, startedAt)

      runner.run(setup) *> (for {
        first <- IO(UUID.randomUUID()); second <- IO(UUID.randomUUID())
        same <- (runner.run(repository.tryCreateRunning(running(first, resource, at))),
          runner.run(repository.tryCreateRunning(running(second, resource, at)))).parTupled
        other <- runner.run(repository.tryCreateRunning(running(UUID.randomUUID(), otherResource, at)))
        _ <- IO { assertEquals(List(same._1, same._2).sorted, List(false, true)); assert(other) }
        winner = if (same._1) first else second
        changed <- runner.run(repository.markSucceeded(org, winner, at.plusSeconds(1)))
        changedAgain <- runner.run(repository.markFailed(org, winner, at.plusSeconds(2), "X", "X"))
        _ <- IO { assert(changed); assert(!changedAgain) }
        staleId <- IO(UUID.randomUUID())
        _ <- runner.run(repository.tryCreateRunning(running(staleId, resource, at.minusSeconds(601))))
        recovered <- runner.run(repository.recoverStaleRunning(org, resource, at.minusSeconds(600), at,
          "OPERATION_RESULT_UNKNOWN", "Operation result is unknown because execution was interrupted"))
        stale <- runner.run(repository.findById(org, resource, staleId))
        history <- runner.run(repository.listByResource(org, resource, None, 20))
        target <- runner.run(targetQuery.find(org, resource))
        _ <- IO {
          assertEquals(recovered, List(staleId)); assertEquals(stale.map(_.status), Some(OperationExecutionStatus.Unknown))
          assertEquals(stale.flatMap(_.errorCode), Some("OPERATION_RESULT_UNKNOWN"))
          assertEquals(history.map(_.startedAt), history.map(_.startedAt).sortWith(_.isAfter(_)))
          assertEquals(target.map(_.targets.size), Some(1))
        }
        _ <- runner.run(sql"insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id) values (${UUID.randomUUID()}, $org, $secondConnection, 'CONTAINER', 'aaaaaaaaaaaa', $resource)".update.run)
        ambiguous <- runner.run(targetQuery.find(org, resource))
        _ <- IO(assertEquals(ambiguous.map(_.targets.size), Some(2)))
        _ <- runner.run(sql"update connection set is_active = false where id = $secondConnection".update.run)
        activeOnly <- runner.run(targetQuery.find(org, resource))
        _ <- IO(assertEquals(activeOnly.map(_.targets.size), Some(1)))
        // History: the same instant on several rows still paginates deterministically, and a page
        // never leaks another resource or another tenant.
        tiedHigh <- IO(UUID.fromString("f0000000-0000-0000-0000-0000000000f2"))
        tiedLow <- IO(UUID.fromString("f0000000-0000-0000-0000-0000000000f1"))
        tiedAt = at.plusSeconds(30)
        _ <- runner.run(repository.tryCreateRunning(running(tiedHigh, resource, tiedAt)) *>
          repository.markSucceeded(org, tiedHigh, tiedAt))
        _ <- runner.run(repository.tryCreateRunning(running(tiedLow, resource, tiedAt)) *>
          repository.markSucceeded(org, tiedLow, tiedAt))
        firstPage <- runner.run(repository.listByResource(org, resource, None, 2))
        secondPage <- runner.run(repository.listByResource(org, resource,
          Some(OperationExecutionCursor(firstPage.last.startedAt, firstPage.last.id)), 2))
        otherHistory <- runner.run(repository.listByResource(org, otherResource, None, 20))
        foreignHistory <- runner.run(repository.listByResource(UUID.randomUUID(), resource, None, 20))
        foreignFind <- runner.run(repository.findById(UUID.randomUUID(), resource, tiedHigh))
        _ <- IO {
          assertEquals(firstPage.map(_.id), List(tiedHigh, tiedLow))
          assert(!secondPage.map(_.id).contains(tiedLow), "the cursor row came back on the next page")
          assert(!otherHistory.map(_.resourceId).contains(resource))
          assertEquals(foreignHistory, List.empty)
          assertEquals(foreignFind, None)
        }
        rollbackId <- IO(UUID.randomUUID())
        failed <- runner.run(repository.tryCreateRunning(running(rollbackId, resource, at.plusSeconds(3))) *>
          new IllegalStateException("audit unavailable").raiseError[ConnectionIO, Unit]).attempt
        afterRollback <- runner.run(repository.findById(org, resource, rollbackId))
        _ <- IO { assert(failed.isLeft); assertEquals(afterRollback, None) }
      } yield ()).guarantee(runner.run(cleanup))
    }.unsafeRunSync()
  }
}
