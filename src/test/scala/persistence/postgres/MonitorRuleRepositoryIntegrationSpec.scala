package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.metric.{MetricCode, MetricObservation}
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleState, MonitorRuleStatus}
import application.monitor.EvaluateMonitorRules
import domain.resource.{Resource, ResourceData}
import domain.resource.node.{NodeSpec, NodeStatus}
import infrastructure.database.{ConnectionIOIdGenerator, Database, DatabaseConfig, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class MonitorRuleRepositoryIntegrationSpec extends FunSuite {

  test("persists rule and explicit runtime state without repository side effects") {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    val ids = TestIds.random()
    val config = PostgresTestDatabase.config

    PostgresTestDatabase.transactor(config).use { xa =>
      val transactionRunner = new DoobieTransactionRunner(xa)
      val resourceRepository = ProductionResourceCodec.resourceRepository
      val ruleRepository = new PostgresMonitorRuleRepository
      val stateRepository = new PostgresMonitorRuleStateRepository
      val resource = Resource(
        id = ids.resourceId,
        organizationId = OrganizationId,
        environmentId = EnvironmentId,
        resourceTypeId = NodeResourceTypeId,
        parentResourceId = None,
        code = ids.resourceCode,
        name = ids.resourceCode,
        isActive = true,
        createdAt = Now,
        updatedAt = Now,
        resourceTypeCode = "NODE",
        data = ResourceData(
          Some(NodeSpec(ids.resourceCode, None, None, None, None)),
          Some(NodeStatus(true, None, None, None))
        )
      )
      val rule = MonitorRule(
        id = ids.ruleId,
        organizationId = OrganizationId,
        resourceId = ids.resourceId,
        metricCode = MetricCode.CpuUsagePercent,
        operator = MonitorOperator.GreaterThan,
        threshold = BigDecimal(90),
        forSeconds = 300,
        noDataSeconds = 900,
        enabled = true,
        createdAt = Now,
        updatedAt = Now
      )
      val state = MonitorRuleState(
        organizationId = OrganizationId,
        monitorRuleId = ids.ruleId,
        status = MonitorRuleStatus.Ok,
        pendingSince = None,
        updatedAt = Now
      )

      val program = for {
        _ <- transactionRunner.run(resourceRepository.save(resource))
        _ <- transactionRunner.run(ruleRepository.save(rule))
        stateBeforeExplicitCreate <- transactionRunner.run(
          stateRepository.findByRuleId(OrganizationId, ids.ruleId)
        )
        loadedRule <- transactionRunner.run(
          ruleRepository.findById(OrganizationId, ids.ruleId)
        )
        enabledRules <- transactionRunner.run(
          ruleRepository.findEnabledByResource(OrganizationId, ids.resourceId)
        )
        _ <- transactionRunner.run(stateRepository.save(state))
        loadedState <- transactionRunner.run(
          stateRepository.findByRuleId(OrganizationId, ids.ruleId)
        )
        _ <- transactionRunner.run(cleanup(ids))
      } yield (stateBeforeExplicitCreate, loadedRule, enabledRules, loadedState)

      program.guarantee(transactionRunner.run(cleanup(ids)).attempt.void).flatMap {
        case (stateBeforeExplicitCreate, loadedRule, enabledRules, loadedState) =>
          IO {
            assertEquals(stateBeforeExplicitCreate, None)
            assertEquals(loadedRule, Some(rule))
            assertEquals(enabledRules, List(rule))
            assertEquals(loadedState, Some(state))
          }
      }
    }.unsafeRunSync()
  }

  test("enforces incident lifecycle constraints and one OPEN incident per rule") {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    val ids = TestIds.random()
    val config = PostgresTestDatabase.config

    PostgresTestDatabase.transactor(config).use { xa =>
      val transactionRunner = new DoobieTransactionRunner(xa)
      val resourceRepository = ProductionResourceCodec.resourceRepository
      val ruleRepository = new PostgresMonitorRuleRepository
      val incidentRepository = new PostgresIncidentRepository
      val resource = Resource(
        id = ids.resourceId,
        organizationId = OrganizationId,
        environmentId = EnvironmentId,
        resourceTypeId = NodeResourceTypeId,
        parentResourceId = None,
        code = ids.resourceCode,
        name = ids.resourceCode,
        isActive = true,
        createdAt = Now,
        updatedAt = Now,
        resourceTypeCode = "NODE",
        data = ResourceData.empty
      )
      val rule = MonitorRule(
        ids.ruleId,
        OrganizationId,
        ids.resourceId,
        MetricCode.CpuUsagePercent,
        MonitorOperator.LessThanOrEqual,
        BigDecimal(90),
        300,
        900,
        enabled = true,
        Now,
        Now
      )
      val open = Incident(
        ids.incidentId,
        OrganizationId,
        ids.ruleId,
        ids.resourceId,
        IncidentStatus.Open,
        IncidentReason.NoData,
        Now,
        Now,
        None,
        Now,
        Now
      )

      val program = for {
        _ <- transactionRunner.run(resourceRepository.save(resource))
        _ <- transactionRunner.run(ruleRepository.save(rule))
        _ <- transactionRunner.run(incidentRepository.save(open))
        loadedOpen <- transactionRunner.run(incidentRepository.findOpenByRule(OrganizationId, ids.ruleId))
        openWithResolution <- transactionRunner.run(
          incidentRepository.save(open.copy(id = ids.secondIncidentId, resolvedAt = Some(Now)))
        ).attempt
        resolvedWithoutResolution <- transactionRunner.run(
          incidentRepository.save(open.copy(id = ids.secondIncidentId, status = IncidentStatus.Resolved))
        ).attempt
        duplicateOpen <- transactionRunner.run(
          incidentRepository.save(open.copy(id = ids.secondIncidentId))
        ).attempt
        _ <- transactionRunner.run(cleanup(ids))
      } yield (loadedOpen, openWithResolution, resolvedWithoutResolution, duplicateOpen)

      program.guarantee(transactionRunner.run(cleanup(ids)).attempt.void).flatMap {
        case (loadedOpen, openWithResolution, resolvedWithoutResolution, duplicateOpen) =>
          IO {
            assertEquals(loadedOpen, Some(open))
            assert(openWithResolution.isLeft)
            assert(resolvedWithoutResolution.isLeft)
            assert(duplicateOpen.isLeft)
          }
      }
    }.unsafeRunSync()
  }

  test("loads the complete monitor projection and inserts observations in one batch") {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    val ids = TestIds.random()
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val resources = ProductionResourceCodec.resourceRepository
      val rules = new PostgresMonitorRuleRepository
      val states = new PostgresMonitorRuleStateRepository
      val incidents = new PostgresIncidentRepository
      val metrics = new PostgresMetricObservationRepository
      val projection = new PostgresMonitorEvaluationQuery
      val resource = Resource(ids.resourceId, OrganizationId, EnvironmentId, NodeResourceTypeId, None,
        ids.resourceCode, ids.resourceCode, isActive = true, Now, Now, "NODE", ResourceData.empty)
      val rule = MonitorRule(ids.ruleId, OrganizationId, ids.resourceId, MetricCode.CpuUsagePercent,
        MonitorOperator.GreaterThan, BigDecimal(90), 300, 900, enabled = true, Now, Now)
      val memoryRule = rule.copy(id = UUID.randomUUID(), metricCode = MetricCode.MemoryUsagePercent)
      val disabledRule = rule.copy(id = UUID.randomUUID(), enabled = false)
      val state = MonitorRuleState(OrganizationId, ids.ruleId, MonitorRuleStatus.Firing, Some(Now), Now)
      val incident = Incident(ids.incidentId, OrganizationId, ids.ruleId, ids.resourceId,
        IncidentStatus.Open, IncidentReason.ThresholdViolation, Now, Now, None, Now, Now)
      val resolvedIncident = incident.copy(id = ids.secondIncidentId, status = IncidentStatus.Resolved,
        resolvedAt = Some(Now.plusSeconds(1)), updatedAt = Now.plusSeconds(1))
      val observations = List(
        MetricObservation(UUID.randomUUID(), OrganizationId, ids.resourceId,
          MetricCode.CpuUsagePercent, BigDecimal(10), Now.minusSeconds(1)),
        MetricObservation(UUID.randomUUID(), OrganizationId, ids.resourceId,
          MetricCode.CpuUsagePercent, BigDecimal(91), Now.plusSeconds(1)),
        MetricObservation(UUID.randomUUID(), OrganizationId, ids.resourceId,
          MetricCode.CpuUsagePercent, BigDecimal(99), Now.plusSeconds(2)),
        MetricObservation(UUID.randomUUID(), OrganizationId, ids.resourceId,
          MetricCode.MemoryUsagePercent, BigDecimal(77), Now.minusSeconds(1))
      )

      val program = for {
        _ <- runner.run(resources.save(resource))
        _ <- runner.run(rules.save(rule))
        _ <- runner.run(rules.save(memoryRule))
        _ <- runner.run(rules.save(disabledRule))
        _ <- runner.run(metrics.insertAll(observations))
        _ <- runner.run(states.saveAll(List(state)))
        _ <- runner.run(incidents.saveAll(List(incident, resolvedIncident)))
        _ <- runner.run(linkResourceToConnection(ids))
        loaded <- runner.run(projection.findEnabledForConnection(
          OrganizationId, ConnectionId, List("NODE")))
        foreign <- runner.run(projection.findEnabledForConnection(
          UUID.randomUUID(), ConnectionId, List("NODE")))
        otherConnection <- runner.run(projection.findEnabledForConnection(
          OrganizationId, UUID.randomUUID(), List("NODE")))
        otherType <- runner.run(projection.findEnabledForConnection(
          OrganizationId, ConnectionId, List("CONTAINER")))
        persistedMetrics <- runner.run(metrics.findByResourceAndPeriod(
          OrganizationId, ids.resourceId, Now.minusSeconds(2), Now.plusSeconds(3)))
        indexNames <- runner.run(sql"""
          select indexname from pg_indexes
          where schemaname = current_schema()
            and indexname in (
              'ix_metric_observation_resource_metric_observed_at',
              'ix_monitor_rule_enabled_resource'
            )
        """.query[String].to[List])
      } yield (loaded, foreign, otherConnection, otherType, persistedMetrics, indexNames)

      program.guarantee(runner.run(cleanup(ids)).attempt.void).flatMap {
        case (loaded, foreign, otherConnection, otherType, persistedMetrics, indexNames) => IO {
        assertEquals(loaded.map(_.rule.id).toSet, Set(rule.id, memoryRule.id))
        val cpu = loaded.find(_.rule.id == rule.id).get
        val memory = loaded.find(_.rule.id == memoryRule.id).get
        assertEquals(cpu.rule.noDataSeconds, 900L)
        assertEquals(cpu.observation.map(_.value), Some(BigDecimal(99)))
        assertEquals(cpu.state, Some(state))
        assertEquals(cpu.openIncident, Some(incident))
        assertEquals(cpu.openIncident.map(_.reason), Some(IncidentReason.ThresholdViolation))
        // The latest observation is chosen per resource and metric, so the memory rule sees its
        // own older sample instead of the CPU one.
        assertEquals(memory.observation.map(_.value), Some(BigDecimal(77)))
        assertEquals(memory.observation.map(_.metricCode), Some(MetricCode.MemoryUsagePercent))
        assertEquals(memory.state, None)
        assertEquals(memory.openIncident, None)
        assertEquals(foreign, List.empty)
        assertEquals(otherConnection, List.empty)
        assertEquals(otherType, List.empty)
        assertEquals(persistedMetrics.map(_.id).toSet, observations.map(_.id).toSet)
        assertEquals(indexNames.toSet, Set(
          "ix_metric_observation_resource_metric_observed_at",
          "ix_monitor_rule_enabled_resource"
        ))
      }}
    }.unsafeRunSync()
  }

  test("evaluates a stale rule end to end and moves it from NO_DATA to FIRING when data returns") {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )

    val ids = TestIds.random()
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val resources = ProductionResourceCodec.resourceRepository
      val rules = new PostgresMonitorRuleRepository
      val states = new PostgresMonitorRuleStateRepository
      val incidents = new PostgresIncidentRepository
      val metrics = new PostgresMetricObservationRepository
      val evaluator = new EvaluateMonitorRules[ConnectionIO](
        new PostgresMonitorEvaluationQuery, states, incidents, new ConnectionIOIdGenerator
      )
      val resource = Resource(ids.resourceId, OrganizationId, EnvironmentId, NodeResourceTypeId, None,
        ids.resourceCode, ids.resourceCode, isActive = true, Now, Now, "NODE", ResourceData.empty)
      val rule = MonitorRule(ids.ruleId, OrganizationId, ids.resourceId, MetricCode.CpuUsagePercent,
        MonitorOperator.GreaterThan, BigDecimal(90), 0, 600, enabled = true, Now.minusSeconds(7200), Now)
      val stale = MetricObservation(UUID.randomUUID(), OrganizationId, ids.resourceId,
        MetricCode.CpuUsagePercent, BigDecimal(10), Now.minusSeconds(3600))
      val fresh = MetricObservation(UUID.randomUUID(), OrganizationId, ids.resourceId,
        MetricCode.CpuUsagePercent, BigDecimal(95), Now)

      val program = for {
        _ <- runner.run(resources.save(resource))
        _ <- runner.run(rules.save(rule))
        _ <- runner.run(linkResourceToConnection(ids))
        _ <- runner.run(metrics.insertAll(List(stale)))
        storedRule <- runner.run(rules.findById(OrganizationId, ids.ruleId))
        noDataTransitions <- runner.run(evaluator.execute(OrganizationId, ConnectionId, Now))
        noDataState <- runner.run(states.findByRuleId(OrganizationId, ids.ruleId))
        noDataIncident <- runner.run(incidents.findOpenByRule(OrganizationId, ids.ruleId))
        _ <- runner.run(metrics.insertAll(List(fresh)))
        firingTransitions <- runner.run(evaluator.execute(OrganizationId, ConnectionId, Now))
        firingState <- runner.run(states.findByRuleId(OrganizationId, ids.ruleId))
        firingIncident <- runner.run(incidents.findOpenByRule(OrganizationId, ids.ruleId))
        allIncidents <- runner.run(incidents.findByOrganization(OrganizationId, None))
        statesOfResource <- runner.run(states.findByResource(OrganizationId, ids.resourceId))
      } yield (storedRule, noDataTransitions, noDataState, noDataIncident, firingTransitions,
        firingState, firingIncident, allIncidents.filter(_.monitorRuleId == ids.ruleId), statesOfResource)

      program.guarantee(runner.run(cleanup(ids)).attempt.void).flatMap {
        case (storedRule, noDataTransitions, noDataState, noDataIncident, firingTransitions,
              firingState, firingIncident, ruleIncidents, statesOfResource) => IO {
          assertEquals(storedRule.map(_.noDataSeconds), Some(600L))
          assertEquals(noDataState.map(_.status), Some(MonitorRuleStatus.NoData))
          assertEquals(noDataState.flatMap(_.pendingSince), None)
          assertEquals(noDataIncident.map(_.reason), Some(IncidentReason.NoData))
          assertEquals(noDataTransitions.map(t => (t.eventName, t.reason)),
            List(("incident.opened", IncidentReason.NoData)))

          assertEquals(firingState.map(_.status), Some(MonitorRuleStatus.Firing))
          assertEquals(firingIncident.map(_.reason), Some(IncidentReason.ThresholdViolation))
          assertEquals(firingTransitions.map(t => (t.eventName, t.reason)), List(
            ("incident.resolved", IncidentReason.NoData),
            ("incident.opened", IncidentReason.ThresholdViolation)
          ))

          // The database allows only one OPEN incident per rule, so the no-data incident has to be
          // resolved in the same transaction that opens the threshold incident.
          assertEquals(ruleIncidents.count(_.status == IncidentStatus.Open), 1)
          assertEquals(ruleIncidents.size, 2)
          assertEquals(statesOfResource.map(_.monitorRuleId), List(ids.ruleId))
        }
      }
    }.unsafeRunSync()
  }

  private def linkResourceToConnection(ids: TestIds): ConnectionIO[Unit] =
    sql"""
      insert into external_ref (id, organization_id, connection_id, external_type, external_id, resource_id)
      values (${ids.externalRefId}, $OrganizationId, $ConnectionId, 'NODE', ${ids.resourceCode}, ${ids.resourceId})
      on conflict do nothing
    """.update.run.void

  private def cleanup(ids: TestIds): ConnectionIO[Unit] =
    for {
      _ <- sql"""
        delete from external_ref
        where organization_id = $OrganizationId
          and resource_id = ${ids.resourceId}
      """.update.run
      _ <- sql"""
        delete from metric_observation
        where organization_id = $OrganizationId
          and resource_id = ${ids.resourceId}
      """.update.run
      _ <- sql"""
        delete from incident
        where organization_id = $OrganizationId
          and monitor_rule_id = ${ids.ruleId}
      """.update.run
      _ <- sql"""
        delete from monitor_rule_state
        where organization_id = $OrganizationId
          and monitor_rule_id = ${ids.ruleId}
      """.update.run
      _ <- sql"""
        delete from monitor_rule
        where organization_id = $OrganizationId
          and resource_id = ${ids.resourceId}
      """.update.run
      _ <- sql"""
        delete from resource
        where organization_id = $OrganizationId
          and id = ${ids.resourceId}
      """.update.run
    } yield ()

  private final case class TestIds(
                                    resourceId: UUID,
                                    ruleId: UUID,
                                    incidentId: UUID,
                                    secondIncidentId: UUID,
                                    externalRefId: UUID,
                                    resourceCode: String
                                  )

  private object TestIds {
    def random(): TestIds = {
      val suffix = UUID.randomUUID().toString.replace("-", "")

      TestIds(
        resourceId = UUID.randomUUID(),
        ruleId = UUID.randomUUID(),
        incidentId = UUID.randomUUID(),
        secondIncidentId = UUID.randomUUID(),
        externalRefId = UUID.randomUUID(),
        resourceCode = s"monitor-rule-$suffix"
      )
    }
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val NodeResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ConnectionId = UUID.fromString("60000000-0000-0000-0000-000000000003")
  private val Now = Instant.parse("2026-09-22T10:00:00Z")
}
