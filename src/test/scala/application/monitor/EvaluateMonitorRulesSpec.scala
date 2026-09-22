package ru.bitec.app.ops
package application.monitor

import application.port.{MetricObservationRepository, MonitorRuleRepository, MonitorRuleStateRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.metric.{MetricCode, MetricObservation}
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleState, MonitorRuleStatus}
import domain.resource.{Resource, ResourceData}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class EvaluateMonitorRulesSpec extends FunSuite {

  test("creates OK when a normal metric has no state") {
    val result = evaluate(cpuRule(), None, Some(observation(MetricCode.CpuUsagePercent, 40)))

    assertEquals(result.state.status, MonitorRuleStatus.Ok)
    assertEquals(result.state.pendingSince, None)
    assertEquals(result.state.updatedAt, EvaluatedAt)
  }

  test("creates PENDING when OK violates a non-zero duration rule") {
    val metricAt = Instant.parse("2026-09-22T10:01:00Z")
    val result = evaluate(cpuRule(forSeconds = 300), Some(okState), Some(observation(MetricCode.CpuUsagePercent, 95, metricAt)))

    assertEquals(result.state.status, MonitorRuleStatus.Pending)
    assertEquals(result.state.pendingSince, Some(metricAt))
  }

  test("keeps PENDING and original pendingSince before duration passes") {
    val pendingSince = Instant.parse("2026-09-22T10:00:00Z")
    val result = evaluate(
      cpuRule(forSeconds = 300),
      Some(state(MonitorRuleStatus.Pending, Some(pendingSince))),
      Some(observation(MetricCode.CpuUsagePercent, 95, pendingSince.plusSeconds(299)))
    )

    assertEquals(result.state.status, MonitorRuleStatus.Pending)
    assertEquals(result.state.pendingSince, Some(pendingSince))
  }

  test("promotes PENDING to FIRING when duration is reached") {
    val pendingSince = Instant.parse("2026-09-22T10:00:00Z")
    val result = evaluate(
      cpuRule(forSeconds = 300),
      Some(state(MonitorRuleStatus.Pending, Some(pendingSince))),
      Some(observation(MetricCode.CpuUsagePercent, 95, pendingSince.plusSeconds(300)))
    )

    assertEquals(result.state.status, MonitorRuleStatus.Firing)
    assertEquals(result.state.pendingSince, Some(pendingSince))
  }

  test("keeps FIRING and pendingSince while violation continues") {
    val pendingSince = Instant.parse("2026-09-22T10:00:00Z")
    val result = evaluate(
      cpuRule(),
      Some(state(MonitorRuleStatus.Firing, Some(pendingSince))),
      Some(observation(MetricCode.CpuUsagePercent, 95, pendingSince.plusSeconds(600)))
    )

    assertEquals(result.state.status, MonitorRuleStatus.Firing)
    assertEquals(result.state.pendingSince, Some(pendingSince))
  }

  test("resets FIRING to OK after recovery") {
    val result = evaluate(
      cpuRule(),
      Some(state(MonitorRuleStatus.Firing, Some(ObservedAt))),
      Some(observation(MetricCode.CpuUsagePercent, 40))
    )

    assertEquals(result.state.status, MonitorRuleStatus.Ok)
    assertEquals(result.state.pendingSince, None)
  }

  test("fires immediately for a zero-duration violation") {
    val metricAt = Instant.parse("2026-09-22T10:01:00Z")
    val result = evaluate(
      cpuRule(forSeconds = 0),
      None,
      Some(observation(MetricCode.CpuUsagePercent, 95, metricAt))
    )

    assertEquals(result.state.status, MonitorRuleStatus.Firing)
    assertEquals(result.state.pendingSince, Some(metricAt))
  }

  test("does not create or change state when latest observation is absent") {
    val original = state(MonitorRuleStatus.Firing, Some(ObservedAt))
    val result = evaluate(cpuRule(), Some(original), None)

    assertEquals(result.states.get(RuleId), Some(original))
    assertEquals(result.saved, List.empty)
  }

  test("does not evaluate rules for a container resource") {
    val ruleRepository = new InMemoryRuleRepository(Map(ContainerResourceId -> List(cpuRule())))
    val stateRepository = new InMemoryStateRepository(Map.empty)
    val metricRepository = new InMemoryMetricObservationRepository(Map.empty)
    val evaluator = new EvaluateMonitorRules[IO](ruleRepository, stateRepository, metricRepository)

    evaluator.execute(List(containerResource), EvaluatedAt).unsafeRunSync()

    assertEquals(ruleRepository.findEnabledCalls, List.empty)
    assertEquals(stateRepository.saved, List.empty)
  }

  test("uses each rule metric code to select CPU and memory observations") {
    val memoryRule = cpuRule(
      id = MemoryRuleId,
      metricCode = MetricCode.MemoryUsagePercent
    )
    val ruleRepository = new InMemoryRuleRepository(Map(ResourceId -> List(cpuRule(), memoryRule)))
    val stateRepository = new InMemoryStateRepository(Map.empty)
    val metricRepository = new InMemoryMetricObservationRepository(
      Map(
        (ResourceId, MetricCode.CpuUsagePercent) -> observation(MetricCode.CpuUsagePercent, 40),
        (ResourceId, MetricCode.MemoryUsagePercent) -> observation(MetricCode.MemoryUsagePercent, 95)
      )
    )
    val evaluator = new EvaluateMonitorRules[IO](ruleRepository, stateRepository, metricRepository)

    evaluator.execute(List(nodeResource), EvaluatedAt).unsafeRunSync()

    assertEquals(
      metricRepository.requestedCodes,
      List(MetricCode.CpuUsagePercent, MetricCode.MemoryUsagePercent)
    )
    assertEquals(stateRepository.states(RuleId).status, MonitorRuleStatus.Ok)
    assertEquals(stateRepository.states(MemoryRuleId).status, MonitorRuleStatus.Pending)
  }

  private def evaluate(
                        rule: MonitorRule,
                        currentState: Option[MonitorRuleState],
                        latestObservation: Option[MetricObservation]
                      ): EvaluationResult = {
    val ruleRepository = new InMemoryRuleRepository(Map(ResourceId -> List(rule)))
    val stateRepository = new InMemoryStateRepository(currentState.map(value => Map(rule.id -> value)).getOrElse(Map.empty))
    val metricRepository = new InMemoryMetricObservationRepository(
      latestObservation.map(value => Map((ResourceId, rule.metricCode) -> value)).getOrElse(Map.empty)
    )
    val evaluator = new EvaluateMonitorRules[IO](ruleRepository, stateRepository, metricRepository)

    evaluator.execute(List(nodeResource), EvaluatedAt).unsafeRunSync()

    EvaluationResult(
      stateRepository.states(rule.id),
      stateRepository.states,
      stateRepository.saved
    )
  }

  private def cpuRule(
                       id: UUID = RuleId,
                       metricCode: MetricCode = MetricCode.CpuUsagePercent,
                       forSeconds: Long = 300
                     ): MonitorRule =
    MonitorRule(
      id,
      OrganizationId,
      ResourceId,
      metricCode,
      MonitorOperator.GreaterThan,
      BigDecimal(90),
      forSeconds,
      enabled = true,
      createdAt = ObservedAt,
      updatedAt = ObservedAt
    )

  private def state(
                    status: MonitorRuleStatus,
                    pendingSince: Option[Instant]
                  ): MonitorRuleState =
    MonitorRuleState(OrganizationId, RuleId, status, pendingSince, ObservedAt)

  private def observation(
                           metricCode: MetricCode,
                           value: BigDecimal,
                           observedAt: Instant = ObservedAt
                         ): MetricObservation =
    MetricObservation(
      UUID.randomUUID(),
      OrganizationId,
      ResourceId,
      metricCode,
      value,
      observedAt
    )

  private final case class EvaluationResult(
                                              state: MonitorRuleState,
                                              states: Map[UUID, MonitorRuleState],
                                              saved: List[MonitorRuleState]
                                            )

  private final class InMemoryRuleRepository(
                                               rulesByResource: Map[UUID, List[MonitorRule]]
                                             ) extends MonitorRuleRepository[IO] {
    var findEnabledCalls: List[UUID] = List.empty

    override def findById(organizationId: UUID, id: UUID): IO[Option[MonitorRule]] =
      IO.pure(rulesByResource.values.flatten.find(_.id == id))

    override def findEnabledByResource(organizationId: UUID, resourceId: UUID): IO[List[MonitorRule]] =
      IO {
        findEnabledCalls = findEnabledCalls :+ resourceId
        rulesByResource.getOrElse(resourceId, List.empty).filter(_.enabled)
      }

    override def save(rule: MonitorRule): IO[Unit] =
      IO.unit
  }

  private final class InMemoryStateRepository(
                                                initial: Map[UUID, MonitorRuleState]
                                              ) extends MonitorRuleStateRepository[IO] {
    var states: Map[UUID, MonitorRuleState] = initial
    var saved: List[MonitorRuleState] = List.empty

    override def findByRuleId(organizationId: UUID, monitorRuleId: UUID): IO[Option[MonitorRuleState]] =
      IO.pure(states.get(monitorRuleId))

    override def save(state: MonitorRuleState): IO[Unit] =
      IO {
        states = states.updated(state.monitorRuleId, state)
        saved = saved :+ state
      }
  }

  private final class InMemoryMetricObservationRepository(
                                                            observations: Map[(UUID, MetricCode), MetricObservation]
                                                          ) extends MetricObservationRepository[IO] {
    var requestedCodes: List[MetricCode] = List.empty

    override def insertAll(observations: List[MetricObservation]): IO[Unit] =
      IO.unit

    override def findLatest(
                             organizationId: UUID,
                             resourceId: UUID,
                             metricCode: MetricCode
                           ): IO[Option[MetricObservation]] =
      IO {
        requestedCodes = requestedCodes :+ metricCode
        observations.get((resourceId, metricCode))
      }
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val ResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val ContainerResourceId = UUID.fromString("70000000-0000-0000-0000-000000000002")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val MemoryRuleId = UUID.fromString("90000000-0000-0000-0000-000000000002")
  private val ObservedAt = Instant.parse("2026-09-22T10:00:00Z")
  private val EvaluatedAt = Instant.parse("2026-09-22T10:05:00Z")

  private val nodeResource = Resource(
    ResourceId,
    OrganizationId,
    EnvironmentId,
    ResourceTypeId,
    None,
    "node",
    "node",
    isActive = true,
    createdAt = ObservedAt,
    updatedAt = ObservedAt,
    resourceTypeCode = "NODE",
    data = ResourceData.empty
  )

  private val containerResource = nodeResource.copy(
    id = ContainerResourceId,
    resourceTypeCode = "CONTAINER"
  )

  private val okState = state(MonitorRuleStatus.Ok, None)
}
