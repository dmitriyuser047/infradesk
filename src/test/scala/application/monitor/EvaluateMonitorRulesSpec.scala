package ru.bitec.app.ops
package application.monitor

import application.port.{IdGenerator, IncidentRepository, MonitorEvaluationQuery, MonitorRuleStateRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.metric.{MetricCode, MetricObservation}
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleState, MonitorRuleStatus}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class EvaluateMonitorRulesSpec extends FunSuite {

  // -------------------------------------------------------------------------------------------
  // Threshold state machine
  // -------------------------------------------------------------------------------------------

  test("preserves OK, PENDING, FIRING and recovery semantics") {
    val normal = run(input(cpuRule(), None, Some(observation(40)), None))
    val pending = run(input(cpuRule(), Some(okState), Some(observation(95)), None))
    val pendingSince = ObservedAt.minusSeconds(300)
    val firing = run(input(cpuRule(), Some(state(MonitorRuleStatus.Pending, Some(pendingSince))),
      Some(observation(95)), None))
    val continuing = run(input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(observation(95)), Some(openIncident())))
    val recovered = run(input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(observation(40)), Some(openIncident())))
    val pendingRecovered = run(input(cpuRule(), Some(state(MonitorRuleStatus.Pending, Some(pendingSince))),
      Some(observation(40)), None))

    assertEquals(normal.states.head.status, MonitorRuleStatus.Ok)
    assertEquals(pending.states.head.status, MonitorRuleStatus.Pending)
    assertEquals(pending.states.head.pendingSince, Some(ObservedAt))
    assertEquals(firing.states.head.status, MonitorRuleStatus.Firing)
    assertEquals(firing.incidents.head.status, IncidentStatus.Open)
    assertEquals(firing.incidents.head.reason, IncidentReason.ThresholdViolation)
    assertEquals(continuing.states.head.status, MonitorRuleStatus.Firing)
    assertEquals(continuing.incidents, List.empty)
    assertEquals(recovered.states.head.status, MonitorRuleStatus.Ok)
    assertEquals(recovered.incidents.head.status, IncidentStatus.Resolved)
    assertEquals(pendingRecovered.states.head.status, MonitorRuleStatus.Ok)
    assertEquals(pendingRecovered.incidents, List.empty)
    assertEquals(recovered.transitions.map(transition =>
      (transition.eventName, transition.incidentId, transition.reason)),
      List(("incident.resolved", IncidentId, IncidentReason.ThresholdViolation)))
  }

  test("opens immediately for a zero-duration violation and returns the transition") {
    val result = run(input(cpuRule(forSeconds = 0), None, Some(observation(95)), None))

    assertEquals(result.states.head.status, MonitorRuleStatus.Firing)
    assertEquals(result.incidents.head.startedAt, ObservedAt)
    assertEquals(result.incidents.head.reason, IncidentReason.ThresholdViolation)
    assertEquals(result.transitions.map(transition =>
      (transition.eventName, transition.incidentId, transition.reason)),
      List(("incident.opened", NewIncidentId, IncidentReason.ThresholdViolation)))
  }

  test("keeps the original pendingSince until the configured duration is reached") {
    val pendingSince = ObservedAt.minusSeconds(299)
    val result = run(input(cpuRule(), Some(state(MonitorRuleStatus.Pending, Some(pendingSince))),
      Some(observation(95)), None))

    assertEquals(result.states.head.status, MonitorRuleStatus.Pending)
    assertEquals(result.states.head.pendingSince, Some(pendingSince))
    assertEquals(result.incidents, List.empty)
  }

  // -------------------------------------------------------------------------------------------
  // Freshness
  // -------------------------------------------------------------------------------------------

  test("treats an observation as stale exactly at the configured no-data timeout") {
    val stale = run(input(cpuRule(), Some(okState), Some(observation(40, observedAt = EvaluatedAt.minusSeconds(600))), None))
    val fresh = run(input(cpuRule(), Some(okState), Some(observation(40, observedAt = EvaluatedAt.minusSeconds(599))), None))

    assertEquals(stale.states.head.status, MonitorRuleStatus.NoData)
    assertEquals(fresh.states.head.status, MonitorRuleStatus.Ok)
  }

  test("waits for its own no-data timeout before reporting a rule that never received data") {
    val young = run(input(cpuRule(createdAt = EvaluatedAt.minusSeconds(599)), None, None, None))
    val elapsed = run(input(cpuRule(createdAt = EvaluatedAt.minusSeconds(600)), None, None, None))

    assertEquals(young.states, List.empty)
    assertEquals(young.incidents, List.empty)
    assertEquals(young.stateBatchCalls, 0)
    assertEquals(elapsed.states.head.status, MonitorRuleStatus.NoData)
    assertEquals(elapsed.incidents.head.reason, IncidentReason.NoData)
    assertEquals(elapsed.incidents.head.startedAt, EvaluatedAt.minusSeconds(600))
  }

  test("a zero no-data timeout keeps evaluating old observations and never reports NO_DATA") {
    val ancient = run(input(cpuRule(noDataSeconds = 0), Some(okState),
      Some(observation(95, observedAt = EvaluatedAt.minusSeconds(100000))), None))
    val missing = run(input(cpuRule(noDataSeconds = 0), Some(okState), None, None))

    assertEquals(ancient.states.head.status, MonitorRuleStatus.Pending)
    assertEquals(missing.states, List.empty)
  }

  // -------------------------------------------------------------------------------------------
  // NO_DATA state machine
  // -------------------------------------------------------------------------------------------

  test("every live status moves to NO_DATA and opens exactly one no-data incident") {
    val fromOk = run(input(cpuRule(), Some(okState), Some(staleObservation), None))
    val fromPending = run(input(cpuRule(), Some(state(MonitorRuleStatus.Pending, Some(ObservedAt))),
      Some(staleObservation), None))
    val fromFiring = run(input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(staleObservation), Some(openIncident())))
    val staying = run(input(cpuRule(), Some(state(MonitorRuleStatus.NoData, None)),
      Some(staleObservation), Some(openIncident(IncidentReason.NoData))))

    assertEquals(fromOk.states.head.status, MonitorRuleStatus.NoData)
    assertEquals(fromOk.states.head.pendingSince, None)
    assertEquals(fromOk.incidents.map(_.reason), List(IncidentReason.NoData))
    assertEquals(fromPending.states.head.status, MonitorRuleStatus.NoData)
    assertEquals(fromPending.incidents.map(_.reason), List(IncidentReason.NoData))
    assertEquals(fromFiring.states.head.status, MonitorRuleStatus.NoData)
    assertEquals(fromFiring.incidents.map(incident => (incident.status, incident.reason)), List(
      (IncidentStatus.Resolved, IncidentReason.ThresholdViolation),
      (IncidentStatus.Open, IncidentReason.NoData)
    ))
    assertEquals(fromFiring.transitions.map(_.eventName), List("incident.resolved", "incident.opened"))
    assertEquals(staying.states.head.status, MonitorRuleStatus.NoData)
    assertEquals(staying.incidents, List.empty)
  }

  test("resolves the threshold incident before opening the no-data incident that replaces it") {
    val result = run(input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(staleObservation), Some(openIncident())))

    assertEquals(result.incidentBatches.map(_.map(_.status)), List(
      List(IncidentStatus.Resolved),
      List(IncidentStatus.Open)
    ))
  }

  test("recovering data resolves the no-data incident and restarts the threshold evaluation") {
    val healthy = run(input(cpuRule(), Some(state(MonitorRuleStatus.NoData, None)),
      Some(observation(40)), Some(openIncident(IncidentReason.NoData))))
    val violating = run(input(cpuRule(), Some(state(MonitorRuleStatus.NoData, None)),
      Some(observation(95)), Some(openIncident(IncidentReason.NoData))))
    val immediate = run(input(cpuRule(forSeconds = 0), Some(state(MonitorRuleStatus.NoData, None)),
      Some(observation(95)), Some(openIncident(IncidentReason.NoData))))

    assertEquals(healthy.states.head.status, MonitorRuleStatus.Ok)
    assertEquals(healthy.incidents.map(incident => (incident.status, incident.reason)),
      List((IncidentStatus.Resolved, IncidentReason.NoData)))

    assertEquals(violating.states.head.status, MonitorRuleStatus.Pending)
    assertEquals(violating.states.head.pendingSince, Some(ObservedAt))
    assertEquals(violating.incidents.map(incident => (incident.status, incident.reason)),
      List((IncidentStatus.Resolved, IncidentReason.NoData)))

    assertEquals(immediate.states.head.status, MonitorRuleStatus.Firing)
    assertEquals(immediate.incidents.map(incident => (incident.status, incident.reason)), List(
      (IncidentStatus.Resolved, IncidentReason.NoData),
      (IncidentStatus.Open, IncidentReason.ThresholdViolation)
    ))
    assertEquals(immediate.transitions.map(transition => (transition.eventName, transition.reason)), List(
      ("incident.resolved", IncidentReason.NoData),
      ("incident.opened", IncidentReason.ThresholdViolation)
    ))
  }

  test("a violation observed after a data gap starts its own duration window") {
    val staleWindow = ObservedAt.minusSeconds(3600)
    val result = run(input(cpuRule(), Some(state(MonitorRuleStatus.NoData, None)),
      Some(observation(95)), Some(openIncident(IncidentReason.NoData))))

    assertEquals(result.states.head.pendingSince, Some(ObservedAt))
    assertNotEquals(result.states.head.pendingSince, Some(staleWindow))
  }

  // -------------------------------------------------------------------------------------------
  // Invariants
  // -------------------------------------------------------------------------------------------

  test("rejects inconsistent state and incident projections before any write") {
    val cases = List(
      input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))), Some(observation(95)), None),
      input(cpuRule(), Some(state(MonitorRuleStatus.Pending, Some(ObservedAt.minusSeconds(300)))),
        Some(observation(95)), Some(openIncident())),
      input(cpuRule(), Some(okState), Some(observation(40)), Some(openIncident())),
      input(cpuRule(), Some(state(MonitorRuleStatus.NoData, None)), Some(staleObservation), None),
      input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))), Some(observation(95)),
        Some(openIncident(IncidentReason.NoData)))
    )

    cases.foreach { value =>
      val query = new RecordingQuery(List(value))
      val states = new RecordingStateRepository
      val incidents = new RecordingIncidentRepository
      val evaluator = new EvaluateMonitorRules[IO](query, states, incidents, new FixedIdGenerator)
      intercept[IllegalStateException](
        evaluator.execute(OrganizationId, ConnectionId, EvaluatedAt).unsafeRunSync()
      )
      assertEquals(states.batches, List.empty)
      assertEquals(incidents.batches, List.empty)
    }
  }

  // -------------------------------------------------------------------------------------------
  // Query and batching
  // -------------------------------------------------------------------------------------------

  test("loads 300 rules of one connection once and persists deterministic batches") {
    val inputs = (1 to 100).toList.flatMap { resourceIndex =>
      val resourceId = uuid(resourceIndex)
      (1 to 3).map { ruleIndex =>
        val ruleId = uuid(1000 + resourceIndex * 3 + ruleIndex)
        MonitorEvaluationInput(cpuRule(id = ruleId, resourceId = resourceId),
          Some(observation(40, resourceId)), None, None)
      }
    }
    val query = new RecordingQuery(inputs)
    val states = new RecordingStateRepository
    val incidents = new RecordingIncidentRepository
    val evaluator = new EvaluateMonitorRules[IO](query, states, incidents, new FixedIdGenerator)

    evaluator.execute(OrganizationId, ConnectionId, EvaluatedAt).unsafeRunSync()

    assertEquals(query.calls.size, 1)
    assertEquals(query.calls.head, (OrganizationId, ConnectionId, MonitoredResourceTypes.codes))
    assertEquals(states.batches.map(_.size), List(300))
    assertEquals(incidents.batches, List.empty)
  }

  // -------------------------------------------------------------------------------------------
  // Maintenance
  // -------------------------------------------------------------------------------------------

  test("an incident opened during maintenance is recorded but silenced, and so is its resolution") {
    val opened = run(input(cpuRule(forSeconds = 0), None, Some(observation(95)), None).copy(inMaintenance = true))
    assertEquals(opened.incidents.map(i => (i.status, i.notificationsSilenced)), List((IncidentStatus.Open, true)))
    assertEquals(opened.transitions.map(t => (t.eventName, t.notificationsSilenced)), List(("incident.opened", true)))

    // The window has ended by the time the incident resolves; the decision taken at opening holds.
    val silenced = openIncident().copy(notificationsSilenced = true)
    val resolved = run(input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(observation(40)), Some(silenced)))
    assertEquals(resolved.transitions.map(t => (t.eventName, t.notificationsSilenced)), List(("incident.resolved", true)))
  }

  test("an incident opened before maintenance keeps notifying when it resolves during it") {
    val resolved = run(input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(observation(40)), Some(openIncident())).copy(inMaintenance = true))
    assertEquals(resolved.transitions.map(t => (t.eventName, t.notificationsSilenced)), List(("incident.resolved", false)))
    val normal = run(input(cpuRule(forSeconds = 0), None, Some(observation(95)), None))
    assertEquals(normal.incidents.map(_.notificationsSilenced), List(false))
  }

  test("the evaluation asks for maintenance at its own evaluation time") {
    val query = new RecordingQuery(Nil)
    new EvaluateMonitorRules[IO](query, new RecordingStateRepository, new RecordingIncidentRepository, new FixedIdGenerator)
      .execute(OrganizationId, ConnectionId, EvaluatedAt).unsafeRunSync()
    assertEquals(query.instants, List(EvaluatedAt))
  }

  test("asks only for the resource types monitoring supports") {
    assertEquals(MonitoredResourceTypes.codes, List("NODE"))
  }

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  private def run(value: MonitorEvaluationInput): Result = {
    val query = new RecordingQuery(List(value))
    val states = new RecordingStateRepository
    val incidents = new RecordingIncidentRepository
    val transitions = new EvaluateMonitorRules[IO](query, states, incidents, new FixedIdGenerator)
      .execute(OrganizationId, ConnectionId, EvaluatedAt).unsafeRunSync()
    Result(states.batches.flatten, incidents.batches.flatten, incidents.batches, transitions,
      states.batches.size)
  }

  private def input(
    rule: MonitorRule,
    currentState: Option[MonitorRuleState],
    latest: Option[MetricObservation],
    incident: Option[Incident]
  ): MonitorEvaluationInput = MonitorEvaluationInput(rule, latest, currentState, incident)

  private def cpuRule(
    id: UUID = RuleId,
    resourceId: UUID = ResourceId,
    forSeconds: Long = 300,
    noDataSeconds: Long = 600,
    createdAt: Instant = ObservedAt
  ): MonitorRule = MonitorRule(id, OrganizationId, resourceId, MetricCode.CpuUsagePercent,
    MonitorOperator.GreaterThan, BigDecimal(90), forSeconds, noDataSeconds, enabled = true,
    createdAt, createdAt)

  private def state(status: MonitorRuleStatus, pendingSince: Option[Instant]): MonitorRuleState =
    MonitorRuleState(OrganizationId, RuleId, status, pendingSince, ObservedAt)

  private def observation(
    value: BigDecimal,
    resourceId: UUID = ResourceId,
    observedAt: Instant = ObservedAt
  ): MetricObservation =
    MetricObservation(UUID.randomUUID(), OrganizationId, resourceId, MetricCode.CpuUsagePercent, value, observedAt)

  private def openIncident(reason: IncidentReason = IncidentReason.ThresholdViolation): Incident =
    Incident(IncidentId, OrganizationId, RuleId, ResourceId, IncidentStatus.Open, reason,
      ObservedAt, ObservedAt, None, ObservedAt, ObservedAt)

  private final case class Result(
    states: List[MonitorRuleState],
    incidents: List[Incident],
    incidentBatches: List[List[Incident]],
    transitions: List[MonitorTransition],
    stateBatchCalls: Int
  )

  private final class RecordingQuery(values: List[MonitorEvaluationInput]) extends MonitorEvaluationQuery[IO] {
    var calls: List[(UUID, UUID, List[String])] = List.empty
    var instants: List[Instant] = List.empty
    override def findEnabledForConnection(
      organizationId: UUID,
      connectionId: UUID,
      resourceTypeCodes: List[String],
      at: java.time.Instant
    ): IO[List[MonitorEvaluationInput]] = IO {
      calls = calls :+ ((organizationId, connectionId, resourceTypeCodes))
      instants = instants :+ at
      values.filter(_.rule.organizationId == organizationId)
    }
  }

  private final class RecordingStateRepository extends MonitorRuleStateRepository[IO] {
    var batches: List[List[MonitorRuleState]] = List.empty
    override def findByRuleId(organizationId: UUID, monitorRuleId: UUID): IO[Option[MonitorRuleState]] = IO.pure(None)
    override def findByResource(organizationId: UUID, resourceId: UUID): IO[List[MonitorRuleState]] = IO.pure(List.empty)
    override def deleteByRuleId(organizationId: UUID, monitorRuleId: UUID): IO[Unit] = IO.unit
    override def save(value: MonitorRuleState): IO[Unit] = saveAll(List(value))
    override def saveAll(values: List[MonitorRuleState]): IO[Unit] = IO { batches = batches :+ values }
  }

  private final class RecordingIncidentRepository extends IncidentRepository[IO] {
    var batches: List[List[Incident]] = List.empty
    override def findOpenByRule(organizationId: UUID, monitorRuleId: UUID): IO[Option[Incident]] = IO.pure(None)
    override def findById(organizationId: UUID, incidentId: UUID): IO[Option[Incident]] = IO.pure(None)
    override def findByOrganization(organizationId: UUID, status: Option[IncidentStatus]): IO[List[Incident]] = IO.pure(List.empty)
    override def save(value: Incident): IO[Unit] = saveAll(List(value))
    override def saveAll(values: List[Incident]): IO[Unit] = IO { batches = batches :+ values }
  }

  private final class FixedIdGenerator extends IdGenerator[IO] {
    override def nextId: IO[UUID] = IO.pure(NewIncidentId)
  }

  private def uuid(value: Int): UUID = UUID.fromString(f"00000000-0000-0000-0000-$value%012d")

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val ConnectionId = UUID.fromString("60000000-0000-0000-0000-000000000003")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val IncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001")
  private val NewIncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000002")
  private val ObservedAt = Instant.parse("2026-09-22T10:00:00Z")
  private val EvaluatedAt = Instant.parse("2026-09-22T10:05:00Z")
  private val okState = state(MonitorRuleStatus.Ok, None)
  private val staleObservation = observation(95, observedAt = EvaluatedAt.minusSeconds(900))
}
