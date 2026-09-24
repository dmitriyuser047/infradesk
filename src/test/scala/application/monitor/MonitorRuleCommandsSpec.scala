package ru.bitec.app.ops
package application.monitor

import application.notification.RecordNotificationDeliveries
import application.port.{IdGenerator, IncidentRepository, MonitorRuleRepository, MonitorRuleStateRepository, NotificationDeliveryRepository, ResourceRepository, TimeProvider}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.metric.MetricCode
import domain.monitor.{InvalidMonitorRule, MonitorOperator, MonitorRule, MonitorRuleState, MonitorRuleStatus}
import domain.notification.{NotificationChannel, NotificationDelivery}
import domain.resource.{Resource, ResourceData}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class MonitorRuleCommandsSpec extends FunSuite {

  // -------------------------------------------------------------------------------------------
  // Creating a rule only for a resource type monitoring supports
  // -------------------------------------------------------------------------------------------

  test("creates a rule for a node") {
    val fixture = new CommandFixture()

    val created = fixture.create.execute(support.AuthorizationFixtures.actor(OrganizationId), NodeResourceId, command()).unsafeRunSync()

    assertEquals(created.map(_.resourceId), Some(NodeResourceId))
    assertEquals(fixture.rules.saved.map(_.id), List(GeneratedRuleId))
  }

  test("refuses a rule for a resource type monitoring does not support") {
    val fixture = new CommandFixture()

    val failure = intercept[InvalidMonitorRule](
      fixture.create.execute(support.AuthorizationFixtures.actor(OrganizationId), ContainerResourceId, command()).unsafeRunSync()
    )

    assert(failure.getMessage.contains("CONTAINER"))
    assertEquals(fixture.rules.saved, List.empty)
  }

  test("reports an absent resource as not found instead of invalid") {
    val fixture = new CommandFixture()

    assertEquals(fixture.create.execute(support.AuthorizationFixtures.actor(OrganizationId), UUID.randomUUID(), command()).unsafeRunSync(), None)
    assertEquals(fixture.rules.saved, List.empty)
  }

  // -------------------------------------------------------------------------------------------
  // Updating a rule invalidates the evaluation state it no longer matches
  // -------------------------------------------------------------------------------------------

  test("disabling a firing rule resolves its incident and clears its state") {
    val fixture = new CommandFixture(
      initialRules = List(enabledRule),
      initialStates = List(state(MonitorRuleStatus.Firing, Some(Earlier))),
      initialIncidents = List(openIncident())
    )

    val updated = fixture.update.execute(support.AuthorizationFixtures.actor(OrganizationId), RuleId, command(enabled = false)).unsafeRunSync()

    assertEquals(updated.map(_.enabled), Some(false))
    assertEquals(fixture.states.deleted, List(OrganizationId -> RuleId))
    assertEquals(fixture.incidents.saved.map(incident => (incident.status, incident.resolvedAt)),
      List((IncidentStatus.Resolved, Some(Now))))
    // Closing an incident by hand is reported through the same outbox as an evaluation would use.
    assertEquals(fixture.notificationDeliveries.saved.map(delivery =>
      (delivery.eventType.code, delivery.reason.code, delivery.incidentId, delivery.status.code)),
      List(("INCIDENT_RESOLVED", "THRESHOLD", IncidentId, "PENDING")))
  }

  test("disabling a rule in NO_DATA resolves its no-data incident and clears its state") {
    val fixture = new CommandFixture(
      initialRules = List(enabledRule),
      initialStates = List(state(MonitorRuleStatus.NoData, None)),
      initialIncidents = List(openIncident(IncidentReason.NoData))
    )

    fixture.update.execute(support.AuthorizationFixtures.actor(OrganizationId), RuleId, command(enabled = false)).unsafeRunSync()

    assertEquals(fixture.states.deleted, List(OrganizationId -> RuleId))
    assertEquals(fixture.incidents.saved.map(incident => (incident.reason, incident.status)),
      List((IncidentReason.NoData, IncidentStatus.Resolved)))
  }

  test("disabling a pending rule clears its state and touches no incident") {
    val fixture = new CommandFixture(
      initialRules = List(enabledRule),
      initialStates = List(state(MonitorRuleStatus.Pending, Some(Earlier)))
    )

    fixture.update.execute(support.AuthorizationFixtures.actor(OrganizationId), RuleId, command(enabled = false)).unsafeRunSync()

    assertEquals(fixture.states.deleted, List(OrganizationId -> RuleId))
    assertEquals(fixture.incidents.saved, List.empty)
  }

  test("every evaluated field invalidates the state when it changes") {
    val invalidating = List(
      command(metricCode = MetricCode.MemoryUsagePercent),
      command(operator = MonitorOperator.GreaterThanOrEqual),
      command(threshold = BigDecimal(95)),
      command(forSeconds = 600),
      command(noDataSeconds = 1200),
      command(enabled = false)
    )

    invalidating.foreach { value =>
      val fixture = new CommandFixture(
        initialRules = List(enabledRule),
        initialStates = List(state(MonitorRuleStatus.Firing, Some(Earlier))),
        initialIncidents = List(openIncident())
      )

      fixture.update.execute(support.AuthorizationFixtures.actor(OrganizationId), RuleId, value).unsafeRunSync()

      assertEquals(fixture.states.deleted, List(OrganizationId -> RuleId), s"state kept for $value")
      assertEquals(fixture.incidents.saved.map(_.status), List(IncidentStatus.Resolved), s"incident kept for $value")
    }
  }

  test("an update that changes nothing evaluated keeps the state and the incident") {
    val fixture = new CommandFixture(
      initialRules = List(enabledRule),
      initialStates = List(state(MonitorRuleStatus.Firing, Some(Earlier))),
      initialIncidents = List(openIncident())
    )

    val updated = fixture.update.execute(support.AuthorizationFixtures.actor(OrganizationId), RuleId, command(threshold = BigDecimal("90.00")))
      .unsafeRunSync()

    assertEquals(updated.map(_.updatedAt), Some(Now))
    assertEquals(fixture.states.deleted, List.empty)
    assertEquals(fixture.incidents.saved, List.empty)
    assertEquals(fixture.rules.saved.map(_.id), List(RuleId))
  }

  test("re-enabling a rule starts from a clean state") {
    val fixture = new CommandFixture(
      initialRules = List(enabledRule.copy(enabled = false)),
      initialStates = List(state(MonitorRuleStatus.Pending, Some(Earlier)))
    )

    val updated = fixture.update.execute(support.AuthorizationFixtures.actor(OrganizationId), RuleId, command(enabled = true)).unsafeRunSync()

    assertEquals(updated.map(_.enabled), Some(true))
    assertEquals(fixture.states.deleted, List(OrganizationId -> RuleId))
  }

  test("an unknown rule is reported as not found without touching anything") {
    val fixture = new CommandFixture(initialRules = List(enabledRule))

    assertEquals(fixture.update.execute(support.AuthorizationFixtures.actor(OrganizationId), UUID.randomUUID(), command()).unsafeRunSync(), None)
    assertEquals(fixture.rules.saved, List.empty)
    assertEquals(fixture.states.deleted, List.empty)
  }

  test("an invalid command is rejected before the rule is written") {
    val fixture = new CommandFixture(initialRules = List(enabledRule))

    intercept[InvalidMonitorRule](
      fixture.update.execute(support.AuthorizationFixtures.actor(OrganizationId), RuleId, command(noDataSeconds = -1)).unsafeRunSync()
    )

    assertEquals(fixture.rules.saved, List.empty)
    assertEquals(fixture.states.deleted, List.empty)
  }

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  private def command(
    metricCode: MetricCode = MetricCode.CpuUsagePercent,
    operator: MonitorOperator = MonitorOperator.GreaterThan,
    threshold: BigDecimal = BigDecimal(90),
    forSeconds: Long = 300,
    noDataSeconds: Long = 900,
    enabled: Boolean = true
  ): MonitorRuleCommand =
    MonitorRuleCommand(metricCode, operator, threshold, forSeconds, noDataSeconds, enabled)

  private def state(status: MonitorRuleStatus, pendingSince: Option[Instant]): MonitorRuleState =
    MonitorRuleState(OrganizationId, RuleId, status, pendingSince, Earlier)

  private def openIncident(reason: IncidentReason = IncidentReason.ThresholdViolation): Incident =
    Incident(IncidentId, OrganizationId, RuleId, NodeResourceId, IncidentStatus.Open, reason,
      Earlier, Earlier, None, Earlier, Earlier)

  private final class CommandFixture(
    initialRules: List[MonitorRule] = List.empty,
    initialStates: List[MonitorRuleState] = List.empty,
    initialIncidents: List[Incident] = List.empty
  ) {
    val resources = new FakeResourceRepository
    val rules = new FakeMonitorRuleRepository(initialRules)
    val states = new FakeMonitorRuleStateRepository(initialStates)
    val incidents = new FakeIncidentRepository(initialIncidents)

    val (auditEvents, auditRecorder) = support.TestAuditRecorder.recording

    val create: CreateMonitorRule[IO] =
      CreateMonitorRule[IO](resources, rules, new FixedIdGenerator, new FixedTimeProvider,
        auditRecorder)

    val notificationDeliveries = new FakeNotificationDeliveryRepository

    val update: UpdateMonitorRule[IO] = UpdateMonitorRule[IO](
      rules,
      states,
      incidents,
      new RecordNotificationDeliveries[IO](notificationDeliveries, new FixedIdGenerator,
        new FixedTimeProvider, List(NotificationChannel.Webhook)),
      auditRecorder,
      new FixedTimeProvider
    )
  }

  private final class FakeResourceRepository extends ResourceRepository[IO] {
    override def findById(organizationId: UUID, id: UUID): IO[Option[Resource]] =
      IO.pure(Map(NodeResourceId -> nodeResource, ContainerResourceId -> containerResource)
        .get(id).filter(_.organizationId == organizationId))
    override def findActiveByEnvironment(organizationId: UUID, environmentId: UUID): IO[List[Resource]] =
      IO.pure(List.empty)
    override def save(resource: Resource): IO[Unit] = IO.unit
    override def deactivateIfExclusiveToConnection(
      organizationId: UUID, id: UUID, connectionId: UUID, now: Instant
    ): IO[Unit] = IO.unit
  }

  private final class FakeMonitorRuleRepository(initial: List[MonitorRule]) extends MonitorRuleRepository[IO] {
    var saved: List[MonitorRule] = List.empty
    override def findById(organizationId: UUID, id: UUID): IO[Option[MonitorRule]] =
      IO.pure(initial.find(rule => rule.organizationId == organizationId && rule.id == id))
    override def findEnabledByResource(organizationId: UUID, resourceId: UUID): IO[List[MonitorRule]] =
      IO.pure(initial.filter(rule => rule.resourceId == resourceId && rule.enabled))
    override def findByResource(organizationId: UUID, resourceId: UUID): IO[List[MonitorRule]] =
      IO.pure(initial.filter(_.resourceId == resourceId))
    override def save(rule: MonitorRule): IO[Unit] = IO { saved = saved :+ rule }
  }

  private final class FakeMonitorRuleStateRepository(initial: List[MonitorRuleState])
    extends MonitorRuleStateRepository[IO] {
    var deleted: List[(UUID, UUID)] = List.empty
    var saved: List[MonitorRuleState] = List.empty
    override def findByRuleId(organizationId: UUID, monitorRuleId: UUID): IO[Option[MonitorRuleState]] =
      IO.pure(initial.find(_.monitorRuleId == monitorRuleId))
    override def findByResource(organizationId: UUID, resourceId: UUID): IO[List[MonitorRuleState]] =
      IO.pure(initial)
    override def deleteByRuleId(organizationId: UUID, monitorRuleId: UUID): IO[Unit] =
      IO { deleted = deleted :+ ((organizationId, monitorRuleId)) }
    override def save(state: MonitorRuleState): IO[Unit] = saveAll(List(state))
    override def saveAll(values: List[MonitorRuleState]): IO[Unit] = IO { saved = saved ++ values }
  }

  private final class FakeIncidentRepository(initial: List[Incident]) extends IncidentRepository[IO] {
    var saved: List[Incident] = List.empty
    override def findOpenByRule(organizationId: UUID, monitorRuleId: UUID): IO[Option[Incident]] =
      IO.pure(initial.find(incident =>
        incident.organizationId == organizationId &&
          incident.monitorRuleId == monitorRuleId &&
          incident.status == IncidentStatus.Open
      ))
    override def findById(organizationId: UUID, incidentId: UUID): IO[Option[Incident]] = IO.pure(None)
    override def findByOrganization(organizationId: UUID, status: Option[IncidentStatus]): IO[List[Incident]] =
      IO.pure(List.empty)
    override def save(incident: Incident): IO[Unit] = saveAll(List(incident))
    override def saveAll(incidents: List[Incident]): IO[Unit] = IO { saved = saved ++ incidents }
  }

  private final class FakeNotificationDeliveryRepository extends NotificationDeliveryRepository[IO] {
    var saved: List[NotificationDelivery] = List.empty
    override def saveAll(deliveries: List[NotificationDelivery]): IO[Unit] =
      IO { saved = saved ++ deliveries }
    override def findById(organizationId: UUID, id: UUID): IO[Option[NotificationDelivery]] = IO.pure(None)
    override def claimPending(claimedBy: UUID, limit: Int, leaseSeconds: Long): IO[List[NotificationDelivery]] =
      IO.pure(List.empty)
    override def markSent(organizationId: UUID, id: UUID, claimedBy: UUID, sentAt: Instant): IO[Boolean] =
      IO.pure(true)
    override def reschedule(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      nextAttemptAt: Instant, errorCode: String, updatedAt: Instant): IO[Boolean] = IO.pure(true)
    override def markDead(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      errorCode: String, updatedAt: Instant): IO[Boolean] = IO.pure(true)
  }

  private final class FixedIdGenerator extends IdGenerator[IO] {
    override def nextId: IO[UUID] = IO.pure(GeneratedRuleId)
  }

  private final class FixedTimeProvider extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(Now)
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val NodeResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val ContainerResourceId = UUID.fromString("70000000-0000-0000-0000-000000000002")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val GeneratedRuleId = UUID.fromString("90000000-0000-0000-0000-000000000009")
  private val IncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001")
  private val Earlier = Instant.parse("2026-09-22T10:00:00Z")
  private val Now = Instant.parse("2026-09-22T12:00:00Z")

  private val nodeResource = Resource(NodeResourceId, OrganizationId, EnvironmentId,
    UUID.fromString("10000000-0000-0000-0000-000000000001"), None, "node", "node",
    isActive = true, Earlier, Earlier, "NODE", ResourceData.empty)

  private val containerResource = nodeResource.copy(id = ContainerResourceId, code = "container",
    name = "container", resourceTypeCode = "CONTAINER")

  private val enabledRule = MonitorRule(RuleId, OrganizationId, NodeResourceId,
    MetricCode.CpuUsagePercent, MonitorOperator.GreaterThan, BigDecimal(90), 300, 900,
    enabled = true, Earlier, Earlier)
}
