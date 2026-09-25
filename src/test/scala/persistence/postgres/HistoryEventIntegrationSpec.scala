package ru.bitec.app.ops
package persistence.postgres

import application.history.{HistoryEntry, HistoryRecorder}
import application.monitor.EvaluateMonitorRules
import application.history.HistoryRecordingMonitorRuleEvaluator
import application.notification.{NotificationRecordingMonitorRuleEvaluator, RecordNotificationDeliveries}
import application.port.{HistoryEventRepository, HistoryEventView}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.history.{HistoryEvent, HistoryEventCursor, HistoryEventSource, HistoryEventType}
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.metric.{MetricCode, MetricObservation}
import domain.monitor.{MonitorOperator, MonitorRule}
import domain.notification.NotificationDeliveryTarget
import domain.resource.{Resource, ResourceData}
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.free.{connection => FC}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The timeline against a real database: ordering, isolation, pagination, the projection, and
  * the fact that a monitoring transition and its journal entry share one transaction.
  */
final class HistoryEventIntegrationSpec extends FunSuite {

  test("stores facts and reads the newest ones of one organization and one resource") {
    withFixture { fixture =>
      val events = List(oldest(fixture), middle(fixture), newestA(fixture), newestB(fixture),
        otherResource(fixture), foreign(fixture))

      for {
        _ <- fixture.run(fixture.repository.saveAll(events))
        organization <- fixture.run(fixture.query.listByOrganization(OrganizationId, None, 50))
        resource <- fixture.run(fixture.query.listByResource(OrganizationId, fixture.resourceId, None, 50))
        mine = organization.filter(event => fixture.ids.contains(event.id))
      } yield IO {
        // Newest first, and two facts of the same instant are ordered by id descending.
        assertEquals(mine.map(_.id), List(newestB(fixture).id, newestA(fixture).id,
          middle(fixture).id, otherResource(fixture).id, oldest(fixture).id))
        assert(!organization.exists(_.id == foreign(fixture).id), "a foreign tenant leaked in")
        assertEquals(resource.map(_.id).toSet,
          Set(newestB(fixture).id, newestA(fixture).id, middle(fixture).id, oldest(fixture).id))
        assert(!resource.exists(_.id == otherResource(fixture).id))
      }
    }
  }

  test("a page continues after its cursor and never returns that row again") {
    withFixture { fixture =>
      for {
        _ <- fixture.run(fixture.repository.saveAll(
          List(oldest(fixture), middle(fixture), newestA(fixture), newestB(fixture))))
        first <- fixture.run(fixture.query.listByResource(OrganizationId, fixture.resourceId, None, 2))
        next <- fixture.run(fixture.query.listByResource(OrganizationId, fixture.resourceId,
          Some(HistoryEventCursor(first.last.occurredAt, first.last.id)), 2))
      } yield IO {
        assertEquals(first.size, 2)
        assertEquals(next.size, 2)
        assert(!next.map(_.id).contains(first.last.id), "the cursor row came back")
        assertEquals((first ++ next).map(_.id).distinct.size, 4)
      }
    }
  }

  test("the projection resolves what an entry points at in one statement") {
    withFixture { fixture =>
      val event = HistoryEvent(UUID.randomUUID(), OrganizationId, HistoryEventType.IncidentOpened,
        HistoryEventSource.System, Some(fixture.resourceId), None, Some(fixture.incidentId), None,
        None, None, At, At)

      for {
        _ <- fixture.run(fixture.repository.save(event))
        rows <- fixture.run(fixture.query.listByResource(OrganizationId, fixture.resourceId, None, 10))
        view = rows.find(_.id == event.id).getOrElse(fail("the entry was not returned"))
      } yield IO {
        assertEquals(view.resource.map(_.name), Some(fixture.resourceCode))
        assertEquals(view.resource.map(_.resourceTypeCode), Some("NODE"))
        assertEquals(view.incident.map(_.status), Some("RESOLVED"))
        assertEquals(view.incident.map(_.reason), Some("THRESHOLD"))
        assertEquals(view.actor, None)
        assertEquals(view.operation, None)
        assertEquals(view.sync, None)
      }
    }
  }

  test("an evaluation commits the incident, the outbox row and the timeline entry together") {
    withFixture { fixture =>
      val observation = MetricObservation(UUID.randomUUID(), OrganizationId, fixture.resourceId,
        MetricCode.CpuUsagePercent, BigDecimal(95), At)

      for {
        _ <- fixture.run(fixture.metrics.insertAll(List(observation)))
        _ <- fixture.run(fixture.evaluator.execute(OrganizationId, ConnectionId, At))
        incidents <- fixture.run(fixture.incidents.findByOrganization(OrganizationId, None))
        opened = incidents.filter(incident =>
          incident.monitorRuleId == fixture.ruleId && incident.id != fixture.incidentId)
        events <- fixture.run(fixture.query.listByResource(OrganizationId, fixture.resourceId, None, 10))
        deliveries <- fixture.run(sql"""
          select count(*) from notification_delivery
          where organization_id = $OrganizationId and monitor_rule_id = ${fixture.ruleId}
        """.query[Int].unique)
      } yield IO {
        assertEquals(opened.map(_.status), List(IncidentStatus.Open))
        assertEquals(events.map(_.eventType.code), List("INCIDENT_OPENED"))
        assertEquals(events.head.source, HistoryEventSource.System)
        assertEquals(events.head.incident.map(_.id), opened.headOption.map(_.id))
        assertEquals(events.head.occurredAt, At)
        assertEquals(deliveries, 1)
      }
    }
  }

  test("a failing timeline rolls the whole monitoring transition back") {
    withFixture { fixture =>
      val observation = MetricObservation(UUID.randomUUID(), OrganizationId, fixture.resourceId,
        MetricCode.CpuUsagePercent, BigDecimal(95), At)

      for {
        _ <- fixture.run(fixture.metrics.insertAll(List(observation)))
        outcome <- fixture.run(fixture.failingEvaluator.execute(OrganizationId, ConnectionId, At)).attempt
        incidents <- fixture.run(fixture.incidents.findByOrganization(OrganizationId, None))
        states <- fixture.run(fixture.states.findByRuleId(OrganizationId, fixture.ruleId))
        events <- fixture.run(fixture.query.listByResource(OrganizationId, fixture.resourceId, None, 10))
        deliveries <- fixture.run(sql"""
          select count(*) from notification_delivery
          where organization_id = $OrganizationId and monitor_rule_id = ${fixture.ruleId}
        """.query[Int].unique)
      } yield IO {
        assert(outcome.isLeft)
        // No journal entry, and therefore no incident, no state and no delivery intent either.
        assertEquals(events, List.empty[HistoryEventView])
        assertEquals(incidents.count(incident =>
          incident.monitorRuleId == fixture.ruleId && incident.id != fixture.incidentId), 0)
        assertEquals(states, None)
        assertEquals(deliveries, 0)
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private def withFixture(body: TimelineFixture => IO[IO[Unit]]): Unit = {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val fixture = new TimelineFixture(new DoobieTransactionRunner(xa))
      (fixture.setUp *> body(fixture).flatten).guarantee(fixture.cleanUp)
    }.unsafeRunSync()
  }

  private final class TimelineFixture(runner: DoobieTransactionRunner) {
    val resourceId: UUID = UUID.randomUUID()
    val secondResourceId: UUID = UUID.randomUUID()
    val ruleId: UUID = UUID.randomUUID()
    val incidentId: UUID = UUID.randomUUID()
    val externalRefId: UUID = UUID.randomUUID()
    val resourceCode: String = s"history-${UUID.randomUUID().toString.take(8)}"
    private val secondCode = s"history-${UUID.randomUUID().toString.take(8)}"

    val repository: HistoryEventRepository[ConnectionIO] = new PostgresHistoryEventRepository
    val query = new PostgresHistoryEventQuery
    val incidents = new PostgresIncidentRepository
    val states = new PostgresMonitorRuleStateRepository
    val metrics = new PostgresMetricObservationRepository
    private val rules = new PostgresMonitorRuleRepository
    private val resources = ProductionResourceCodec.resourceRepository

    private val plainEvaluator = new EvaluateMonitorRules[ConnectionIO](
      new PostgresMonitorEvaluationQuery, states, incidents, new ConnectionIOIdGenerator
    )

    private def notifying = new NotificationRecordingMonitorRuleEvaluator[ConnectionIO](
      plainEvaluator,
      new RecordNotificationDeliveries[ConnectionIO](new PostgresNotificationDeliveryRepository,
        new support.NoNotificationRouting[ConnectionIO],
        new ConnectionIOIdGenerator, new ConnectionIOTimeProvider, List(NotificationDeliveryTarget.LegacyWebhook))
    )

    val evaluator = new HistoryRecordingMonitorRuleEvaluator[ConnectionIO](
      notifying,
      new HistoryRecorder[ConnectionIO](repository, new ConnectionIOIdGenerator,
        new ConnectionIOTimeProvider)
    )

    val failingEvaluator = new HistoryRecordingMonitorRuleEvaluator[ConnectionIO](
      notifying,
      new HistoryRecorder[ConnectionIO](new FailingHistoryEventRepository,
        new ConnectionIOIdGenerator, new ConnectionIOTimeProvider)
    )

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def events: List[HistoryEvent] =
      List(oldest(this), middle(this), newestA(this), newestB(this), otherResource(this),
        foreign(this))

    def ids: Set[UUID] = events.filter(_.organizationId == OrganizationId).map(_.id).toSet

    def setUp: IO[Unit] = {
      val resource = Resource(resourceId, OrganizationId, EnvironmentId, NodeResourceTypeId, None,
        resourceCode, resourceCode, isActive = true, At, At, "NODE", ResourceData.empty)
      val second = resource.copy(id = secondResourceId, code = secondCode, name = secondCode)
      val rule = MonitorRule(ruleId, OrganizationId, resourceId, MetricCode.CpuUsagePercent,
        MonitorOperator.GreaterThan, BigDecimal(90), 0, 900, enabled = true, At, At)
      val incident = Incident(incidentId, OrganizationId, ruleId, resourceId,
        IncidentStatus.Resolved, IncidentReason.ThresholdViolation, At, At, Some(At), At, At)

      run(sql"""
        insert into organization (id, code, name)
        values ($ForeignOrganizationId, 'history-foreign-fixture', 'History foreign fixture')
        on conflict do nothing
      """.update.run.void) *>
        run(sql"""
          insert into connection (id, organization_id, scope_type, connector_type, code, name)
          values ($ConnectionId, $OrganizationId, 'ORGANIZATION', 'SSH', ${ConnectionId.toString},
            'Integration fixture')
          on conflict do nothing
        """.update.run.void) *>
        run(resources.save(resource)) *>
        run(resources.save(second)) *>
        run(rules.save(rule)) *>
        run(sql"""
          insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id)
          values ($externalRefId, $OrganizationId, $ConnectionId, 'NODE', $resourceCode, $resourceId)
          on conflict do nothing
        """.update.run.void) *>
        run(incidents.saveAll(List(incident)))
    }

    def cleanUp: IO[Unit] = run(for {
      _ <- sql"delete from history_event where organization_id in ($OrganizationId, $ForeignOrganizationId) and (resource_id in ($resourceId, $secondResourceId) or resource_id is null)".update.run
      _ <- sql"delete from notification_delivery where organization_id = $OrganizationId and monitor_rule_id = $ruleId".update.run
      _ <- sql"delete from metric_observation where organization_id = $OrganizationId and resource_id = $resourceId".update.run
      _ <- sql"delete from incident where organization_id = $OrganizationId and monitor_rule_id = $ruleId".update.run
      _ <- sql"delete from monitor_rule_state where organization_id = $OrganizationId and monitor_rule_id = $ruleId".update.run
      _ <- sql"delete from monitor_rule where organization_id = $OrganizationId and resource_id = $resourceId".update.run
      _ <- sql"delete from external_ref where organization_id = $OrganizationId and resource_id = $resourceId".update.run
      _ <- sql"delete from connection where organization_id = $OrganizationId and id = $ConnectionId".update.run
      _ <- sql"delete from resource where organization_id = $OrganizationId and id in ($resourceId, $secondResourceId)".update.run
    } yield ()).attempt.void
  }

  private final class FailingHistoryEventRepository extends HistoryEventRepository[ConnectionIO] {
    override def save(event: HistoryEvent): ConnectionIO[Unit] = saveAll(List(event))
    override def saveAll(events: List[HistoryEvent]): ConnectionIO[Unit] =
      if (events.isEmpty) FC.unit
      else FC.raiseError(new IllegalStateException("history unavailable"))
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val ForeignOrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000fd")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val NodeResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  // Each PostgreSQL suite evaluates every rule reachable through the connection it names, so a
  // shared fixture connection would let suites running in parallel evaluate each other's rules.
  // This one belongs to this run alone.
  private val ConnectionId: UUID = UUID.randomUUID()
  // Ordering compares stored timestamps, so the fixture works in real time.
  private val At = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(900)

  private def event(
    id: UUID,
    organizationId: UUID,
    resourceId: Option[UUID],
    occurredAt: Instant,
    eventType: HistoryEventType = HistoryEventType.ResourceDiscovered
  ): HistoryEvent =
    HistoryEvent(id, organizationId, eventType, HistoryEventSource.System, resourceId, None, None,
      None, None, None, occurredAt, occurredAt)

  private def oldest(fixture: TimelineFixture) = event(
    UUID.fromString("b0000000-0000-0000-0000-0000000000a1"), OrganizationId, Some(fixture.resourceId), At)
  private def middle(fixture: TimelineFixture) = event(
    UUID.fromString("b0000000-0000-0000-0000-0000000000a2"), OrganizationId, Some(fixture.resourceId),
    At.plusSeconds(60), HistoryEventType.IncidentResolved)
  // Two facts of the same instant: only the id orders them apart.
  private def newestA(fixture: TimelineFixture) = event(
    UUID.fromString("b0000000-0000-0000-0000-0000000000a3"), OrganizationId, Some(fixture.resourceId),
    At.plusSeconds(120), HistoryEventType.OperationRequested)
  private def newestB(fixture: TimelineFixture) = event(
    UUID.fromString("b0000000-0000-0000-0000-0000000000a4"), OrganizationId, Some(fixture.resourceId),
    At.plusSeconds(120), HistoryEventType.OperationSucceeded)
  private def otherResource(fixture: TimelineFixture) = event(
    UUID.fromString("b0000000-0000-0000-0000-0000000000a5"), OrganizationId,
    Some(fixture.secondResourceId), At.plusSeconds(30))
  private def foreign(fixture: TimelineFixture) = event(
    UUID.fromString("b0000000-0000-0000-0000-0000000000a6"), ForeignOrganizationId, None,
    At.plusSeconds(200))
}
