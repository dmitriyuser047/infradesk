package ru.bitec.app.ops
package persistence.postgres

import application.monitor.{EvaluateMonitorRules, MonitorTransition}
import application.notification.{
  NotificationDispatcher,
  NotificationEvent,
  NotificationRecordingMonitorRuleEvaluator,
  RecordNotificationDeliveries
}
import application.port.{NotificationDeliveryRepository, NotificationSendResult, NotificationSender}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.metric.{MetricCode, MetricObservation}
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleStatus}
import domain.notification.{
  NotificationChannelType,
  NotificationDelivery,
  NotificationDeliveryStatus,
  NotificationEventType
}
import domain.resource.{Resource, ResourceData}
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import infrastructure.runtime.SystemTimeProvider
import munit.FunSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.free.{connection => FC}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** The notification outbox against a real database: claiming, leases, fencing and the atomicity
  * of an incident change with the delivery that reports it.
  */
final class NotificationDeliveryIntegrationSpec extends FunSuite {

  test("stores a delivery once per incident event and reads it back") {
    withFixture { fixture =>
      val delivery = pending(fixture, DeliveryId)
      val duplicate = delivery.copy(id = SecondDeliveryId, attemptCount = 5)

      for {
        _ <- fixture.run(fixture.deliveries.saveAll(List(delivery)))
        _ <- fixture.run(fixture.deliveries.saveAll(List(duplicate)))
        stored <- fixture.run(fixture.deliveries.findById(OrganizationId, DeliveryId))
        second <- fixture.run(fixture.deliveries.findById(OrganizationId, SecondDeliveryId))
        count <- fixture.run(fixture.countDeliveries)
      } yield IO {
        assertEquals(stored, Some(delivery))
        // The unique event index makes recording the same transition again a no-op.
        assertEquals(second, None)
        assertEquals(count, 1)
      }
    }
  }

  test("two dispatchers claiming at once take disjoint deliveries instead of blocking") {
    withFixture { fixture =>
      val first = pending(fixture, DeliveryId, eventType = NotificationEventType.IncidentOpened)
      val second = pending(fixture, SecondDeliveryId, eventType = NotificationEventType.IncidentResolved)

      for {
        _ <- fixture.run(fixture.deliveries.saveAll(List(first, second)))
        slowClaim <- fixture.run(
          fixture.deliveries.claimPending(DispatcherA, 1, 60).flatTap(_ => FC.delay(Thread.sleep(1000)))
        ).start
        _ <- IO.sleep(300.milliseconds)
        otherClaim <- fixture.run(fixture.deliveries.claimPending(DispatcherB, 1, 60))
        firstClaim <- slowClaim.joinWithNever
      } yield IO {
        assertEquals(firstClaim.size, 1)
        assertEquals(otherClaim.size, 1)
        assertNotEquals(firstClaim.head.id, otherClaim.head.id)
        assertEquals(firstClaim.head.claimedBy, Some(DispatcherA))
        assertEquals(otherClaim.head.claimedBy, Some(DispatcherB))
      }
    }
  }

  test("a delivery is invisible while its lease holds and claimable once it expires") {
    withFixture { fixture =>
      for {
        _ <- fixture.run(fixture.deliveries.saveAll(List(pending(fixture, DeliveryId))))
        claimed <- fixture.run(fixture.deliveries.claimPending(DispatcherA, 10, 60))
        whileLeased <- fixture.run(fixture.deliveries.claimPending(DispatcherB, 10, 60))
        _ <- fixture.run(fixture.expireLease(DeliveryId))
        afterExpiry <- fixture.run(fixture.deliveries.claimPending(DispatcherB, 10, 60))
      } yield IO {
        // The claim query is deliberately tenant-wide, so a parallel suite may own rows of its
        // own; only this fixture's delivery is assertable here.
        assertEquals(claimed.map(_.id).filter(_ == DeliveryId), List(DeliveryId))
        assertEquals(whileLeased.map(_.id).filter(_ == DeliveryId), List.empty[java.util.UUID])
        assertEquals(afterExpiry.collect {
          case delivery if delivery.id == DeliveryId => (delivery.id, delivery.claimedBy)
        }, List((DeliveryId, Some(DispatcherB))))
      }
    }
  }

  test("only the dispatcher that holds the claim can finish a delivery") {
    withFixture { fixture =>
      for {
        _ <- fixture.run(fixture.deliveries.saveAll(List(pending(fixture, DeliveryId))))
        _ <- fixture.run(fixture.deliveries.claimPending(DispatcherA, 10, 60))
        _ <- fixture.run(fixture.expireLease(DeliveryId))
        _ <- fixture.run(fixture.deliveries.claimPending(DispatcherB, 10, 60))
        staleOwner <- fixture.run(fixture.deliveries.markSent(OrganizationId, DeliveryId, DispatcherA, SentAt))
        afterStale <- fixture.run(fixture.deliveries.findById(OrganizationId, DeliveryId))
        currentOwner <- fixture.run(fixture.deliveries.markSent(OrganizationId, DeliveryId, DispatcherB, SentAt))
        afterCurrent <- fixture.run(fixture.deliveries.findById(OrganizationId, DeliveryId))
      } yield IO {
        // The stale worker lost the race; its late completion must not touch the row.
        assertEquals(staleOwner, false)
        assertEquals(afterStale.map(_.status), Some(NotificationDeliveryStatus.Pending))
        assertEquals(afterStale.flatMap(_.claimedBy), Some(DispatcherB))
        assertEquals(currentOwner, true)
        assertEquals(afterCurrent.map(_.status), Some(NotificationDeliveryStatus.Sent))
        assertEquals(afterCurrent.flatMap(_.sentAt), Some(SentAt))
        assertEquals(afterCurrent.flatMap(_.claimedBy), None)
        assertEquals(afterCurrent.map(_.attemptCount), Some(1L))
      }
    }
  }

  test("a rescheduled delivery stays pending for a later attempt and a dead one stops") {
    withFixture { fixture =>
      val retried = pending(fixture, DeliveryId, eventType = NotificationEventType.IncidentOpened)
      val abandoned = pending(fixture, SecondDeliveryId, eventType = NotificationEventType.IncidentResolved)

      for {
        _ <- fixture.run(fixture.deliveries.saveAll(List(retried, abandoned)))
        _ <- fixture.run(fixture.deliveries.claimPending(DispatcherA, 10, 60))
        rescheduled <- fixture.run(fixture.deliveries.reschedule(OrganizationId, DeliveryId,
          DispatcherA, 1, SentAt.plusSeconds(30), "HTTP_500", SentAt))
        died <- fixture.run(fixture.deliveries.markDead(OrganizationId, SecondDeliveryId,
          DispatcherA, 3, "HTTP_404", SentAt))
        storedRetry <- fixture.run(fixture.deliveries.findById(OrganizationId, DeliveryId))
        storedDead <- fixture.run(fixture.deliveries.findById(OrganizationId, SecondDeliveryId))
      } yield IO {
        assertEquals(rescheduled, true)
        assertEquals(died, true)
        assertEquals(storedRetry.map(_.status), Some(NotificationDeliveryStatus.Pending))
        assertEquals(storedRetry.map(_.attemptCount), Some(1L))
        assertEquals(storedRetry.map(_.nextAttemptAt), Some(SentAt.plusSeconds(30)))
        assertEquals(storedRetry.flatMap(_.lastErrorCode), Some("HTTP_500"))
        assertEquals(storedRetry.flatMap(_.claimedBy), None)
        assertEquals(storedDead.map(_.status), Some(NotificationDeliveryStatus.Dead))
        assertEquals(storedDead.flatMap(_.lastErrorCode), Some("HTTP_404"))
        assertEquals(storedDead.flatMap(_.sentAt), None)
      }
    }
  }

  test("a dispatcher leases only the deliveries it is about to send") {
    withFixture { fixture =>
      val secondIncident = fixture.seedIncident.copy(id = SecondIncidentId)
      val deliveries = List(
        pending(fixture, deliveryId(1), NotificationEventType.IncidentOpened),
        pending(fixture, deliveryId(2), NotificationEventType.IncidentResolved),
        pending(fixture, deliveryId(3), NotificationEventType.IncidentOpened).copy(incidentId = SecondIncidentId),
        pending(fixture, deliveryId(4), NotificationEventType.IncidentResolved).copy(incidentId = SecondIncidentId)
      )
      // Slower than one poll of the observer, so an over-eager lease would be visible in the table.
      val sender = new SlowSender(150.milliseconds)
      val dispatcher = new NotificationDispatcher[IO, ConnectionIO](
        fixture.deliveries, sender, fixture.transactionRunner, new SystemTimeProvider,
        Slf4jLogger.getLoggerFromName[IO]("test.notification.dispatcher"),
        maxConcurrency = 1, DispatcherA, 30.seconds, maxAttempts = 3
      )

      for {
        _ <- fixture.run(fixture.incidents.saveAll(List(secondIncident)))
        _ <- fixture.run(fixture.deliveries.saveAll(deliveries))
        dispatching <- dispatcher.tick(deliveries.size).start
        // Sample the lease held by this dispatcher while the wave-by-wave tick is running.
        samples <- (IO.sleep(40.milliseconds) *> fixture.run(fixture.countLeased(DispatcherA)))
          .replicateA(12).timeoutTo(10.seconds, IO.pure(List.empty))
        _ <- dispatching.joinWithNever
        stored <- fixture.run(fixture.listDeliveries)
      } yield IO {
        // A batch claim would have leased all four rows at once while three of them waited.
        assertEquals(samples.maxOption.getOrElse(0), 1, samples.toString)
        assert(samples.exists(_ == 1), s"the dispatcher was never observed working: $samples")
        assertEquals(sender.count, 4)
        assertEquals(stored.map(_.status.code).toSet, Set("SENT"))
      }
    }
  }

  test("an evaluation commits the incident and its notification together") {
    withFixture { fixture =>
      val observation = MetricObservation(UUID.randomUUID(), OrganizationId, fixture.resourceId,
        MetricCode.CpuUsagePercent, BigDecimal(95), Now)

      for {
        _ <- fixture.run(fixture.metrics.insertAll(List(observation)))
        transitions <- fixture.run(fixture.recordingEvaluator.execute(OrganizationId, ConnectionId, Now))
        incidents <- fixture.run(fixture.incidents.findByOrganization(OrganizationId, None))
        deliveries <- fixture.run(fixture.listDeliveries)
      } yield IO {
        // The fixture seeds one resolved incident for the delivery foreign keys.
        val ruleIncidents = incidents.filter(incident =>
          incident.monitorRuleId == fixture.ruleId && incident.id != IncidentId)
        assertEquals(transitions.filter(_.monitorRuleId == fixture.ruleId).map(_.eventName),
          List("incident.opened"))
        assertEquals(ruleIncidents.map(_.status), List(IncidentStatus.Open))
        assertEquals(deliveries.map(delivery =>
          (delivery.eventType.code, delivery.reason.code, delivery.status.code, delivery.incidentId)),
          List(("INCIDENT_OPENED", "THRESHOLD", "PENDING", ruleIncidents.head.id)))
        assertEquals(deliveries.map(_.occurredAt), List(Now))
        assertEquals(deliveries.map(_.attemptCount), List(0L))
      }
    }
  }

  test("a failing outbox write rolls the incident back with it") {
    withFixture { fixture =>
      val observation = MetricObservation(UUID.randomUUID(), OrganizationId, fixture.resourceId,
        MetricCode.CpuUsagePercent, BigDecimal(95), Now)
      val failing = new NotificationRecordingMonitorRuleEvaluator[ConnectionIO](
        fixture.evaluator,
        new RecordNotificationDeliveries[ConnectionIO](new FailingDeliveryRepository,
          new ConnectionIOIdGenerator, new ConnectionIOTimeProvider, List(NotificationChannelType.Webhook))
      )

      for {
        _ <- fixture.run(fixture.metrics.insertAll(List(observation)))
        outcome <- fixture.run(failing.execute(OrganizationId, ConnectionId, Now)).attempt
        incidents <- fixture.run(fixture.incidents.findByOrganization(OrganizationId, None))
        states <- fixture.run(fixture.states.findByRuleId(OrganizationId, fixture.ruleId))
        deliveries <- fixture.run(fixture.listDeliveries)
      } yield IO {
        assert(outcome.isLeft)
        // Without durable delivery intent the incident must not exist either.
        assertEquals(incidents.count(incident =>
          incident.monitorRuleId == fixture.ruleId && incident.id != IncidentId), 0)
        assertEquals(states, None)
        assertEquals(deliveries, List.empty)
      }
    }
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private def withFixture(body: OutboxFixture => IO[IO[Unit]]): Unit = {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val fixture = new OutboxFixture(new DoobieTransactionRunner(xa))
      (fixture.setUp *> body(fixture).flatten).guarantee(fixture.cleanUp)
    }.unsafeRunSync()
  }

  private final class OutboxFixture(runner: DoobieTransactionRunner) {
    val resourceId: UUID = UUID.randomUUID()
    val ruleId: UUID = UUID.randomUUID()
    val externalRefId: UUID = UUID.randomUUID()
    private val code = s"notification-${UUID.randomUUID().toString.take(8)}"

    val deliveries: NotificationDeliveryRepository[ConnectionIO] = new PostgresNotificationDeliveryRepository
    val incidents = new PostgresIncidentRepository
    val states = new PostgresMonitorRuleStateRepository
    val metrics = new PostgresMetricObservationRepository
    private val rules = new PostgresMonitorRuleRepository
    private val resources = ProductionResourceCodec.resourceRepository

    val evaluator = new EvaluateMonitorRules[ConnectionIO](
      new PostgresMonitorEvaluationQuery, states, incidents, new ConnectionIOIdGenerator
    )

    val recordingEvaluator = new NotificationRecordingMonitorRuleEvaluator[ConnectionIO](
      evaluator,
      new RecordNotificationDeliveries[ConnectionIO](deliveries, new ConnectionIOIdGenerator,
        new ConnectionIOTimeProvider, List(NotificationChannelType.Webhook))
    )

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    val transactionRunner: DoobieTransactionRunner = runner

    def setUp: IO[Unit] = {
      val resource = Resource(resourceId, OrganizationId, EnvironmentId, NodeResourceTypeId, None,
        code, code, isActive = true, Now, Now, "NODE", ResourceData.empty)
      val rule = MonitorRule(ruleId, OrganizationId, resourceId, MetricCode.CpuUsagePercent,
        MonitorOperator.GreaterThan, BigDecimal(90), 0, 900, enabled = true, Now, Now)

      run(sql"""
          insert into connection (id, organization_id, scope_type, connector_type, code, name)
          values ($ConnectionId, $OrganizationId, 'ORGANIZATION', 'SSH', ${ConnectionId.toString},
            'Integration fixture')
          on conflict do nothing
      """.update.run.void) *>
        run(resources.save(resource)) *>
        run(rules.save(rule)) *>
        run(sql"""
          insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id)
          values ($externalRefId, $OrganizationId, $ConnectionId, 'NODE', $code, $resourceId)
          on conflict do nothing
        """.update.run.void) *>
        run(incidents.saveAll(List(seedIncident)))
    }

    /** An incident the standalone delivery rows can point at. */
    def seedIncident: Incident =
      Incident(IncidentId, OrganizationId, ruleId, resourceId, IncidentStatus.Resolved,
        IncidentReason.ThresholdViolation, Now, Now, Some(Now), Now, Now)

    def countDeliveries: ConnectionIO[Int] =
      sql"select count(*) from notification_delivery where organization_id = $OrganizationId and monitor_rule_id = $ruleId"
        .query[Int].unique

    def listDeliveries: ConnectionIO[List[NotificationDelivery]] =
      sql"select id from notification_delivery where organization_id = $OrganizationId and monitor_rule_id = $ruleId order by created_at, id"
        .query[UUID].to[List]
        .flatMap(_.traverse(id => deliveries.findById(OrganizationId, id)))
        .map(_.flatten)

    /** How many deliveries this dispatcher currently holds under a live lease. */
    def countLeased(claimedBy: UUID): ConnectionIO[Int] =
      sql"""
        select count(*) from notification_delivery
        where organization_id = $OrganizationId and monitor_rule_id = $ruleId
          and status = 'PENDING' and claimed_by = $claimedBy
          and claimed_until > current_timestamp
      """.query[Int].unique

    def expireLease(deliveryId: UUID): ConnectionIO[Unit] =
      sql"""
        update notification_delivery
        set claimed_until = current_timestamp - interval '1 second'
        where organization_id = $OrganizationId and id = $deliveryId
      """.update.run.void

    def cleanUp: IO[Unit] = run(for {
      _ <- sql"delete from notification_delivery where organization_id = $OrganizationId and monitor_rule_id = $ruleId".update.run
      _ <- sql"delete from metric_observation where organization_id = $OrganizationId and resource_id = $resourceId".update.run
      _ <- sql"delete from incident where organization_id = $OrganizationId and monitor_rule_id = $ruleId".update.run
      _ <- sql"delete from monitor_rule_state where organization_id = $OrganizationId and monitor_rule_id = $ruleId".update.run
      _ <- sql"delete from monitor_rule where organization_id = $OrganizationId and resource_id = $resourceId".update.run
      _ <- sql"delete from external_ref where organization_id = $OrganizationId and resource_id = $resourceId".update.run
      _ <- sql"delete from connection where organization_id = $OrganizationId and id = $ConnectionId".update.run
      _ <- sql"delete from resource where organization_id = $OrganizationId and id = $resourceId".update.run
    } yield ()).attempt.void
  }

  /** A sender that takes long enough for the observer to see how many rows are leased. */
  private final class SlowSender(delay: FiniteDuration) extends NotificationSender[IO] {
    private val sent = new java.util.concurrent.atomic.AtomicInteger(0)
    def count: Int = sent.get()
    override def send(event: NotificationEvent): IO[NotificationSendResult] =
      IO.sleep(delay) *> IO(sent.incrementAndGet()) *> IO.pure(NotificationSendResult.Sent)
  }

  private final class FailingDeliveryRepository extends NotificationDeliveryRepository[ConnectionIO] {
    override def saveAll(deliveries: List[NotificationDelivery]): ConnectionIO[Unit] =
      FC.raiseError(new IllegalStateException("outbox unavailable"))
    override def findById(organizationId: UUID, id: UUID): ConnectionIO[Option[NotificationDelivery]] =
      FC.pure(None)
    override def claimPending(claimedBy: UUID, limit: Int, leaseSeconds: Long): ConnectionIO[List[NotificationDelivery]] =
      FC.pure(List.empty)
    override def markSent(organizationId: UUID, id: UUID, claimedBy: UUID, sentAt: Instant): ConnectionIO[Boolean] =
      FC.pure(false)
    override def reschedule(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      nextAttemptAt: Instant, errorCode: String, updatedAt: Instant): ConnectionIO[Boolean] = FC.pure(false)
    override def markDead(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      errorCode: String, updatedAt: Instant): ConnectionIO[Boolean] = FC.pure(false)
  }

  private def pending(
    fixture: OutboxFixture,
    id: UUID,
    eventType: NotificationEventType = NotificationEventType.IncidentOpened
  ): NotificationDelivery =
    NotificationDelivery(id, OrganizationId, IncidentId, fixture.resourceId, fixture.ruleId,
      eventType, IncidentReason.ThresholdViolation, NotificationChannelType.Webhook, Now,
      NotificationDeliveryStatus.Pending, 0, Now, None, None, None, None, Now, Now)

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val NodeResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  // Each PostgreSQL suite evaluates every rule reachable through the connection it names, so a
  // shared fixture connection would let suites running in parallel evaluate each other's rules.
  // This one belongs to this run alone.
  private val ConnectionId: UUID = UUID.randomUUID()
  private val IncidentId = UUID.fromString("a0000000-0000-0000-0000-0000000000ff")
  private val DeliveryId = UUID.fromString("c0000000-0000-0000-0000-000000000001")
  private val SecondDeliveryId = UUID.fromString("c0000000-0000-0000-0000-000000000002")
  private val SecondIncidentId = UUID.fromString("a0000000-0000-0000-0000-0000000000fe")

  private def deliveryId(index: Int): UUID = UUID.fromString(f"c0000000-0000-0000-0000-$index%012d")
  private val DispatcherA = UUID.fromString("d0000000-0000-0000-0000-00000000000a")
  private val DispatcherB = UUID.fromString("d0000000-0000-0000-0000-00000000000b")
  // Claims compare against the database clock, so the fixture has to be due in real time.
  private val Now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusSeconds(300)
  private val SentAt = Now.plusSeconds(5)
}
