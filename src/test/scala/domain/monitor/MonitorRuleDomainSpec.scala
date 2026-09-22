package ru.bitec.app.ops
package domain.monitor

import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class MonitorRuleDomainSpec extends FunSuite {

  test("parses GREATER_THAN monitor operator") {
    assertEquals(
      MonitorOperator.fromCode("GREATER_THAN"),
      Right(MonitorOperator.GreaterThan)
    )
  }

  test("rejects an unknown monitor operator") {
    assert(MonitorOperator.fromCode("LESS_THAN").isLeft)
  }

  test("round-trips all monitor rule statuses") {
    val statuses = List(
      MonitorRuleStatus.Ok,
      MonitorRuleStatus.Pending,
      MonitorRuleStatus.Firing
    )

    statuses.foreach { status =>
      assertEquals(MonitorRuleStatus.fromCode(status.code), Right(status))
    }
  }

  test("rejects an unknown monitor rule status") {
    assert(MonitorRuleStatus.fromCode("UNKNOWN").isLeft)
  }

  test("allows an OK state without pendingSince") {
    val state = MonitorRuleState(
      OrganizationId,
      RuleId,
      MonitorRuleStatus.Ok,
      None,
      UpdatedAt
    )

    assertEquals(state.pendingSince, None)
  }

  test("allows a PENDING state with pendingSince") {
    val state = MonitorRuleState(
      OrganizationId,
      RuleId,
      MonitorRuleStatus.Pending,
      Some(PendingSince),
      UpdatedAt
    )

    assertEquals(state.pendingSince, Some(PendingSince))
  }

  test("allows a FIRING state with pendingSince") {
    val state = MonitorRuleState(
      OrganizationId,
      RuleId,
      MonitorRuleStatus.Firing,
      Some(PendingSince),
      UpdatedAt
    )

    assertEquals(state.pendingSince, Some(PendingSince))
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val PendingSince = Instant.parse("2026-09-22T10:00:00Z")
  private val UpdatedAt = Instant.parse("2026-09-22T10:05:00Z")
}
