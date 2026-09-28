package ru.bitec.app.ops
package domain.audit

import java.time.Instant
import java.util.UUID

/** What a user did to the configuration of an organization. */
sealed trait AuditAction {
  def code: String
}

object AuditAction {
  case object TerminalSessionOpened extends AuditAction { val code = "TERMINAL_SESSION_OPENED" }
  case object TerminalSessionClosed extends AuditAction { val code = "TERMINAL_SESSION_CLOSED" }

  case object ProjectCreated extends AuditAction { override val code: String = "PROJECT_CREATED" }
  case object EnvironmentCreated extends AuditAction { override val code: String = "ENVIRONMENT_CREATED" }
  case object ConnectionCreated extends AuditAction { override val code: String = "CONNECTION_CREATED" }
  case object ConnectionUpdated extends AuditAction { override val code: String = "CONNECTION_UPDATED" }
  case object ConnectionDeleted extends AuditAction { override val code: String = "CONNECTION_DELETED" }
  case object MonitorRuleCreated extends AuditAction { override val code: String = "MONITOR_RULE_CREATED" }
  case object MonitorRuleUpdated extends AuditAction { override val code: String = "MONITOR_RULE_UPDATED" }

  /** The user asked for a synchronization; whether it succeeded is sync session history. */
  case object ManualSyncRequested extends AuditAction { override val code: String = "MANUAL_SYNC_REQUESTED" }
  case object ContainerStartRequested extends AuditAction { override val code: String = "CONTAINER_START_REQUESTED" }
  case object ContainerStopRequested extends AuditAction { override val code: String = "CONTAINER_STOP_REQUESTED" }
  case object ContainerRestartRequested extends AuditAction { override val code: String = "CONTAINER_RESTART_REQUESTED" }

  case object NotificationChannelCreated extends AuditAction { override val code: String = "NOTIFICATION_CHANNEL_CREATED" }
  case object NotificationChannelUpdated extends AuditAction { override val code: String = "NOTIFICATION_CHANNEL_UPDATED" }
  case object NotificationChannelEnabled extends AuditAction { override val code: String = "NOTIFICATION_CHANNEL_ENABLED" }
  case object NotificationChannelDisabled extends AuditAction { override val code: String = "NOTIFICATION_CHANNEL_DISABLED" }

  /** A user changed their own password. The journal records that it happened, never the secret. */
  case object AccountPasswordChanged extends AuditAction { override val code: String = "ACCOUNT_PASSWORD_CHANGED" }
  /** A user changed their own profile, currently the display name only. */
  case object AccountProfileUpdated extends AuditAction { override val code: String = "ACCOUNT_PROFILE_UPDATED" }
  /** A user revoked one of their own sessions. */
  case object AccountSessionRevoked extends AuditAction { override val code: String = "ACCOUNT_SESSION_REVOKED" }
  /** A user revoked every session but the current one. */
  case object AccountOtherSessionsRevoked extends AuditAction { override val code: String = "ACCOUNT_OTHER_SESSIONS_REVOKED" }
  /** A user revoked every session, the current one included. */
  case object AccountAllSessionsRevoked extends AuditAction { override val code: String = "ACCOUNT_ALL_SESSIONS_REVOKED" }

  /** A profile was created; its first revision is journalled as its own event in the same commit. */
  case object ConfigurationProfileCreated extends AuditAction { override val code: String = "CONFIGURATION_PROFILE_CREATED" }
  /** Name or description changed. Content changes are revisions, never this. */
  case object ConfigurationProfileUpdated extends AuditAction { override val code: String = "CONFIGURATION_PROFILE_UPDATED" }
  case object ConfigurationProfileArchived extends AuditAction { override val code: String = "CONFIGURATION_PROFILE_ARCHIVED" }
  /** A new immutable revision of a profile. The target is the profile; the journal keeps no content. */
  case object ConfigurationRevisionCreated extends AuditAction { override val code: String = "CONFIGURATION_REVISION_CREATED" }
  /** Desired configuration was assigned to a resource. The journal keeps identifiers, never values. */
  case object ConfigurationAssignmentCreated extends AuditAction { override val code: String = "CONFIGURATION_ASSIGNMENT_CREATED" }
  case object ConfigurationAssignmentUpdated extends AuditAction { override val code: String = "CONFIGURATION_ASSIGNMENT_UPDATED" }
  /** Removed from InfraDesk's desired state; nothing on the server is touched. */
  case object ConfigurationAssignmentRemoved extends AuditAction { override val code: String = "CONFIGURATION_ASSIGNMENT_REMOVED" }
  case object ConfigurationDeploymentRequested extends AuditAction { override val code: String = "CONFIGURATION_DEPLOYMENT_REQUESTED" }
  case object ConfigurationDeploymentCancelled extends AuditAction { override val code: String = "CONFIGURATION_DEPLOYMENT_CANCELLED" }

  val All: List[AuditAction] = List(
    ProjectCreated,
    EnvironmentCreated,
    ConnectionCreated,
    ConnectionUpdated,
    ConnectionDeleted,
    MonitorRuleCreated,
    MonitorRuleUpdated,
    ManualSyncRequested,
    ContainerStartRequested,
    ContainerStopRequested,
    ContainerRestartRequested,
    NotificationChannelCreated,
    NotificationChannelUpdated,
    NotificationChannelEnabled,
    NotificationChannelDisabled,
    AccountPasswordChanged,
    AccountProfileUpdated,
    AccountSessionRevoked,
    AccountOtherSessionsRevoked,
    AccountAllSessionsRevoked,
    TerminalSessionOpened,
    TerminalSessionClosed,
    ConfigurationProfileCreated,
    ConfigurationProfileUpdated,
    ConfigurationProfileArchived,
    ConfigurationRevisionCreated,
    ConfigurationAssignmentCreated,
    ConfigurationAssignmentUpdated,
    ConfigurationAssignmentRemoved,
    ConfigurationDeploymentRequested,
    ConfigurationDeploymentCancelled
  )

  def fromCode(code: String): Either[IllegalArgumentException, AuditAction] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported audit action '$code'"))
}

/** The kind of object an action was applied to. */
sealed trait AuditTargetType {
  def code: String
}

object AuditTargetType {
  case object TerminalSession extends AuditTargetType { val code = "TERMINAL_SESSION" }

  case object Project extends AuditTargetType { override val code: String = "PROJECT" }
  case object Environment extends AuditTargetType { override val code: String = "ENVIRONMENT" }
  case object Connection extends AuditTargetType { override val code: String = "CONNECTION" }
  case object MonitorRule extends AuditTargetType { override val code: String = "MONITOR_RULE" }
  case object Resource extends AuditTargetType { override val code: String = "RESOURCE" }
  case object NotificationChannel extends AuditTargetType { override val code: String = "NOTIFICATION_CHANNEL" }
  /** The actor's own user account: the target of a self-service account change. */
  case object Account extends AuditTargetType { override val code: String = "ACCOUNT" }
  case object ConfigurationProfile extends AuditTargetType { override val code: String = "CONFIGURATION_PROFILE" }
  case object ConfigurationAssignment extends AuditTargetType { override val code: String = "CONFIGURATION_ASSIGNMENT" }
  case object ConfigurationDeployment extends AuditTargetType { override val code: String = "CONFIGURATION_DEPLOYMENT" }

  val All: List[AuditTargetType] =
    List(Project, Environment, Connection, MonitorRule, Resource, NotificationChannel, Account, TerminalSession,
      ConfigurationProfile, ConfigurationAssignment, ConfigurationDeployment)

  def fromCode(code: String): Either[IllegalArgumentException, AuditTargetType] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported audit target type '$code'"))
}

/** One journal entry: who did what, to which object, and when.
  *
  * The entry deliberately carries identifiers only. Old and new values, request bodies,
  * configuration and secrets of any kind stay out of the journal.
  */
final case class AuditEvent(
  id: UUID,
  organizationId: UUID,
  actorUserId: UUID,
  action: AuditAction,
  targetType: AuditTargetType,
  targetId: Option[UUID],
  occurredAt: Instant,
  createdAt: Instant
)

/** Where a page of the journal continues: the last row the previous page returned. */
final case class AuditCursor(occurredAt: Instant, id: UUID)
