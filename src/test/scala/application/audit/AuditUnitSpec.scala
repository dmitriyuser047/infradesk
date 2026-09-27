package ru.bitec.app.ops
package application.audit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.audit.{AuditAction, AuditTargetType}
import munit.FunSuite
import support.{AuthorizationFixtures, TestAuditRecorder}

import java.util.UUID

final class AuditUnitSpec extends FunSuite {

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val TargetId = UUID.fromString("90000000-0000-0000-0000-000000000001")

  test("audit codes round-trip and reject unknown values") {
    AuditAction.All.foreach(value => assertEquals(AuditAction.fromCode(value.code), Right(value)))
    AuditTargetType.All.foreach(value =>
      assertEquals(AuditTargetType.fromCode(value.code), Right(value))
    )

    assertEquals(AuditAction.All.map(_.code), List(
      "PROJECT_CREATED", "ENVIRONMENT_CREATED",
      "CONNECTION_CREATED", "CONNECTION_UPDATED", "CONNECTION_DELETED",
      "MONITOR_RULE_CREATED", "MONITOR_RULE_UPDATED", "MANUAL_SYNC_REQUESTED",
      "CONTAINER_START_REQUESTED", "CONTAINER_STOP_REQUESTED", "CONTAINER_RESTART_REQUESTED",
      "NOTIFICATION_CHANNEL_CREATED", "NOTIFICATION_CHANNEL_UPDATED",
      "NOTIFICATION_CHANNEL_ENABLED", "NOTIFICATION_CHANNEL_DISABLED",
      "ACCOUNT_PASSWORD_CHANGED", "ACCOUNT_PROFILE_UPDATED",
      "ACCOUNT_SESSION_REVOKED", "ACCOUNT_OTHER_SESSIONS_REVOKED", "ACCOUNT_ALL_SESSIONS_REVOKED",
      "TERMINAL_SESSION_OPENED", "TERMINAL_SESSION_CLOSED"
    ))
    assertEquals(AuditTargetType.All.map(_.code),
      List("PROJECT", "ENVIRONMENT", "CONNECTION", "MONITOR_RULE", "RESOURCE",
        "NOTIFICATION_CHANNEL", "ACCOUNT", "TERMINAL_SESSION"))

    assert(AuditAction.fromCode("PROJECT_DELETED").isLeft)
    assert(AuditTargetType.fromCode("SECRET").isLeft)
  }

  test("the recorder stores actor, organization, action, target and time from its providers") {
    val (journal, recorder) = TestAuditRecorder.recording

    recorder.record(AuthorizationFixtures.actor(OrganizationId), AuditAction.MonitorRuleUpdated,
      AuditTargetType.MonitorRule, Some(TargetId)).unsafeRunSync()
    val event = journal.recorded.head

    assertEquals(journal.recorded.size, 1)
    assertEquals(event.organizationId, OrganizationId)
    assertEquals(event.actorUserId, AuthorizationFixtures.ActorUserId)
    assertEquals(event.action, AuditAction.MonitorRuleUpdated)
    assertEquals(event.targetType, AuditTargetType.MonitorRule)
    assertEquals(event.targetId, Some(TargetId))
    assertEquals(event.occurredAt, TestAuditRecorder.RecordedAt)
    assertEquals(event.createdAt, TestAuditRecorder.RecordedAt)
    assertEquals(event.id, UUID.fromString("e0000000-0000-0000-0000-000000000001"))
  }

  test("a failing journal fails the effect it was recorded in") {
    val recorder = TestAuditRecorder(new support.FailingAuditEventRepository)

    val outcome = recorder.record(AuthorizationFixtures.actor(OrganizationId),
      AuditAction.ProjectCreated, AuditTargetType.Project, Some(TargetId)).attempt.unsafeRunSync()

    // Audit is part of the business transaction, not a best-effort log line.
    assert(outcome.isLeft)
  }

  test("the page size is bounded whatever the caller asks for") {
    assertEquals(ListAuditEvents.boundedLimit(10), 10)
    assertEquals(ListAuditEvents.boundedLimit(0), 1)
    assertEquals(ListAuditEvents.boundedLimit(-5), 1)
    assertEquals(ListAuditEvents.boundedLimit(Int.MaxValue), ListAuditEvents.MaxLimit)
    assertEquals(ListAuditEvents.DefaultLimit, 50)
  }
}
