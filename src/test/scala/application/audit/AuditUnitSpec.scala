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
      "INTEGRATION_CREATED", "INTEGRATION_UPDATED", "INTEGRATION_ENABLED",
      "INTEGRATION_DISABLED", "INTEGRATION_DELETED", "INTEGRATION_RECOVERY_ABANDONED", "INTEGRATION_TEST_REQUESTED",
      "INTEGRATION_SYNC_REQUESTED", "INTEGRATION_RESOURCE_BOUND", "INTEGRATION_RESOURCE_UNBOUND",
      "INTEGRATION_ACTION_REQUESTED",
      "INTEGRATION_MANAGEMENT_MODE_CHANGED", "INTEGRATION_DESIRED_STATE_SET", "INTEGRATION_DESIRED_STATE_REMOVED",
      "INTEGRATION_CONFIG_PROFILE_ADOPTED", "INTEGRATION_CONFIG_REVISION_CREATED",
      "INTEGRATION_CONFIG_DEPLOYMENT_REQUESTED", "INTEGRATION_CONFIG_ROLLOUT_REQUESTED",
      "INTEGRATION_CONFIG_ROLLOUT_CANCELLED",
      "ACCOUNT_PASSWORD_CHANGED", "ACCOUNT_PROFILE_UPDATED",
      "ACCOUNT_SESSION_REVOKED", "ACCOUNT_OTHER_SESSIONS_REVOKED", "ACCOUNT_ALL_SESSIONS_REVOKED",
      "TERMINAL_SESSION_OPENED", "TERMINAL_SESSION_CLOSED",
      "CONFIGURATION_PROFILE_CREATED", "CONFIGURATION_PROFILE_UPDATED",
      "CONFIGURATION_PROFILE_ARCHIVED", "CONFIGURATION_REVISION_CREATED",
      "CONFIGURATION_ASSIGNMENT_CREATED", "CONFIGURATION_ASSIGNMENT_UPDATED", "CONFIGURATION_ASSIGNMENT_REMOVED",
      "CONFIGURATION_DEPLOYMENT_REQUESTED", "PROVISIONING_RUN_REQUESTED", "CONFIGURATION_DEPLOYMENT_CANCELLED",
      "CONFIGURATION_ASSIGNMENTS_PROMOTED", "CONFIGURATION_ROLLOUT_REQUESTED", "CONFIGURATION_ROLLOUT_CANCELLED",
      "CONFIGURATION_RULE_CREATED", "CONFIGURATION_RULE_UPDATED", "CONFIGURATION_RULE_ENABLED",
      "CONFIGURATION_RULE_DISABLED", "CONFIGURATION_RULE_ARCHIVED", "CONFIGURATION_RULE_REVISION_PROMOTED",
      "CONFIGURATION_RULE_RESOURCE_EXCLUDED", "CONFIGURATION_RULE_RESOURCE_INCLUDED",
      "CONFIGURATION_RULE_RECONCILE_REQUESTED", "CONFIGURATION_ASSIGNMENT_ADOPTED",
      "CONFIGURATION_ASSIGNMENT_DETACHED", "RESOURCE_LABELS_UPDATED",
      "SERVER_PROFILE_CREATED", "SERVER_PROFILE_REVISION_CREATED", "SERVER_PROFILE_ASSIGNED",
      "SERVER_PROFILE_UNASSIGNED", "SERVER_PROFILE_ARCHIVED", "SERVER_PROFILE_APPLY_REQUESTED", "REMNAWAVE_NODE_REPLACEMENT_REQUESTED", "REMNAWAVE_PREVIOUS_INSTALLATION_RETIRED", "REMNAWAVE_NODE_ONBOARDING_REQUESTED", "REMNAWAVE_NODE_ONBOARDING_COMPLETED", "REMNAWAVE_NODE_CERTIFICATE_IMPORTED",
      "REMNAWAVE_FLEET_CREATED", "REMNAWAVE_FLEET_UPDATED", "REMNAWAVE_FLEET_ARCHIVED",
      "REMNAWAVE_FLEET_REVISION_CREATED", "REMNAWAVE_FLEET_REVISION_PROMOTED",
      "REMNAWAVE_FLEET_MEMBER_ADDED", "REMNAWAVE_FLEET_MEMBER_REMOVED",
      "REMNAWAVE_FLEET_ROLLOUT_REQUESTED", "REMNAWAVE_FLEET_ROLLOUT_PAUSED", "REMNAWAVE_FLEET_ROLLOUT_RESUMED",
      "REMNAWAVE_FLEET_ROLLOUT_ROLLBACK_REQUESTED", "REMNAWAVE_FLEET_ROLLOUT_COMPLETED", "REMNAWAVE_FLEET_ROLLOUT_FAILED",
      "REMNAWAVE_NODE_RELEASE_TARGET_CHANGED", "REMNAWAVE_FLEET_UPGRADE_REQUESTED", "REMNAWAVE_FLEET_UPGRADE_PAUSED",
      "REMNAWAVE_FLEET_UPGRADE_RESUMED", "REMNAWAVE_FLEET_UPGRADE_ROLLBACK_REQUESTED", "REMNAWAVE_FLEET_UPGRADE_COMPLETED"
    ))
    assertEquals(AuditTargetType.All.map(_.code),
      List("PROJECT", "ENVIRONMENT", "CONNECTION", "MONITOR_RULE", "RESOURCE",
        "NOTIFICATION_CHANNEL", "INTEGRATION", "ACCOUNT", "TERMINAL_SESSION", "CONFIGURATION_PROFILE",
        "CONFIGURATION_ASSIGNMENT", "CONFIGURATION_DEPLOYMENT", "CONFIGURATION_ROLLOUT",
        "CONFIGURATION_ASSIGNMENT_RULE", "SERVER_PROFILE"))

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
