package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.metric.MetricCode
import domain.incident.{Incident, IncidentStatus}
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleState, MonitorRuleStatus}
import domain.resource.{Resource, ResourceData}
import domain.resource.node.{NodeSpec, NodeStatus}
import infrastructure.database.{Database, DatabaseConfig, DoobieTransactionRunner}
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
    val config = DatabaseConfig.load.unsafeRunSync()

    Database.transactor(config).use { xa =>
      val transactionRunner = new DoobieTransactionRunner(xa)
      val resourceRepository = new PostgresResourceRepository
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
    val config = DatabaseConfig.load.unsafeRunSync()

    Database.transactor(config).use { xa =>
      val transactionRunner = new DoobieTransactionRunner(xa)
      val resourceRepository = new PostgresResourceRepository
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
        MonitorOperator.GreaterThan,
        BigDecimal(90),
        300,
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

  private def cleanup(ids: TestIds): ConnectionIO[Unit] =
    for {
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
          and id = ${ids.ruleId}
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
        resourceCode = s"monitor-rule-$suffix"
      )
    }
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val EnvironmentId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val NodeResourceTypeId = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val Now = Instant.parse("2026-09-22T10:00:00Z")
}
