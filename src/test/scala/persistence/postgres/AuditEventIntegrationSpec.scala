package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.port.AuditEventRepository
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.audit.{AuditAction, AuditCursor, AuditEvent, AuditTargetType}
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.AuthorizationFixtures

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The audit journal against a real database: tenant scope, ordering, pagination and the fact
  * that an entry outlives the object it describes.
  */
final class AuditEventIntegrationSpec extends FunSuite {

  test("stores entries and reads the newest ones of one organization") {
    withFixture { fixture =>
      val events = List(oldest, middle, newest, foreign)

      for {
        _ <- fixture.run(fixture.repository.saveAll(events))
        page <- fixture.run(fixture.repository.listByOrganization(OrganizationId, None, 10))
        stored = page.filter(event => ids.contains(event.id))
      } yield IO {
        // Newest first, and a foreign organization is simply not part of the answer.
        assertEquals(stored.map(_.id), List(newest.id, middle.id, oldest.id))
        assertEquals(stored.map(_.action.code),
          List("MONITOR_RULE_UPDATED", "CONNECTION_DELETED", "PROJECT_CREATED"))
        assertEquals(stored.head.actorUserId, AuthorizationFixtures.ActorUserId)
        assertEquals(stored.head.targetType, AuditTargetType.MonitorRule)
        assertEquals(stored.head.occurredAt, newest.occurredAt)
        assert(!page.exists(_.id == foreign.id), "an entry of another organization was returned")
        assert(!page.exists(_.organizationId != OrganizationId))
      }
    }
  }

  test("a page continues after its cursor even when timestamps are equal") {
    withFixture { fixture =>
      // Two entries share a timestamp: only the (occurred_at, id) pair orders them apart.
      val tied = middle.copy(id = TiedId, occurredAt = newest.occurredAt, createdAt = newest.occurredAt)

      for {
        _ <- fixture.run(fixture.repository.saveAll(List(oldest, middle, newest, tied)))
        first <- fixture.run(fixture.repository.listByOrganization(OrganizationId, None, 2))
        next <- fixture.run(fixture.repository.listByOrganization(OrganizationId,
          Some(AuditCursor(first.last.occurredAt, first.last.id)), 2))
        afterOldest <- fixture.run(fixture.repository.listByOrganization(OrganizationId,
          Some(AuditCursor(oldest.occurredAt, oldest.id)), 10))
      } yield IO {
        assertEquals(first.size, 2)
        assertEquals(first.map(_.id).toSet, Set(newest.id, TiedId))
        assert(!next.map(_.id).contains(first.last.id), "the cursor row came back on the next page")
        assertEquals(next.filter(event => ids.contains(event.id) || event.id == TiedId).map(_.id),
          List(middle.id, oldest.id))
        assertEquals(afterOldest.filter(event => ids.contains(event.id)), List.empty)
      }
    }
  }

  test("an entry survives the object it describes") {
    withFixture { fixture =>
      val connectionId = UUID.randomUUID()
      val deletion = oldest.copy(id = UUID.randomUUID(), action = AuditAction.ConnectionDeleted,
        targetType = AuditTargetType.Connection, targetId = Some(connectionId))

      for {
        _ <- fixture.run(sql"""
          insert into connection (id, organization_id, scope_type, connector_type, code, name)
          values ($connectionId, $OrganizationId, 'ORGANIZATION', 'SSH',
            ${connectionId.toString}, 'Audit fixture')
        """.update.run)
        _ <- fixture.run(fixture.repository.save(deletion))
        _ <- fixture.run(
          sql"delete from connection where organization_id = $OrganizationId and id = $connectionId"
            .update.run
        )
        stored <- fixture.run(fixture.repository.listByOrganization(OrganizationId, None, 50))
        connections <- fixture.run(
          sql"select count(*) from connection where id = $connectionId".query[Int].unique
        )
      } yield IO {
        assertEquals(connections, 0)
        // No foreign key on the target, exactly so this entry can still be read.
        assertEquals(stored.find(_.id == deletion.id).map(_.targetId), Some(Some(connectionId)))
      }
    }
  }

  test("the recorder writes through the transaction it is given") {
    withFixture { fixture =>
      val recorder = new AuditRecorder[ConnectionIO](fixture.repository,
        new ConnectionIOIdGenerator, new ConnectionIOTimeProvider)
      val targetId = UUID.randomUUID()

      for {
        before <- IO(Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(1))
        _ <- fixture.run(recorder.record(AuthorizationFixtures.actor(OrganizationId),
          AuditAction.ProjectCreated, AuditTargetType.Project, Some(targetId)))
        stored <- fixture.run(fixture.repository.listByOrganization(OrganizationId, None, 50))
        event = stored.find(_.targetId.contains(targetId))
      } yield IO {
        assertEquals(event.map(_.action), Some(AuditAction.ProjectCreated))
        assertEquals(event.map(_.actorUserId), Some(AuthorizationFixtures.ActorUserId))
        assert(event.exists(!_.occurredAt.isBefore(before)))
      }
    }
  }

  test("an entry for another organization or an unknown actor is refused by the schema") {
    withFixture { fixture =>
      val unknownOrganization = oldest.copy(id = UUID.randomUUID(), organizationId = UUID.randomUUID())
      val unknownActor = oldest.copy(id = UUID.randomUUID(), actorUserId = UUID.randomUUID())

      for {
        organizationOutcome <- fixture.run(fixture.repository.save(unknownOrganization)).attempt
        actorOutcome <- fixture.run(fixture.repository.save(unknownActor)).attempt
      } yield IO {
        assert(organizationOutcome.isLeft)
        assert(actorOutcome.isLeft)
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private def withFixture(body: JournalFixture => IO[IO[Unit]]): Unit = {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val fixture = new JournalFixture(new DoobieTransactionRunner(xa))
      (fixture.setUp *> body(fixture).flatten).guarantee(fixture.cleanUp)
    }.unsafeRunSync()
  }

  private final class JournalFixture(runner: DoobieTransactionRunner) {
    val repository: AuditEventRepository[ConnectionIO] = new PostgresAuditEventRepository

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    /** A second tenant, so the scope of a listing is actually observable. */
    def setUp: IO[Unit] = run(sql"""
      insert into organization (id, code, name)
      values ($ForeignOrganizationId, 'audit-foreign-fixture', 'Audit foreign fixture')
      on conflict do nothing
    """.update.run.void) *> cleanUp

    def cleanUp: IO[Unit] = run(
      sql"delete from audit_event where organization_id in ($OrganizationId, $ForeignOrganizationId)"
        .update.run.void
    ).attempt.void
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val ForeignOrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000fe")
  private val TiedId = UUID.fromString("e0000000-0000-0000-0000-0000000000ff")
  // Claims and ordering compare against stored timestamps, so the fixture uses real time.
  private val At = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(600)

  private def event(
    id: UUID,
    action: AuditAction,
    targetType: AuditTargetType,
    occurredAt: Instant,
    organizationId: UUID = OrganizationId
  ): AuditEvent =
    AuditEvent(id, organizationId, AuthorizationFixtures.ActorUserId, action, targetType,
      Some(UUID.randomUUID()), occurredAt, occurredAt)

  private val oldest = event(UUID.fromString("e0000000-0000-0000-0000-0000000000a1"),
    AuditAction.ProjectCreated, AuditTargetType.Project, At)
  private val middle = event(UUID.fromString("e0000000-0000-0000-0000-0000000000a2"),
    AuditAction.ConnectionDeleted, AuditTargetType.Connection, At.plusSeconds(60))
  private val newest = event(UUID.fromString("e0000000-0000-0000-0000-0000000000a3"),
    AuditAction.MonitorRuleUpdated, AuditTargetType.MonitorRule, At.plusSeconds(120))
  private val foreign = event(UUID.fromString("e0000000-0000-0000-0000-0000000000a4"),
    AuditAction.EnvironmentCreated, AuditTargetType.Environment, At.plusSeconds(180),
    organizationId = ForeignOrganizationId)

  private val ids = Set(oldest.id, middle.id, newest.id)
}
