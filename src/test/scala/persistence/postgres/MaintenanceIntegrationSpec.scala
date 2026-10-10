package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.monitor.{AcknowledgeIncident, CreateMaintenanceWindow, EvaluateMonitorRules, MaintenanceError, MaintenanceWindows}
import application.notification.{NotificationRecordingMonitorRuleEvaluator, RecordNotificationDeliveries}
import application.port.IncidentAcknowledgement
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.notification.NotificationDeliveryTarget
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

final class MaintenanceIntegrationSpec extends FunSuite {

  test("an incident opened during maintenance of the server is stored silenced and nobody is notified") {
    withWorld { w =>
      val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
      for {
        rule <- w.rule(w.node, "NODE")
        _ <- w.window(w.node, now.minusSeconds(60), now.plusSeconds(3600))
        _ <- w.observe(w.node, now.minusSeconds(5), 99)
        transitions <- w.run(w.evaluator.execute(w.org, w.connection, now))
        incident <- w.run(sql"select status, notifications_silenced from incident where monitor_rule_id = $rule".query[(String, Boolean)].unique)
        deliveries <- w.deliveries
        // Recovery after the window: the resolution of a silenced incident stays silent.
        _ <- w.observe(w.node, now.plusSeconds(7200), 1)
        _ <- w.run(w.evaluator.execute(w.org, w.connection, now.plusSeconds(7201)))
        resolved <- w.run(sql"select status from incident where monitor_rule_id = $rule".query[String].unique)
        afterResolution <- w.deliveries
      } yield {
        assertEquals(transitions.map(t => (t.eventName, t.notificationsSilenced)), List(("incident.opened", true)))
        assertEquals(incident, ("OPEN", true))
        assertEquals(deliveries, 0L)
        assertEquals(resolved, "RESOLVED")
        assertEquals(afterResolution, 0L)
      }
    }
  }

  test("a window on a server covers what runs on it; a cancelled, finished or future window covers nothing") {
    withWorld { w =>
      val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
      val query = new PostgresMonitorEvaluationQuery
      def covered(types: List[String]): IO[Boolean] =
        w.run(query.findEnabledForConnection(w.org, w.connection, types, now)).map(_.exists(_.inMaintenance))
      for {
        _ <- w.rule(w.container, "CONTAINER")
        _ <- w.rule(w.node, "NODE")
        none <- covered(List("CONTAINER"))
        finished <- w.window(w.node, now.minusSeconds(7200), now.minusSeconds(60))
        future <- w.window(w.node, now.plusSeconds(60), now.plusSeconds(600))
        stillNone <- covered(List("CONTAINER"))
        cancelled <- w.window(w.node, now.minusSeconds(60), now.plusSeconds(600))
        _ <- w.run(sql"update maintenance_window set cancelled_at = $now, cancelled_by = ${w.user} where id = $cancelled".update.run)
        afterCancel <- covered(List("CONTAINER"))
        _ <- w.window(w.node, now.minusSeconds(60), now.plusSeconds(600))
        child <- covered(List("CONTAINER"))
        server <- covered(List("NODE"))
      } yield {
        assert(!none && !stillNone && !afterCancel, clues(finished, future))
        assert(child && server)
      }
    }
  }

  test("windows are planned, listed and cancelled within their organization, and journalled once") {
    withWorld { w =>
      val actor = support.AuthorizationFixtures.actor(w.org, w.user)
      for {
        now <- IO.realTimeInstant
        created <- w.windows.create(actor, CreateMaintenanceWindow(w.node, now.plusSeconds(60), now.plusSeconds(3600), "  kernel upgrade  "))
        foreignResource <- w.windows.create(support.AuthorizationFixtures.actor(UUID.randomUUID(), w.user),
          CreateMaintenanceWindow(w.node, now.plusSeconds(60), now.plusSeconds(3600), "x")).attempt
        backdated <- w.windows.create(actor, CreateMaintenanceWindow(w.node, now.minusSeconds(3600), now.plusSeconds(60), "x")).attempt
        tooLong <- w.windows.create(actor, CreateMaintenanceWindow(w.node, now.plusSeconds(60),
          now.plusSeconds(60).plus(java.time.Duration.ofDays(31)), "x")).attempt
        listed <- w.windows.list(w.org)
        cancelled <- w.windows.cancel(actor, created.window.id)
        again <- w.windows.cancel(actor, created.window.id)
        foreignCancel <- w.windows.cancel(support.AuthorizationFixtures.actor(UUID.randomUUID(), w.user), created.window.id).attempt
        finished <- w.window(w.node, now.minusSeconds(7200), now.minusSeconds(60))
        finishedCancel <- w.windows.cancel(actor, finished).attempt
        audit <- w.run(sql"select action from audit_event where organization_id = ${w.org} order by occurred_at, action".query[String].to[List])
      } yield {
        assertEquals(created.window.reason, "kernel upgrade")
        assertEquals((created.resourceName, created.createdByName), ("node", "Operator"))
        def code(result: Either[Throwable, _]) = result.left.toOption.collect { case e: MaintenanceError => e.code }
        assertEquals(code(foreignResource), Some("RESOURCE_NOT_FOUND"))
        assertEquals(code(backdated), Some("INVALID_REQUEST"))
        assertEquals(code(tooLong), Some("INVALID_REQUEST"))
        assertEquals(listed._2.map(_.window.id), List(created.window.id))
        assert(cancelled.window.cancelledAt.nonEmpty)
        assertEquals(again.window.cancelledAt, cancelled.window.cancelledAt)
        assertEquals(cancelled.cancelledByName, Some("Operator"))
        assertEquals(code(foreignCancel), Some("MAINTENANCE_WINDOW_NOT_FOUND"))
        assertEquals(code(finishedCancel), Some("MAINTENANCE_WINDOW_FINISHED"))
        assertEquals(audit, List("MAINTENANCE_WINDOW_CREATED", "MAINTENANCE_WINDOW_CANCELLED"))
      }
    }
  }

  test("the first acknowledgement of an open incident stands and survives the incident's resolution") {
    withWorld { w =>
      val actor = support.AuthorizationFixtures.actor(w.org, w.user)
      val repository = new PostgresMaintenanceWindowRepository
      val incidents = new PostgresIncidentRepository
      for {
        now <- IO.realTimeInstant.map(_.truncatedTo(ChronoUnit.MILLIS))
        rule <- w.rule(w.node, "NODE")
        incident = Incident(UUID.randomUUID(), w.org, rule, w.node, IncidentStatus.Open, IncidentReason.ThresholdViolation,
          now.minusSeconds(60), now.minusSeconds(60), None, now.minusSeconds(60), now.minusSeconds(60), notificationsSilenced = true)
        _ <- w.run(incidents.saveAll(List(incident)))
        foreign <- w.run(repository.acknowledge(UUID.randomUUID(), incident.id, w.user, now))
        _ <- w.acknowledge.execute(actor, incident.id)
        second <- w.run(repository.acknowledge(w.org, incident.id, w.user, now.plusSeconds(10)))
        _ <- w.acknowledge.execute(actor, incident.id)
        // A resolution written by the evaluation must not erase the acknowledgement or the silencing.
        _ <- w.run(incidents.saveAll(List(incident.copy(status = IncidentStatus.Resolved, resolvedAt = Some(now), updatedAt = now))))
        stored <- w.run(incidents.findById(w.org, incident.id))
        resolvedAck <- w.acknowledge.execute(actor, incident.id).attempt
        audit <- w.run(sql"select count(*) from audit_event where organization_id = ${w.org} and action = 'INCIDENT_ACKNOWLEDGED'".query[Long].unique)
      } yield {
        assertEquals(foreign, IncidentAcknowledgement.NotFound)
        assertEquals(second, IncidentAcknowledgement.AlreadyAcknowledged)
        assertEquals(stored.map(i => (i.status, i.notificationsSilenced, i.acknowledgedBy, i.acknowledgedAt.nonEmpty)),
          Some((IncidentStatus.Resolved, true, Some(w.user), true)))
        assertEquals(resolvedAck.left.toOption.collect { case e: MaintenanceError => e.code }, Some("INCIDENT_NOT_OPEN"))
        assertEquals(audit, 1L)
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
    val runner = new DoobieTransactionRunner(xa)
    val org: UUID = UUID.randomUUID()
    val user: UUID = UUID.randomUUID()
    val connection: UUID = UUID.randomUUID()
    val node: UUID = UUID.randomUUID()
    val container: UUID = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val environment = UUID.randomUUID()

    private val audit = new AuditRecorder[ConnectionIO](new PostgresAuditEventRepository, new ConnectionIOIdGenerator,
      new ConnectionIOTimeProvider)
    private val maintenanceRepository = new PostgresMaintenanceWindowRepository
    val windows = new MaintenanceWindows[ConnectionIO](maintenanceRepository,
      ProductionResourceCodec.resourceRepository, runner, audit)
    val acknowledge = new AcknowledgeIncident[ConnectionIO](maintenanceRepository, runner, audit)
    val evaluator = new NotificationRecordingMonitorRuleEvaluator[ConnectionIO](
      new EvaluateMonitorRules[ConnectionIO](new PostgresMonitorEvaluationQuery, new PostgresMonitorRuleStateRepository,
        new PostgresIncidentRepository, new ConnectionIOIdGenerator),
      new RecordNotificationDeliveries[ConnectionIO](new PostgresNotificationDeliveryRepository,
        new support.NoNotificationRouting[ConnectionIO], new ConnectionIOIdGenerator, new ConnectionIOTimeProvider,
        List(NotificationDeliveryTarget.LegacyWebhook)))

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def setUp: IO[Unit] = run(for {
      _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'Maintenance')".update.run
      _ <- sql"""insert into user_account (id, email, password_hash, display_name, created_at, updated_at)
        values ($user, ${s"$user@example.test"}, 'x', 'Operator', now(), now())""".update.run
      _ <- sql"insert into project (id, organization_id, code, name) values ($project, $org, 'p', 'P')".update.run
      _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($environment, $org, $project, 'e', 'E', 'PROD')".update.run
      _ <- sql"""insert into connection (id, organization_id, scope_type, connector_type, code, name)
        values ($connection, $org, 'ORGANIZATION', 'SSH', 'c', 'C')""".update.run
      _ <- sql"""insert into resource (id, organization_id, environment_id, resource_type_id, code, name)
        values ($node, $org, $environment, (select id from resource_type where code = 'NODE'), 'node', 'node')""".update.run
      _ <- sql"""insert into resource (id, organization_id, environment_id, resource_type_id, parent_resource_id, code, name)
        values ($container, $org, $environment, (select id from resource_type where code = 'CONTAINER'), $node, 'c1', 'c1')""".update.run
      _ <- List(node, container).traverse_(id => sql"""insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id)
        values (${UUID.randomUUID()}, $org, $connection, 'X', ${id.toString}, $id)""".update.run)
    } yield ())

    def cleanUp: IO[Unit] = run(for {
      _ <- sql"delete from notification_delivery where organization_id = $org".update.run
      _ <- sql"delete from audit_event where organization_id = $org".update.run
      _ <- sql"delete from incident where organization_id = $org".update.run
      _ <- sql"delete from monitor_rule_state where organization_id = $org".update.run
      _ <- sql"delete from monitor_rule where organization_id = $org".update.run
      _ <- sql"delete from metric_observation where organization_id = $org".update.run
      _ <- sql"delete from maintenance_window where organization_id = $org".update.run
      _ <- sql"delete from external_ref where organization_id = $org".update.run
      _ <- sql"delete from resource where organization_id = $org and parent_resource_id is not null".update.run
      _ <- sql"delete from resource where organization_id = $org".update.run
      _ <- sql"delete from connection where organization_id = $org".update.run
      _ <- sql"delete from environment where organization_id = $org".update.run
      _ <- sql"delete from project where organization_id = $org".update.run
      _ <- sql"delete from user_account where id = $user".update.run
      _ <- sql"delete from organization where id = $org".update.run
    } yield ())

    def rule(resource: UUID, typeCode: String): IO[UUID] = {
      val id = UUID.randomUUID()
      run(sql"""insert into monitor_rule (id, organization_id, resource_id, metric_code, operator, threshold, for_seconds,
          no_data_seconds, enabled, created_at, updated_at)
        values ($id, $org, $resource, 'CPU_USAGE_PERCENT', 'GREATER_THAN', 90, 0, 0, true, now(), now())""".update.run).as(id)
    }

    def window(resource: UUID, startsAt: Instant, endsAt: Instant): IO[UUID] = {
      val id = UUID.randomUUID()
      run(sql"""insert into maintenance_window (id, organization_id, resource_id, starts_at, ends_at, reason, created_by, created_at)
        values ($id, $org, $resource, $startsAt, $endsAt, 'test', $user, ${startsAt.minusSeconds(1)})""".update.run).as(id)
    }

    def observe(resource: UUID, at: Instant, value: BigDecimal): IO[Unit] =
      run(sql"""insert into metric_observation (id, organization_id, resource_id, metric_code, value, observed_at)
        values (${UUID.randomUUID()}, $org, $resource, 'CPU_USAGE_PERCENT', $value, $at)""".update.run.void)

    def deliveries: IO[Long] =
      run(sql"select count(*) from notification_delivery where organization_id = $org".query[Long].unique)
  }
}
