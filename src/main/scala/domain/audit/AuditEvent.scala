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
  case object IntegrationCreated extends AuditAction { override val code: String = "INTEGRATION_CREATED" }
  case object IntegrationUpdated extends AuditAction { override val code: String = "INTEGRATION_UPDATED" }
  case object IntegrationEnabled extends AuditAction { override val code: String = "INTEGRATION_ENABLED" }
  case object IntegrationDisabled extends AuditAction { override val code: String = "INTEGRATION_DISABLED" }
  case object IntegrationDeleted extends AuditAction { override val code: String = "INTEGRATION_DELETED" }
  case object IntegrationRecoveryAbandoned extends AuditAction { override val code: String = "INTEGRATION_RECOVERY_ABANDONED" }
  case object IntegrationTestRequested extends AuditAction { override val code: String = "INTEGRATION_TEST_REQUESTED" }
  case object IntegrationSyncRequested extends AuditAction { override val code: String = "INTEGRATION_SYNC_REQUESTED" }
  case object IntegrationResourceBound extends AuditAction { override val code: String = "INTEGRATION_RESOURCE_BOUND" }
  case object IntegrationResourceUnbound extends AuditAction { override val code: String = "INTEGRATION_RESOURCE_UNBOUND" }
  case object IntegrationActionRequested extends AuditAction { override val code: String = "INTEGRATION_ACTION_REQUESTED" }
  case object IntegrationInventoryArchived extends AuditAction { override val code: String = "INTEGRATION_INVENTORY_ARCHIVED" }
  case object IntegrationManagementModeChanged extends AuditAction { override val code: String = "INTEGRATION_MANAGEMENT_MODE_CHANGED" }
  case object IntegrationDesiredStateSet extends AuditAction { override val code: String = "INTEGRATION_DESIRED_STATE_SET" }
  case object IntegrationDesiredStateRemoved extends AuditAction { override val code: String = "INTEGRATION_DESIRED_STATE_REMOVED" }
  case object IntegrationConfigProfileAdopted extends AuditAction { override val code: String = "INTEGRATION_CONFIG_PROFILE_ADOPTED" }
  case object IntegrationConfigRevisionCreated extends AuditAction { override val code: String = "INTEGRATION_CONFIG_REVISION_CREATED" }
  case object IntegrationConfigDeploymentRequested extends AuditAction { override val code: String = "INTEGRATION_CONFIG_DEPLOYMENT_REQUESTED" }
  case object IntegrationConfigRolloutRequested extends AuditAction { override val code: String = "INTEGRATION_CONFIG_ROLLOUT_REQUESTED" }
  case object IntegrationConfigRolloutCancelled extends AuditAction { override val code: String = "INTEGRATION_CONFIG_ROLLOUT_CANCELLED" }

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
  case object ProvisioningRunRequested extends AuditAction { override val code: String = "PROVISIONING_RUN_REQUESTED" }
  case object ConfigurationDeploymentCancelled extends AuditAction { override val code: String = "CONFIGURATION_DEPLOYMENT_CANCELLED" }
  case object ConfigurationAssignmentsPromoted extends AuditAction { override val code: String = "CONFIGURATION_ASSIGNMENTS_PROMOTED" }
  case object ConfigurationRolloutRequested extends AuditAction { override val code: String = "CONFIGURATION_ROLLOUT_REQUESTED" }
  case object ConfigurationRolloutCancelled extends AuditAction { override val code: String = "CONFIGURATION_ROLLOUT_CANCELLED" }
  /** Stage 22E: rules that keep assignments present on matching nodes, and the labels they select by. */
  case object ConfigurationRuleCreated extends AuditAction { override val code: String = "CONFIGURATION_RULE_CREATED" }
  case object ConfigurationRuleUpdated extends AuditAction { override val code: String = "CONFIGURATION_RULE_UPDATED" }
  case object ConfigurationRuleEnabled extends AuditAction { override val code: String = "CONFIGURATION_RULE_ENABLED" }
  case object ConfigurationRuleDisabled extends AuditAction { override val code: String = "CONFIGURATION_RULE_DISABLED" }
  case object ConfigurationRuleArchived extends AuditAction { override val code: String = "CONFIGURATION_RULE_ARCHIVED" }
  case object ConfigurationRuleRevisionPromoted extends AuditAction { override val code: String = "CONFIGURATION_RULE_REVISION_PROMOTED" }
  case object ConfigurationRuleResourceExcluded extends AuditAction { override val code: String = "CONFIGURATION_RULE_RESOURCE_EXCLUDED" }
  case object ConfigurationRuleResourceIncluded extends AuditAction { override val code: String = "CONFIGURATION_RULE_RESOURCE_INCLUDED" }
  case object ConfigurationRuleReconcileRequested extends AuditAction { override val code: String = "CONFIGURATION_RULE_RECONCILE_REQUESTED" }
  case object ConfigurationAssignmentAdopted extends AuditAction { override val code: String = "CONFIGURATION_ASSIGNMENT_ADOPTED" }
  case object ConfigurationAssignmentDetached extends AuditAction { override val code: String = "CONFIGURATION_ASSIGNMENT_DETACHED" }
  case object ResourceLabelsUpdated extends AuditAction { override val code: String = "RESOURCE_LABELS_UPDATED" }
  case object ServerProfileCreated extends AuditAction { val code = "SERVER_PROFILE_CREATED" }
  case object ServerProfileRevisionCreated extends AuditAction { val code = "SERVER_PROFILE_REVISION_CREATED" }
  case object ServerProfileAssigned extends AuditAction { val code = "SERVER_PROFILE_ASSIGNED" }
  case object ServerProfileUnassigned extends AuditAction { val code = "SERVER_PROFILE_UNASSIGNED" }
  case object ServerProfileArchived extends AuditAction { val code = "SERVER_PROFILE_ARCHIVED" }
  case object ServerProfileApplyRequested extends AuditAction { val code = "SERVER_PROFILE_APPLY_REQUESTED" }
  case object RemnawaveNodeReplacementRequested extends AuditAction { val code = "REMNAWAVE_NODE_REPLACEMENT_REQUESTED" }
  case object RemnawavePreviousInstallationRetired extends AuditAction { val code = "REMNAWAVE_PREVIOUS_INSTALLATION_RETIRED" }
  case object RemnawaveNodeOnboardingRequested extends AuditAction { val code = "REMNAWAVE_NODE_ONBOARDING_REQUESTED" }
  case object RemnawaveNodeOnboardingCompleted extends AuditAction { val code = "REMNAWAVE_NODE_ONBOARDING_COMPLETED" }
  case object RemnawaveNodeCertificateImported extends AuditAction { val code = "REMNAWAVE_NODE_CERTIFICATE_IMPORTED" }
  case object RemnawaveFleetCreated extends AuditAction { val code = "REMNAWAVE_FLEET_CREATED" }
  case object RemnawaveFleetUpdated extends AuditAction { val code = "REMNAWAVE_FLEET_UPDATED" }
  case object RemnawaveFleetArchived extends AuditAction { val code = "REMNAWAVE_FLEET_ARCHIVED" }
  case object RemnawaveFleetRevisionCreated extends AuditAction { val code = "REMNAWAVE_FLEET_REVISION_CREATED" }
  case object RemnawaveFleetRevisionPromoted extends AuditAction { val code = "REMNAWAVE_FLEET_REVISION_PROMOTED" }
  case object RemnawaveFleetMemberAdded extends AuditAction { val code = "REMNAWAVE_FLEET_MEMBER_ADDED" }
  case object RemnawaveFleetMemberRemoved extends AuditAction { val code = "REMNAWAVE_FLEET_MEMBER_REMOVED" }
  case object RemnawaveFleetRolloutRequested extends AuditAction { val code = "REMNAWAVE_FLEET_ROLLOUT_REQUESTED" }
  case object RemnawaveFleetRolloutPaused extends AuditAction { val code = "REMNAWAVE_FLEET_ROLLOUT_PAUSED" }
  case object RemnawaveFleetRolloutResumed extends AuditAction { val code = "REMNAWAVE_FLEET_ROLLOUT_RESUMED" }
  case object RemnawaveFleetRolloutRollbackRequested extends AuditAction { val code = "REMNAWAVE_FLEET_ROLLOUT_ROLLBACK_REQUESTED" }
  case object RemnawaveFleetRolloutCompleted extends AuditAction { val code = "REMNAWAVE_FLEET_ROLLOUT_COMPLETED" }
  case object RemnawaveNodeReleaseTargetChanged extends AuditAction { val code = "REMNAWAVE_NODE_RELEASE_TARGET_CHANGED" }
  case object RemnawaveFleetUpgradeRequested extends AuditAction { val code = "REMNAWAVE_FLEET_UPGRADE_REQUESTED" }
  case object RemnawaveFleetUpgradePaused extends AuditAction { val code = "REMNAWAVE_FLEET_UPGRADE_PAUSED" }
  case object RemnawaveFleetUpgradeResumed extends AuditAction { val code = "REMNAWAVE_FLEET_UPGRADE_RESUMED" }
  case object RemnawaveFleetUpgradeRollbackRequested extends AuditAction { val code = "REMNAWAVE_FLEET_UPGRADE_ROLLBACK_REQUESTED" }
  case object RemnawaveFleetUpgradeCompleted extends AuditAction { val code = "REMNAWAVE_FLEET_UPGRADE_COMPLETED" }
  case object RemnawaveFleetRolloutFailed extends AuditAction { val code = "REMNAWAVE_FLEET_ROLLOUT_FAILED" }

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
    IntegrationCreated,
    IntegrationUpdated,
    IntegrationEnabled,
    IntegrationDisabled,
    IntegrationDeleted,
    IntegrationRecoveryAbandoned,
    IntegrationTestRequested,
    IntegrationSyncRequested,
    IntegrationResourceBound,
    IntegrationResourceUnbound,
    IntegrationActionRequested,
    IntegrationInventoryArchived,
    IntegrationManagementModeChanged,
    IntegrationDesiredStateSet,
    IntegrationDesiredStateRemoved,
    IntegrationConfigProfileAdopted,
    IntegrationConfigRevisionCreated,
    IntegrationConfigDeploymentRequested,
    IntegrationConfigRolloutRequested,
    IntegrationConfigRolloutCancelled,
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
    ProvisioningRunRequested,
    ConfigurationDeploymentCancelled,
    ConfigurationAssignmentsPromoted,
    ConfigurationRolloutRequested,
    ConfigurationRolloutCancelled,
    ConfigurationRuleCreated,
    ConfigurationRuleUpdated,
    ConfigurationRuleEnabled,
    ConfigurationRuleDisabled,
    ConfigurationRuleArchived,
    ConfigurationRuleRevisionPromoted,
    ConfigurationRuleResourceExcluded,
    ConfigurationRuleResourceIncluded,
    ConfigurationRuleReconcileRequested,
    ConfigurationAssignmentAdopted,
    ConfigurationAssignmentDetached,
    ResourceLabelsUpdated,
    ServerProfileCreated,
    ServerProfileRevisionCreated,
    ServerProfileAssigned,
    ServerProfileUnassigned,
    ServerProfileArchived,
    ServerProfileApplyRequested,
    RemnawaveNodeReplacementRequested,
    RemnawavePreviousInstallationRetired,
    RemnawaveNodeOnboardingRequested,
    RemnawaveNodeOnboardingCompleted,
    RemnawaveNodeCertificateImported,
    RemnawaveFleetCreated,
    RemnawaveFleetUpdated,
    RemnawaveFleetArchived,
    RemnawaveFleetRevisionCreated,
    RemnawaveFleetRevisionPromoted,
    RemnawaveFleetMemberAdded,
    RemnawaveFleetMemberRemoved,
    RemnawaveFleetRolloutRequested,
    RemnawaveFleetRolloutPaused,
    RemnawaveFleetRolloutResumed,
    RemnawaveFleetRolloutRollbackRequested,
    RemnawaveFleetRolloutCompleted,
    RemnawaveFleetRolloutFailed,
    RemnawaveNodeReleaseTargetChanged, RemnawaveFleetUpgradeRequested, RemnawaveFleetUpgradePaused,
    RemnawaveFleetUpgradeResumed, RemnawaveFleetUpgradeRollbackRequested, RemnawaveFleetUpgradeCompleted
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
  case object Integration extends AuditTargetType { override val code: String = "INTEGRATION" }
  /** The actor's own user account: the target of a self-service account change. */
  case object Account extends AuditTargetType { override val code: String = "ACCOUNT" }
  case object ConfigurationProfile extends AuditTargetType { override val code: String = "CONFIGURATION_PROFILE" }
  case object ConfigurationAssignment extends AuditTargetType { override val code: String = "CONFIGURATION_ASSIGNMENT" }
  case object ConfigurationDeployment extends AuditTargetType { override val code: String = "CONFIGURATION_DEPLOYMENT" }
  case object ConfigurationRollout extends AuditTargetType { override val code: String = "CONFIGURATION_ROLLOUT" }
  case object ConfigurationAssignmentRule extends AuditTargetType { override val code: String = "CONFIGURATION_ASSIGNMENT_RULE" }
  case object ServerProfile extends AuditTargetType { val code = "SERVER_PROFILE" }

  val All: List[AuditTargetType] =
    List(Project, Environment, Connection, MonitorRule, Resource, NotificationChannel, Integration, Account, TerminalSession,
      ConfigurationProfile, ConfigurationAssignment, ConfigurationDeployment, ConfigurationRollout,
      ConfigurationAssignmentRule, ServerProfile)

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
