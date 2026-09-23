package ru.bitec.app.ops
package domain.monitor

import domain.incident.IncidentReason
import domain.metric.MetricCode
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class MonitorRuleDomainSpec extends FunSuite {

  test("round-trips every monitor operator code") {
    MonitorOperator.All.foreach { operator =>
      assertEquals(MonitorOperator.fromCode(operator.code), Right(operator))
    }
    assertEquals(MonitorOperator.All.map(_.code), List(
      "GREATER_THAN", "GREATER_THAN_OR_EQUAL", "LESS_THAN", "LESS_THAN_OR_EQUAL"
    ))
  }

  test("rejects an unknown monitor operator") {
    assert(MonitorOperator.fromCode("BETWEEN").isLeft)
  }

  test("compares values against the threshold at and around equality") {
    val threshold = BigDecimal(80)
    val below = BigDecimal("79.9")
    val equal = BigDecimal("80.0")
    val above = BigDecimal("80.1")

    assertEquals(MonitorOperator.GreaterThan.matches(below, threshold), false)
    assertEquals(MonitorOperator.GreaterThan.matches(equal, threshold), false)
    assertEquals(MonitorOperator.GreaterThan.matches(above, threshold), true)

    assertEquals(MonitorOperator.GreaterThanOrEqual.matches(below, threshold), false)
    assertEquals(MonitorOperator.GreaterThanOrEqual.matches(equal, threshold), true)
    assertEquals(MonitorOperator.GreaterThanOrEqual.matches(above, threshold), true)

    assertEquals(MonitorOperator.LessThan.matches(below, threshold), true)
    assertEquals(MonitorOperator.LessThan.matches(equal, threshold), false)
    assertEquals(MonitorOperator.LessThan.matches(above, threshold), false)

    assertEquals(MonitorOperator.LessThanOrEqual.matches(below, threshold), true)
    assertEquals(MonitorOperator.LessThanOrEqual.matches(equal, threshold), true)
    assertEquals(MonitorOperator.LessThanOrEqual.matches(above, threshold), false)
  }

  test("compares scaled decimals by value and not by representation") {
    assertEquals(MonitorOperator.GreaterThanOrEqual.matches(BigDecimal("80.00"), BigDecimal(80)), true)
    assertEquals(MonitorOperator.LessThanOrEqual.matches(BigDecimal("80.000"), BigDecimal(80)), true)
  }

  test("round-trips all monitor rule statuses") {
    MonitorRuleStatus.All.foreach { status =>
      assertEquals(MonitorRuleStatus.fromCode(status.code), Right(status))
    }
    assertEquals(MonitorRuleStatus.All.map(_.code), List("OK", "PENDING", "FIRING", "NO_DATA"))
  }

  test("rejects an unknown monitor rule status") {
    assert(MonitorRuleStatus.fromCode("UNKNOWN").isLeft)
  }

  test("round-trips incident reasons and rejects unknown ones") {
    IncidentReason.All.foreach { reason =>
      assertEquals(IncidentReason.fromCode(reason.code), Right(reason))
    }
    assertEquals(IncidentReason.All.map(_.code), List("THRESHOLD", "NO_DATA"))
    assert(IncidentReason.fromCode("FLAPPING").isLeft)
  }

  test("accepts a rule whose durations and percentage threshold are in range") {
    assertEquals(
      MonitorRuleValidation.validate(MetricCode.CpuUsagePercent, BigDecimal(80), 300, 900),
      Right(())
    )
    assertEquals(
      MonitorRuleValidation.validate(MetricCode.MemoryUsagePercent, BigDecimal(0), 0, 0),
      Right(())
    )
    assertEquals(
      MonitorRuleValidation.validate(MetricCode.MemoryUsagePercent, BigDecimal(100), 0, 0),
      Right(())
    )
  }

  test("rejects negative durations and out-of-range percentage thresholds") {
    val negativeFor = MonitorRuleValidation.validate(MetricCode.CpuUsagePercent, BigDecimal(80), -1, 900)
    val negativeNoData = MonitorRuleValidation.validate(MetricCode.CpuUsagePercent, BigDecimal(80), 300, -1)
    val tooHigh = MonitorRuleValidation.validate(MetricCode.CpuUsagePercent, BigDecimal("100.1"), 300, 900)
    val tooLow = MonitorRuleValidation.validate(MetricCode.MemoryUsagePercent, BigDecimal(-1), 300, 900)

    assertEquals(negativeFor.swap.toOption.map(_.getMessage), Some("forSeconds must not be negative"))
    assertEquals(negativeNoData.swap.toOption.map(_.getMessage), Some("noDataSeconds must not be negative"))
    assert(tooHigh.swap.toOption.exists(_.getMessage.contains("CPU_USAGE_PERCENT")))
    assert(tooLow.swap.toOption.exists(_.getMessage.contains("MEMORY_USAGE_PERCENT")))
  }

  test("allows an OK state without pendingSince") {
    val state = MonitorRuleState(OrganizationId, RuleId, MonitorRuleStatus.Ok, None, UpdatedAt)

    assertEquals(state.pendingSince, None)
  }

  test("allows a PENDING state with pendingSince") {
    val state = MonitorRuleState(OrganizationId, RuleId, MonitorRuleStatus.Pending, Some(PendingSince), UpdatedAt)

    assertEquals(state.pendingSince, Some(PendingSince))
  }

  test("allows a FIRING state with pendingSince") {
    val state = MonitorRuleState(OrganizationId, RuleId, MonitorRuleStatus.Firing, Some(PendingSince), UpdatedAt)

    assertEquals(state.pendingSince, Some(PendingSince))
  }

  test("allows a NO_DATA state without pendingSince") {
    val state = MonitorRuleState(OrganizationId, RuleId, MonitorRuleStatus.NoData, None, UpdatedAt)

    assertEquals(state.pendingSince, None)
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val PendingSince = Instant.parse("2026-09-22T10:00:00Z")
  private val UpdatedAt = Instant.parse("2026-09-22T10:05:00Z")
}
