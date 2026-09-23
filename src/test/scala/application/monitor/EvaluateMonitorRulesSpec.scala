package ru.bitec.app.ops
package application.monitor

import application.port.{IdGenerator, IncidentRepository, MonitorEvaluationQuery, MonitorRuleStateRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.incident.{Incident, IncidentStatus}
import domain.metric.{MetricCode, MetricObservation}
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleState, MonitorRuleStatus}
import domain.resource.{Resource, ResourceData}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class EvaluateMonitorRulesSpec extends FunSuite {
  test("preserves OK, PENDING, FIRING and recovery semantics") {
    val normal = run(input(cpuRule(), None, Some(observation(40)), None))
    val pending = run(input(cpuRule(), Some(okState), Some(observation(95)), None))
    val pendingSince = ObservedAt.minusSeconds(300)
    val firing = run(input(cpuRule(), Some(state(MonitorRuleStatus.Pending, Some(pendingSince))),
      Some(observation(95)), None))
    val continuing = run(input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(observation(95)), Some(openIncident)))
    val recovered = run(input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(observation(40)), Some(openIncident)))

    assertEquals(normal.states.head.status, MonitorRuleStatus.Ok)
    assertEquals(pending.states.head.status, MonitorRuleStatus.Pending)
    assertEquals(pending.states.head.pendingSince, Some(ObservedAt))
    assertEquals(firing.states.head.status, MonitorRuleStatus.Firing)
    assertEquals(firing.incidents.head.status, IncidentStatus.Open)
    assertEquals(continuing.states.head.status, MonitorRuleStatus.Firing)
    assertEquals(continuing.incidents, List.empty)
    assertEquals(recovered.states.head.status, MonitorRuleStatus.Ok)
    assertEquals(recovered.incidents.head.status, IncidentStatus.Resolved)
    assertEquals(recovered.transitions, List(
      MonitorTransition.Resolved(OrganizationId, ResourceId, RuleId, IncidentId, EvaluatedAt)
    ))
  }

  test("opens immediately for a zero-duration violation and returns the transition") {
    val result = run(input(cpuRule(forSeconds = 0), None, Some(observation(95)), None))

    assertEquals(result.states.head.status, MonitorRuleStatus.Firing)
    assertEquals(result.incidents.head.startedAt, ObservedAt)
    assertEquals(result.transitions, List(
      MonitorTransition.Opened(OrganizationId, ResourceId, RuleId, NewIncidentId, EvaluatedAt)
    ))
  }

  test("keeps the original pendingSince until the configured duration is reached") {
    val pendingSince = ObservedAt.minusSeconds(299)
    val result = run(input(cpuRule(), Some(state(MonitorRuleStatus.Pending, Some(pendingSince))),
      Some(observation(95)), None))

    assertEquals(result.states.head.status, MonitorRuleStatus.Pending)
    assertEquals(result.states.head.pendingSince, Some(pendingSince))
    assertEquals(result.incidents, List.empty)
  }

  test("does not write state or incident when the projection has no current observation") {
    val result = run(input(cpuRule(), Some(okState), None, None))

    assertEquals(result.states, List.empty)
    assertEquals(result.incidents, List.empty)
    assertEquals(result.stateBatchCalls, 0)
    assertEquals(result.incidentBatchCalls, 0)
  }

  test("rejects inconsistent state and incident projections before any write") {
    val cases = List(
      input(cpuRule(), Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))), Some(observation(95)), None),
      input(cpuRule(), Some(state(MonitorRuleStatus.Pending, Some(ObservedAt.minusSeconds(300)))),
        Some(observation(95)), Some(openIncident)),
      input(cpuRule(), Some(okState), Some(observation(40)), Some(openIncident))
    )

    cases.foreach { value =>
      val query = new RecordingQuery(List(value))
      val states = new RecordingStateRepository
      val incidents = new RecordingIncidentRepository
      val evaluator = new EvaluateMonitorRules[IO](query, states, incidents, new FixedIdGenerator)
      intercept[IllegalStateException](evaluator.execute(List(nodeResource), EvaluatedAt).unsafeRunSync())
      assertEquals(states.batches, List.empty)
      assertEquals(incidents.batches, List.empty)
    }
  }

  test("ignores non-node resources without querying persistence") {
    val query = new RecordingQuery(List.empty)
    val evaluator = new EvaluateMonitorRules[IO](query, new RecordingStateRepository,
      new RecordingIncidentRepository, new FixedIdGenerator)

    evaluator.execute(List(containerResource), EvaluatedAt).unsafeRunSync()

    assertEquals(query.calls, List.empty)
  }

  test("loads 300 rules for 100 resources once and persists deterministic batches") {
    val resources = (1 to 100).toList.map(index => nodeResource.copy(id = uuid(index)))
    val inputs = resources.zipWithIndex.flatMap { case (resource, resourceIndex) =>
      (1 to 3).map { ruleIndex =>
        val ruleId = uuid(1000 + resourceIndex * 3 + ruleIndex)
        val rule = cpuRule(id = ruleId, resourceId = resource.id)
        MonitorEvaluationInput(rule, Some(observation(40, resource.id)), None, None)
      }
    }
    val query = new RecordingQuery(inputs)
    val states = new RecordingStateRepository
    val incidents = new RecordingIncidentRepository
    val evaluator = new EvaluateMonitorRules[IO](query, states, incidents, new FixedIdGenerator)

    evaluator.execute(resources, EvaluatedAt).unsafeRunSync()

    assertEquals(query.calls.size, 1)
    assertEquals(query.calls.head._1, OrganizationId)
    assertEquals(query.calls.head._2.toSet, resources.map(_.id).toSet)
    assertEquals(states.batches.map(_.size), List(300))
    assertEquals(incidents.batches, List.empty)
  }

  test("issues one projection query per organization") {
    val otherOrganization = uuid(9000)
    val query = new RecordingQuery(List.empty)
    val evaluator = new EvaluateMonitorRules[IO](query, new RecordingStateRepository,
      new RecordingIncidentRepository, new FixedIdGenerator)

    evaluator.execute(List(nodeResource, nodeResource.copy(id = uuid(9001), organizationId = otherOrganization)),
      EvaluatedAt).unsafeRunSync()

    assertEquals(query.calls.map(_._1).toSet, Set(OrganizationId, otherOrganization))
  }

  private def run(value: MonitorEvaluationInput): Result = {
    val query = new RecordingQuery(List(value))
    val states = new RecordingStateRepository
    val incidents = new RecordingIncidentRepository
    val transitions = new EvaluateMonitorRules[IO](query, states, incidents, new FixedIdGenerator)
      .execute(List(nodeResource), EvaluatedAt).unsafeRunSync()
    Result(states.batches.flatten, incidents.batches.flatten, transitions, states.batches.size, incidents.batches.size)
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
    forSeconds: Long = 300
  ): MonitorRule = MonitorRule(id, OrganizationId, resourceId, MetricCode.CpuUsagePercent,
    MonitorOperator.GreaterThan, BigDecimal(90), forSeconds, enabled = true, ObservedAt, ObservedAt)

  private def state(status: MonitorRuleStatus, pendingSince: Option[Instant]): MonitorRuleState =
    MonitorRuleState(OrganizationId, RuleId, status, pendingSince, ObservedAt)

  private def observation(value: BigDecimal, resourceId: UUID = ResourceId): MetricObservation =
    MetricObservation(UUID.randomUUID(), OrganizationId, resourceId, MetricCode.CpuUsagePercent, value, ObservedAt)

  private final case class Result(
    states: List[MonitorRuleState],
    incidents: List[Incident],
    transitions: List[MonitorTransition],
    stateBatchCalls: Int,
    incidentBatchCalls: Int
  )

  private final class RecordingQuery(values: List[MonitorEvaluationInput]) extends MonitorEvaluationQuery[IO] {
    var calls: List[(UUID, List[UUID])] = List.empty
    override def findEnabledForResources(organizationId: UUID, resourceIds: List[UUID]): IO[List[MonitorEvaluationInput]] = IO {
      calls = calls :+ (organizationId -> resourceIds)
      values.filter(value => value.rule.organizationId == organizationId && resourceIds.contains(value.rule.resourceId))
    }
  }

  private final class RecordingStateRepository extends MonitorRuleStateRepository[IO] {
    var batches: List[List[MonitorRuleState]] = List.empty
    override def findByRuleId(organizationId: UUID, monitorRuleId: UUID): IO[Option[MonitorRuleState]] = IO.pure(None)
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

  private def uuid(value: Int): UUID = UUID.fromString(f"00000000-0000-0000-0000-${value}%012d")

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val ResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val IncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001")
  private val NewIncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000002")
  private val ObservedAt = Instant.parse("2026-09-22T10:00:00Z")
  private val EvaluatedAt = Instant.parse("2026-09-22T10:05:00Z")
  private val okState = state(MonitorRuleStatus.Ok, None)
  private val openIncident = Incident(IncidentId, OrganizationId, RuleId, ResourceId, IncidentStatus.Open,
    ObservedAt, ObservedAt, None, ObservedAt, ObservedAt)

  private val nodeResource = Resource(ResourceId, OrganizationId, EnvironmentId, ResourceTypeId, None,
    "node", "node", isActive = true, ObservedAt, ObservedAt, "NODE", ResourceData.empty)
  private val containerResource = nodeResource.copy(id = uuid(999), resourceTypeCode = "CONTAINER")
}
