package ru.bitec.app.ops
package bootstrap

import application.account.{
  AccountAudit,
  ChangePassword,
  ListUserSessions,
  RevokeAllUserSessions,
  RevokeOtherUserSessions,
  RevokeUserSession,
  UpdateAccountProfile
}
import application.audit.{AuditRecorder, ListAuditEvents}
import application.history.{
  HistoryRecorder,
  HistoryRecordingMonitorRuleEvaluator,
  ListHistoryEvents
}
import application.auth.{Authentication, BCryptPasswordHasher, BootstrapAdmin, CleanupLoginThrottle, CleanupSecurityEvents, ListSecurityEvents, Login, LoginThrottleHasher, SessionTokens}
import application.connection.{
  GetConnection,
  GetConnectionSyncSession,
  ListConnectionSyncSessions,
  ListConnections,
  OpenSshTerminal,
  RunManualConnectionSync,
  SshConnectionManagement
}
import application.connector.{RunConnectionSync, SyncConnection, SyncConnectionById}
import application.discovery.{CreateDiscoveredResource, ReconcileDiscoveredResource, SyncDiscoveredSnapshot}
import application.configuration.{
  ConfigurationAssignmentQueries,
  ConfigurationAssignments,
  ConfigurationDeployments,
  ConfigurationDeploymentWorker,
  ConfigurationPromotions,
  ConfigurationRollouts,
  ConfigurationRolloutWorker,
  ConfigurationProfileManagement,
  ConfigurationProfileQueries
}
import application.provisioning.{ProvisioningPlanCleanup, ProvisioningRuns, ProvisioningSettings, ProvisioningWorker, ServerProfiles}
import application.context.{
  GetConnectionInfrastructureSummary,
  GetResourceContext,
  ListConnectionInfrastructureCounts,
  ListConnectionResources,
  ListEnvironmentResourceSources
}
import application.incident.{GetIncidentDetail, ListConnectionIncidents, ListIncidents, ListResourceIncidents}
import application.integration.{IntegrationBindings, IntegrationManagement, IntegrationSync,
  IntegrationSyncScheduler, IntegrationSyncSchedulerSettings, IntegrationSyncTransactions, TestIntegration,
  IntegrationActions, IntegrationActionWorker, IntegrationDesiredStates, IntegrationDesiredStateSettings,
  IntegrationDesiredStateWorker}
import application.overview.GetOperationsOverview
import application.monitor.{CreateMonitorRule, EvaluateMonitorRules, ListMonitorRules, UpdateMonitorRule}
import application.notification.{
  GetNotificationChannel,
  ManagedNotificationSender,
  ListNotificationChannels,
  NotificationChannelManagement,
  NotificationDispatcher,
  NotificationRecordingMonitorRuleEvaluator,
  RecordNotificationDeliveries,
  TestNotificationChannel
}
import application.port.NotificationSender
import application.navigation.{GetEnvironmentContext, GetOrganization, ListEnvironments, ListProjects}
import application.resource.{
  GetResource,
  GetResourceMetricHistory,
  ListEnvironmentResources,
  PersistExternalResource,
  RecordResourceObservations
}
import application.operation.{ExecuteResourceOperation, ListResourceOperationExecutions, ResourceOperationPreparation}
import application.scheduler.SyncScheduler
import application.port.NotificationDeliveryScope
import domain.notification.NotificationDeliveryTarget
import application.workspace.{CreateEnvironment, CreateProject}
import cats.effect.IO
import cats.syntax.traverse._
import infrastructure.config.AppConfig
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider}
import infrastructure.runtime.{SystemIdGenerator, SystemTimeProvider}
import org.typelevel.doobie.ConnectionIO

import java.util.UUID

/** Application use cases, ready to be consumed by HTTP routes and by the runtime lifecycle.
  *
  * This record exists for assembly only. Routes and the scheduler receive the individual use
  * cases they need through their constructors, never this record.
  */
final case class ApplicationComponents(
  administration: application.administration.Administration[ConnectionIO],
  terminalSessionLifecycle: application.terminal.TerminalSessionLifecycle[ConnectionIO],
  getResource: GetResource[ConnectionIO],
  listEnvironmentResources: ListEnvironmentResources[ConnectionIO],
  getResourceMetricHistory: GetResourceMetricHistory[ConnectionIO],
  getIncident: GetIncidentDetail[ConnectionIO],
  getConnectionInfrastructureSummary: GetConnectionInfrastructureSummary[ConnectionIO],
  listConnectionResources: ListConnectionResources[ConnectionIO],
  listConnectionIncidents: ListConnectionIncidents[ConnectionIO],
  listConnectionInfrastructureCounts: ListConnectionInfrastructureCounts[ConnectionIO],
  getResourceContext: GetResourceContext[ConnectionIO],
  listResourceIncidents: ListResourceIncidents[ConnectionIO],
  listEnvironmentResourceSources: ListEnvironmentResourceSources[ConnectionIO],
  configurationProfileQueries: ConfigurationProfileQueries[ConnectionIO],
  configurationProfileManagement: ConfigurationProfileManagement[ConnectionIO],
  configurationAssignments: ConfigurationAssignments[IO, ConnectionIO],
  configurationAssignmentQueries: ConfigurationAssignmentQueries[ConnectionIO],
  configurationDeployments: ConfigurationDeployments[IO, ConnectionIO],
  configurationDeploymentWorker: ConfigurationDeploymentWorker[ConnectionIO],
  provisioningRuns: ProvisioningRuns[IO, ConnectionIO],
  provisioningWorker: ProvisioningWorker[ConnectionIO],
  provisioningPlanCleanup: ProvisioningPlanCleanup[ConnectionIO],
  serverProfiles: ServerProfiles[IO, ConnectionIO],
  remnawaveOnboarding: application.integration.RemnawaveOnboarding[ConnectionIO],
  remnawaveOnboardingWorker: application.integration.RemnawaveOnboardingWorker[ConnectionIO],
  remnawaveFleets: application.integration.RemnawaveFleets[ConnectionIO],
  remnawaveFleetObserver: application.integration.RemnawaveFleetObserver[ConnectionIO],
  remnawaveFleetRollouts: application.integration.RemnawaveFleetRollouts[ConnectionIO],
  remnawaveFleetRolloutWorker: application.integration.RemnawaveFleetRolloutWorker[ConnectionIO],
  remnawaveFleetUpgrades: application.integration.RemnawaveFleetUpgrades[ConnectionIO],
  remnawaveFleetUpgradeWorker: application.integration.RemnawaveFleetUpgradeWorker[ConnectionIO],
  configurationPromotions: ConfigurationPromotions[IO, ConnectionIO],
  configurationRollouts: ConfigurationRollouts[IO, ConnectionIO],
  configurationRolloutWorker: ConfigurationRolloutWorker[ConnectionIO],
  configurationAssignmentRules: application.configuration.ConfigurationAssignmentRules[IO, ConnectionIO],
  configurationAssignmentRuleWorker: application.configuration.ConfigurationAssignmentRuleWorker[ConnectionIO],
  resourceLabels: application.configuration.ResourceLabels[IO, ConnectionIO],
  listIncidents: ListIncidents[ConnectionIO],
  listMonitorRules: ListMonitorRules[ConnectionIO],
  createMonitorRule: CreateMonitorRule[ConnectionIO],
  updateMonitorRule: UpdateMonitorRule[ConnectionIO],
  getConnection: GetConnection[ConnectionIO],
  listConnections: ListConnections[ConnectionIO],
  sshConnectionManagement: SshConnectionManagement[ConnectionIO],
  openSshTerminal: OpenSshTerminal[ConnectionIO],
  listConnectionSyncSessions: ListConnectionSyncSessions[ConnectionIO],
  getConnectionSyncSession: GetConnectionSyncSession[ConnectionIO],
  runManualConnectionSync: RunManualConnectionSync[ConnectionIO],
  getOrganization: GetOrganization[ConnectionIO],
  listProjects: ListProjects[ConnectionIO],
  listEnvironments: ListEnvironments[ConnectionIO],
  getEnvironmentContext: GetEnvironmentContext[ConnectionIO],
  createProject: CreateProject[ConnectionIO],
  createEnvironment: CreateEnvironment[ConnectionIO],
  login: Login[ConnectionIO],
  cleanupLoginThrottle: CleanupLoginThrottle[IO, ConnectionIO],
  cleanupSecurityEvents: CleanupSecurityEvents[IO, ConnectionIO],
  authentication: Authentication[ConnectionIO],
  changePassword: ChangePassword[ConnectionIO],
  updateAccountProfile: UpdateAccountProfile[ConnectionIO],
  listUserSessions: ListUserSessions[ConnectionIO],
  revokeUserSession: RevokeUserSession[ConnectionIO],
  revokeOtherUserSessions: RevokeOtherUserSessions[ConnectionIO],
  revokeAllUserSessions: RevokeAllUserSessions[ConnectionIO],
  listSecurityEvents: ListSecurityEvents[ConnectionIO],
  bootstrapAdmin: BootstrapAdmin[ConnectionIO],
  listAuditEvents: ListAuditEvents[ConnectionIO],
  listNotificationChannels: ListNotificationChannels[ConnectionIO],
  getNotificationChannel: GetNotificationChannel[ConnectionIO],
  notificationChannelManagement: NotificationChannelManagement[ConnectionIO],
  integrationManagement: IntegrationManagement[ConnectionIO],
  testIntegration: TestIntegration[ConnectionIO],
  integrationProviderRegistry: application.integration.IntegrationProviderRegistry[IO],
  integrationSync: IntegrationSync[ConnectionIO],
  integrationActions: IntegrationActions[ConnectionIO],
  integrationActionWorker: IntegrationActionWorker[ConnectionIO],
  integrationActionsEnabled: Boolean,
  integrationDesiredStates: IntegrationDesiredStates[ConnectionIO],
  integrationDesiredStateWorker: IntegrationDesiredStateWorker[ConnectionIO],
  integrationConfigProfiles: application.integration.IntegrationConfigProfiles[ConnectionIO],
  integrationConfigDeploymentWorker: application.integration.IntegrationConfigDeploymentWorker[ConnectionIO],
  integrationConfigRollouts: application.integration.IntegrationConfigRollouts[ConnectionIO],
  integrationConfigRolloutWorker: application.integration.IntegrationConfigRolloutWorker[ConnectionIO],
  integrationBindings: IntegrationBindings[ConnectionIO],
  integrationInventoryMaintenance: application.integration.IntegrationInventoryMaintenance[ConnectionIO],
  integrationSyncScheduler: IntegrationSyncScheduler[ConnectionIO],
  testNotificationChannel: TestNotificationChannel,
  listHistoryEvents: ListHistoryEvents[ConnectionIO],
  getOperationsOverview: GetOperationsOverview[ConnectionIO],
  resourceOperationPreparation: ResourceOperationPreparation[ConnectionIO],
  executeResourceOperation: ExecuteResourceOperation[ConnectionIO],
  listResourceOperationExecutions: ListResourceOperationExecutions[ConnectionIO],
  scheduler: SyncScheduler[IO, ConnectionIO],
  /** Absent when the deployment configures no webhook of its own, so no worker is started for
    * one. Channels are configured in the database, so the managed worker always runs.
    */
  notificationDispatcher: Option[NotificationDispatcher[IO, ConnectionIO]],
  managedNotificationDispatcher: NotificationDispatcher[IO, ConnectionIO]
)

object ApplicationModule {

  import scala.concurrent.duration._

  /** How often the login-throttle table is swept for stale rows. A light background sweep, not a
    * delete on the login path.
    */
  private val LoginThrottleCleanupInterval: FiniteDuration = 1.hour
  private val SecurityEventCleanupInterval: FiniteDuration = 6.hours

  def build(
    config: AppConfig,
    persistence: PersistenceComponents,
    integrations: IntegrationComponents,
    loggers: AppLoggers,
    schedulerInstanceId: UUID,
    transports: NotificationTransports,
    notificationDispatcherInstanceId: UUID = UUID.randomUUID(),
    nodeReleaseVerifier: Option[application.port.RemnawaveNodeReleaseVerifier] = None
  ): ApplicationComponents = {
    import persistence._

    // IO-level primitives for runtime use cases, ConnectionIO-level ones for operations that
    // must generate ids and read the clock inside the transaction they participate in.
    val idGenerator = new SystemIdGenerator
    val timeProvider = new SystemTimeProvider
    val transactionIdGenerator = new ConnectionIOIdGenerator
    val transactionTimeProvider = new ConnectionIOTimeProvider

    // Every mutation use case writes its journal entry through this recorder, inside its own
    // business transaction.
    val auditRecorder =
      new AuditRecorder[ConnectionIO](
        auditEventRepository,
        transactionIdGenerator,
        transactionTimeProvider
      )

    // Journals self-service account changes once per organization the user belongs to.
    val accountAudit = new AccountAudit[ConnectionIO](auditRecorder, membershipRepository)

    // Every durable transition journals its facts through this recorder, inside the transaction
    // that produced them.
    val historyRecorder =
      new HistoryRecorder[ConnectionIO](
        historyEventRepository,
        transactionIdGenerator,
        transactionTimeProvider
      )

    val persistExternalResource =
      new PersistExternalResource[ConnectionIO](resourceRepository, externalRefRepository)

    val createDiscoveredResource =
      new CreateDiscoveredResource[ConnectionIO](
        resourceTypeRepository,
        externalRefRepository,
        persistExternalResource
      )

    val reconcileDiscoveredResource =
      new ReconcileDiscoveredResource[ConnectionIO](resourceRepository, externalRefRepository)

    val recordResourceObservations =
      new RecordResourceObservations[ConnectionIO](metricObservationRepository)

    val syncDiscoveredSnapshot =
      new SyncDiscoveredSnapshot[ConnectionIO](
        createDiscoveredResource,
        reconcileDiscoveredResource,
        externalRefRepository,
        resourceRepository,
        syncSessionRepository,
        recordResourceObservations,
        historyRecorder
      )

    // The webhook a deployment configures through its environment, which predates configured
    // channels and is recorded for every transition regardless of subscriptions. Channels of the
    // organization are found by routing; this is the one target that is not one of them.
    val legacyNotificationTargets: List[NotificationDeliveryTarget] =
      if (transports.legacyWebhookSender.isDefined) List(NotificationDeliveryTarget.LegacyWebhook)
      else Nil

    val recordNotificationDeliveries =
      new RecordNotificationDeliveries[ConnectionIO](
        notificationDeliveryRepository,
        notificationChannelRoutingQuery,
        transactionIdGenerator,
        transactionTimeProvider,
        legacyNotificationTargets
      )

    // The outbox rows are written inside the evaluation transaction, so an incident change and
    // the intent to report it commit together.
    val evaluateMonitorRules =
      new HistoryRecordingMonitorRuleEvaluator[ConnectionIO](
        new NotificationRecordingMonitorRuleEvaluator[ConnectionIO](
          new EvaluateMonitorRules[ConnectionIO](
            monitorEvaluationQuery,
            monitorRuleStateRepository,
            incidentRepository,
            transactionIdGenerator
          ),
          recordNotificationDeliveries
        ),
        historyRecorder
      )

    val syncConnection =
      new SyncConnection[IO, ConnectionIO](
        integrations.connectorRegistry,
        syncDiscoveredSnapshot,
        connectionRepository,
        syncSessionRepository,
        transactionRunner,
        idGenerator,
        timeProvider,
        recordResourceObservations,
        historyRecorder,
        integrations.connectionSyncBudget,
        loggers.sync
      )

    val syncConnectionById =
      new SyncConnectionById[IO, ConnectionIO](connectionRepository, transactionRunner, syncConnection)

    val runConnectionSync =
      new RunConnectionSync[IO, ConnectionIO](
        syncConnectionById,
        evaluateMonitorRules,
        transactionRunner,
        timeProvider,
        loggers.monitor
      )

    // Derived rather than random so that the identity is stable for a given process, and
    // distinct from the legacy worker's in the same process.
    val managedDispatcherInstanceId = new UUID(
      notificationDispatcherInstanceId.getMostSignificantBits,
      notificationDispatcherInstanceId.getLeastSignificantBits ^ 1L)

    // Shared with the test-send endpoint below: the same tenant-safe read, the same decryption,
    // the same transports a real delivery goes through, so a test send is not a second
    // implementation of any of them.
    val managedNotificationSender = new ManagedNotificationSender[IO, ConnectionIO](
      notificationChannelDispatchQuery,
      integrations.notificationChannelCipher,
      transactionRunner,
      transports.webhook,
      transports.telegram,
      transports.email
    )

    val passwordHasher = new BCryptPasswordHasher
    val sessionTokens = new SessionTokens
    // A purpose-separated subkey of the master secret hashes throttle keys, so the throttle table
    // never stores a raw login email or client address.
    val loginThrottleHasher = new LoginThrottleHasher(
      config.secretEncryption.deriveSubkey("infradesk/login-throttle/v1"))
    val resourceOperationPreparation = new ResourceOperationPreparation[ConnectionIO](
      resourceOperationTargetQuery, operationExecutionRepository, transactionIdGenerator,
      transactionTimeProvider, auditRecorder, historyRecorder,
      integrations.resourceOperationBudget)

    // One deployment service: rollouts preflight through exactly the same remote read path.
    val configurationDeployments = new ConfigurationDeployments[IO, ConnectionIO](
      configurationAssignmentRepository, configurationAssignmentQuery, configurationProfileQuery,
      configurationDeploymentSourceQuery, configurationDeploymentRepository,
      integrations.configurationTransport, transactionIdGenerator, transactionTimeProvider,
      auditRecorder, readOnlySnapshotRunner, transactionRunner, config.configurationDeployment)

    val serverProfiles = new ServerProfiles[IO,ConnectionIO](serverProfileRepository,provisioningTargetQuery,
      provisioningRunRepository,integrations.serverProfileRemote,transactionIdGenerator,transactionTimeProvider,
      auditRecorder,readOnlySnapshotRunner,transactionRunner,config.provisioning)
    val serverProfileApplyCoordinator = new application.provisioning.ServerProfileApplyCoordinator[ConnectionIO](
      serverProfileRepository,provisioningRunRepository,provisioningTargetQuery,integrations.serverProfileRemote,readOnlySnapshotRunner,config.provisioning.leaseDuration)
    val provisioningRuns = new ProvisioningRuns[IO, ConnectionIO](provisioningRunRepository,
      provisioningTargetQuery, transactionIdGenerator, transactionTimeProvider, auditRecorder,
      readOnlySnapshotRunner, transactionRunner, config.provisioning,
      Some(serverProfiles))
    val provisioningWorker = new ProvisioningWorker[ConnectionIO](provisioningRunRepository,
      provisioningTargetQuery, integrations.provisioningTransport, transactionRunner,
      config.provisioning, loggers.configuration,profileHandler=Some(serverProfileApplyCoordinator))
    val provisioningPlanCleanup = new ProvisioningPlanCleanup[ConnectionIO](provisioningRunRepository,
      transactionRunner, loggers.configuration)
    val integrationConfigRepository = new ru.bitec.app.ops.persistence.postgres.PostgresIntegrationConfigProfileRepository(
      integration.secret.RemnawaveConfigCipher.fromConfig(config.secretEncryption))
    val integrationConfigDeployments = new ru.bitec.app.ops.persistence.postgres.PostgresIntegrationConfigDeploymentRepository
    val integrationConfigRollouts = new ru.bitec.app.ops.persistence.postgres.PostgresIntegrationConfigRolloutRepository
    val integrationManagement = new IntegrationManagement[ConnectionIO](
      integrationRepository, integrationSecretRepository, transactionIdGenerator,
      transactionTimeProvider, integrations.integrationCredentialCipher, auditRecorder,
      integrationSyncStateRepository, integrationActionRepository, integrationInventoryRepository,
      integrationConfigRepository, integrationConfigRollouts)
    val integrationSyncSettings = config.integrations.sync
    val integrationSyncTransactions = new IntegrationSyncTransactions[ConnectionIO](integrationRepository,
      integrationSecretRepository, integrationSyncSessionRepository, integrationInventoryRepository,
      transactionIdGenerator, transactionTimeProvider, auditRecorder, integrationDesiredStateRepository)
    val integrationSync = new IntegrationSync[ConnectionIO](integrationSyncTransactions,
      transactionRunner, integrations.integrationCredentialCipher, integrations.integrationProviderRegistry,
      loggers.integration, integrationSyncSettings.attemptTimeout, config.integrations.inventoryMaxObjects)
    val integrationActions = new IntegrationActions[ConnectionIO](integrationRepository,
      integrationInventoryRepository, integrationActionRepository, integrationSecretRepository,
      integrations.integrationProviderRegistry, transactionIdGenerator, transactionTimeProvider, auditRecorder,
      integrationDesiredStateRepository)
    val desiredStateSettings = config.integrations.desiredState
    val actionSettings = config.integrations.actions
    val integrationActionWorker = new IntegrationActionWorker[ConnectionIO](integrationActionRepository,
      integrationRepository, integrationSecretRepository, integrations.integrationCredentialCipher,
      integrations.integrationProviderRegistry, transactionRunner, timeProvider, loggers.integration,
      actionSettings.pollInterval, actionSettings.batchSize, actionSettings.maxConcurrency,
      config.integrations.requestTimeout, UUID.randomUUID())

    val integrationConfigProfiles = new application.integration.IntegrationConfigProfiles[ConnectionIO](
      integrationRepository, integrationInventoryRepository, integrationSecretRepository,
      integrations.integrationCredentialCipher, integrations.integrationProviderRegistry,
      configurationProfileRepository, configurationProfileQuery, integrationConfigRepository,
      integrationConfigDeployments, integrationConfigRollouts, transactionIdGenerator, transactionTimeProvider, auditRecorder,
      transactionRunner, integrationSyncStateRepository, loggers.integration)
    val integrationConfigDeploymentWorker = new application.integration.IntegrationConfigDeploymentWorker[ConnectionIO](
      integrationConfigDeployments, integrationConfigRepository, integrationRepository,
      integrationInventoryRepository, integrationSecretRepository, integrations.integrationCredentialCipher,
      integrations.integrationProviderRegistry, integrationSyncStateRepository, transactionRunner,
      timeProvider, loggers.integration, actionSettings.pollInterval, actionSettings.batchSize,
      actionSettings.maxConcurrency, config.integrations.requestTimeout, UUID.randomUUID())
    val integrationConfigRolloutService = new application.integration.IntegrationConfigRollouts[ConnectionIO](
      integrationRepository, integrationInventoryRepository, configurationProfileQuery,
      integrationConfigRepository, integrationConfigDeployments, integrationConfigRollouts, transactionIdGenerator,
      transactionTimeProvider, auditRecorder, integrationSyncStateRepository, transactionRunner,
      config.integrations.configRolloutsOperational)
    val rolloutSettings = config.integrations.configRollouts
    val integrationConfigRolloutWorker = new application.integration.IntegrationConfigRolloutWorker[ConnectionIO](
      integrationConfigRollouts, integrationRepository, integrationSyncStateRepository,
      transactionRunner, timeProvider, loggers.integration, rolloutSettings.pollInterval,
      rolloutSettings.batchSize, rolloutSettings.maxConcurrency, rolloutSettings.claimLease,
      rolloutSettings.verifyTimeout, UUID.randomUUID())

    val integrationBindingsService = new IntegrationBindings[ConnectionIO](integrationRepository,
      integrationInventoryRepository,integrationBindingRepository,transactionIdGenerator,transactionTimeProvider,auditRecorder)
    val integrationDesiredStatesService = new IntegrationDesiredStates[ConnectionIO](integrationRepository,
      integrationInventoryRepository,integrationDesiredStateRepository,integrations.integrationProviderRegistry,
      transactionIdGenerator,transactionTimeProvider,auditRecorder,config.integrations.desiredStateOperational)
    val onboardingRepository = new ru.bitec.app.ops.persistence.postgres.PostgresRemnawaveOnboardingRepository
    val onboardingQuery = new ru.bitec.app.ops.persistence.postgres.PostgresRemnawaveOnboardingQuery
    val onboardingRemote = new integration.ssh.SshRemnawaveNodeRemote(integrations.configurationTransport,config.integrations.allowPrivateDestinations)
    val onboardingOperations = new application.integration.ExistingRemnawaveOnboardingOperations[ConnectionIO](
      onboardingRepository,onboardingQuery,integrationRepository,integrationSecretRepository,integrations.integrationCredentialCipher,
      provisioningTargetQuery,serverProfileRepository,provisioningRunRepository,integrations.serverProfileRemote,
      provisioningRuns,integrationSync,integrationSyncTransactions,integrationSyncSessionRepository,
      integrationInventoryRepository,integrationBindingRepository,integrationBindingsService,
      integrationDesiredStateRepository,integrationDesiredStatesService,transactionRunner)
    val panelSourceResolver = ru.bitec.app.ops.integration.remnawave.RemnawavePanelDnsResolver.production(config.integrations.allowPrivateDestinations)
    val remnawaveOnboarding = new application.integration.RemnawaveOnboarding[ConnectionIO](onboardingRepository,onboardingQuery,
      integrationRepository,integrationSecretRepository,integrations.integrationCredentialCipher,integrations.integrationProviderRegistry,
      provisioningTargetQuery,serverProfiles,integrationInventoryQuery,transactionRunner,onboardingRemote,auditRecorder,
      config.provisioning,provisioningRunRepository,panelSourceResolver,
      nodeCipher=Some(integration.secret.NodeInstallationCipher.fromConfig(config.secretEncryption)))
    val fleetRepository = new ru.bitec.app.ops.persistence.postgres.PostgresRemnawaveFleetRepository
    val fleetQuery = new ru.bitec.app.ops.persistence.postgres.PostgresRemnawaveFleetQuery
    val upgradeRepository = new ru.bitec.app.ops.persistence.postgres.PostgresRemnawaveFleetUpgradeRepository
    val fleetConfig = config.integrations.fleets
    val remnawaveFleets = new application.integration.RemnawaveFleets[ConnectionIO](fleetRepository, fleetQuery,
      integrationRepository, integrationInventoryRepository, integrationBindingRepository,
      integrationConfigRepository, configurationProfileQuery, serverProfileRepository,
      integrationSyncStateRepository, auditRecorder, transactionRunner,
      application.integration.FleetSettings(fleetConfig.staleAfter, fleetConfig.recheckInterval))
    // Read-only: the observer gets the node transport for observation and no mutation service at all.
    val remnawaveFleetObserver = new application.integration.RemnawaveFleetObserver[ConnectionIO](fleetRepository,
      fleetQuery, provisioningTargetQuery, onboardingRemote, transactionRunner, loggers.integration,
      application.integration.RemnawaveFleetObserverSettings(fleetConfig.enabled, fleetConfig.pollInterval,
        fleetConfig.batchSize, fleetConfig.maxConcurrency, fleetConfig.claimLease,
        fleetConfig.observationTimeout, fleetConfig.recheckInterval, fleetConfig.staleAfter),
      UUID.randomUUID(), imageLifecycle = Some(onboardingRemote -> upgradeRepository))
    val rolloutRepository = new ru.bitec.app.ops.persistence.postgres.PostgresRemnawaveFleetRolloutRepository
    def ownedRolloutChildren(id: UUID, token: UUID): application.integration.FleetRolloutChildren = {
      val childRunner = new ru.bitec.app.ops.persistence.postgres.PostgresFleetRolloutChildRunner(transactionRunner, id, token)
      val childProfiles = new ServerProfiles[IO,ConnectionIO](serverProfileRepository, provisioningTargetQuery,
        provisioningRunRepository, integrations.serverProfileRemote, transactionIdGenerator, transactionTimeProvider,
        auditRecorder, childRunner, childRunner, config.provisioning)
      val childProvisioning = new ProvisioningRuns[IO,ConnectionIO](provisioningRunRepository,
        provisioningTargetQuery, transactionIdGenerator, transactionTimeProvider, auditRecorder,
        childRunner, childRunner, config.provisioning, Some(childProfiles))
      val childConfigs = new application.integration.IntegrationConfigRollouts[ConnectionIO](integrationRepository,
        integrationInventoryRepository, configurationProfileQuery, integrationConfigRepository,
        integrationConfigDeployments, integrationConfigRollouts, transactionIdGenerator, transactionTimeProvider,
        auditRecorder, integrationSyncStateRepository, childRunner, config.integrations.configRolloutsOperational)
      new application.integration.LiveFleetRolloutChildren[ConnectionIO](childProfiles, childProvisioning,
        childConfigs, integrationDesiredStatesService, integrationDesiredStateRepository, serverProfileRepository,
        provisioningTargetQuery, fleetQuery, fleetRepository, integrationSyncStateRepository, onboardingRemote, childRunner)
    }
    val rolloutChildren = new application.integration.LiveFleetRolloutChildren[ConnectionIO](serverProfiles,
      provisioningRuns, integrationConfigRolloutService, integrationDesiredStatesService,
      integrationDesiredStateRepository, serverProfileRepository, provisioningTargetQuery, fleetQuery,
      fleetRepository, integrationSyncStateRepository, onboardingRemote, transactionRunner, Some(ownedRolloutChildren))
    val remnawaveFleetRollouts = new application.integration.RemnawaveFleetRollouts[ConnectionIO](fleetRepository,
      fleetQuery, rolloutRepository, integrationRepository, integrationInventoryRepository,
      integrationBindingRepository, rolloutChildren, auditRecorder, transactionRunner,
      application.integration.FleetRolloutSettings(24.hours, fleetConfig.staleAfter, 25),
      Some(remnawaveFleets.pinnedContentAvailable),
      Some((org, members) => members.filter(m => m.actions.exists(a =>
        a == domain.integration.FleetActionKind.ServerProfileApply || a == domain.integration.FleetActionKind.NetworkFirewall))
        .traverse(m => provisioningTargetQuery.eligible(org, m.resourceId).map(_.exists(t =>
          m.baseline.sourceConnectionId.contains(t.connectionId) && m.baseline.sourceUpdatedAt.contains(t.connectionUpdatedAt))))
        .map(_.forall(identity))))
    val remnawaveFleetRolloutWorker = new application.integration.RemnawaveFleetRolloutWorker[ConnectionIO](
      rolloutRepository, fleetQuery, rolloutChildren,
      new application.integration.FleetRolloutMemberRunner[ConnectionIO](rolloutRepository, rolloutChildren,
        transactionRunner, application.integration.FleetMemberRunnerSettings()),
      remnawaveFleetRollouts, transactionRunner, loggers.integration,
      application.integration.RemnawaveFleetRolloutWorkerSettings(enabled = fleetConfig.enabled),
      UUID.randomUUID())
    val remnawaveOnboardingWorker = new application.integration.RemnawaveOnboardingWorker[ConnectionIO](onboardingRepository,
      onboardingOperations,integrations.integrationProviderRegistry,onboardingRemote,
      integration.secret.NodeInstallationCipher.fromConfig(config.secretEncryption),transactionRunner,auditRecorder,
      config.provisioning,loggers.integration,panelSources=panelSourceResolver,
      panelSourcePolicy=Some(new application.integration.OnboardingPanelSources(onboardingQuery,transactionRunner,panelSourceResolver)))
    val remnawaveFleetUpgrades = new application.integration.RemnawaveFleetUpgrades[ConnectionIO](fleetRepository,
      fleetQuery, upgradeRepository, integrationRepository, integrationSecretRepository, integrations.integrationCredentialCipher,
      integrations.integrationProviderRegistry, provisioningTargetQuery, auditRecorder, transactionRunner,
      application.integration.NodeUpgradeSettings(enabled = fleetConfig.enabled && config.provisioning.enabled, staleAfter = fleetConfig.staleAfter))
    val remnawaveFleetUpgradeWorker = new application.integration.RemnawaveFleetUpgradeWorker[ConnectionIO](upgradeRepository,
      remnawaveFleetUpgrades, onboardingRemote, nodeReleaseVerifier.getOrElse(new application.port.RemnawaveNodeReleaseVerifier {
        def verify(release: domain.integration.NodeRelease, platform: domain.integration.NodeReleasePlatform): IO[Unit] =
          IO.raiseError(application.port.NodeImageRemoteFailure("NODE_RELEASE_ARTIFACT_UNCONFIRMED"))
      }), rolloutChildren.refresh, transactionRunner, loggers.integration)

    ApplicationComponents(
      getResource = GetResource[ConnectionIO](resourceRepository),
      listEnvironmentResources = ListEnvironmentResources[ConnectionIO](resourceRepository),
      getResourceMetricHistory =
        GetResourceMetricHistory[ConnectionIO](resourceRepository, metricObservationRepository),
      getIncident = GetIncidentDetail[ConnectionIO](incidentListQuery),
      getConnectionInfrastructureSummary =
        GetConnectionInfrastructureSummary[ConnectionIO](infrastructureContextQuery, incidentListQuery),
      listConnectionResources = ListConnectionResources[ConnectionIO](infrastructureContextQuery),
      listConnectionIncidents = ListConnectionIncidents[ConnectionIO](infrastructureContextQuery, incidentListQuery),
      listConnectionInfrastructureCounts = ListConnectionInfrastructureCounts[ConnectionIO](infrastructureContextQuery),
      getResourceContext = GetResourceContext[ConnectionIO](infrastructureContextQuery),
      listResourceIncidents = ListResourceIncidents[ConnectionIO](infrastructureContextQuery, incidentListQuery),
      listEnvironmentResourceSources = ListEnvironmentResourceSources[ConnectionIO](infrastructureContextQuery),
      configurationProfileQueries = new ConfigurationProfileQueries[ConnectionIO](configurationProfileQuery),
      // What an assignment depends on is read in one snapshot; its change is one short write.
      configurationAssignments = new ConfigurationAssignments[IO, ConnectionIO](configurationAssignmentRepository,
        configurationTargetQuery, configurationProfileQuery, transactionIdGenerator, transactionTimeProvider,
        auditRecorder, readOnlySnapshotRunner, transactionRunner),
      configurationAssignmentQueries =
        new ConfigurationAssignmentQueries[ConnectionIO](configurationAssignmentQuery, configurationProfileQuery),
      configurationDeployments = configurationDeployments,
      configurationDeploymentWorker = new ConfigurationDeploymentWorker[ConnectionIO](
        configurationDeploymentRepository, configurationAssignmentRepository,
        configurationProfileQuery, configurationDeploymentSourceQuery, integrations.configurationTransport,
        transactionRunner, schedulerInstanceId, config.configurationDeployment, loggers.configuration),
      provisioningRuns = provisioningRuns,
      provisioningWorker = provisioningWorker,
      provisioningPlanCleanup = provisioningPlanCleanup,
      serverProfiles = serverProfiles,
      remnawaveOnboarding = remnawaveOnboarding,
      remnawaveOnboardingWorker = remnawaveOnboardingWorker,
      remnawaveFleets = remnawaveFleets,
      remnawaveFleetObserver = remnawaveFleetObserver,
      remnawaveFleetRollouts = remnawaveFleetRollouts,
      remnawaveFleetRolloutWorker = remnawaveFleetRolloutWorker,
      remnawaveFleetUpgrades = remnawaveFleetUpgrades,
      remnawaveFleetUpgradeWorker = remnawaveFleetUpgradeWorker,
      configurationPromotions = new ConfigurationPromotions[IO, ConnectionIO](
        configurationPromotionRepository, configurationProfileQuery, transactionTimeProvider, auditRecorder,
        readOnlySnapshotRunner, transactionRunner),
      configurationRollouts = new ConfigurationRollouts[IO, ConnectionIO](
        configurationAssignmentRepository, configurationAssignmentQuery, configurationProfileQuery,
        configurationDeploymentSourceQuery, configurationDeployments,
        configurationRolloutRepository, transactionIdGenerator, transactionTimeProvider,
        auditRecorder, readOnlySnapshotRunner, transactionRunner, config.configurationDeployment),
      // Rules manage desired state only: the rule worker gets no transport and no credential.
      configurationAssignmentRules = new application.configuration.ConfigurationAssignmentRules[IO, ConnectionIO](
        configurationAssignmentRuleRepository, configurationAssignmentRuleRepository, configurationAssignmentRepository,
        configurationAssignmentQuery, configurationProfileQuery, transactionIdGenerator, transactionTimeProvider,
        auditRecorder, readOnlySnapshotRunner, transactionRunner),
      configurationAssignmentRuleWorker = new application.configuration.ConfigurationAssignmentRuleWorker[ConnectionIO](
        configurationAssignmentRuleRepository, configurationProfileQuery, transactionIdGenerator, transactionRunner,
        schedulerInstanceId, config.configurationRules, loggers.configuration),
      resourceLabels = new application.configuration.ResourceLabels[IO, ConnectionIO](resourceLabelRepository,
        transactionTimeProvider, auditRecorder, readOnlySnapshotRunner, transactionRunner),
      configurationRolloutWorker = new ConfigurationRolloutWorker[ConnectionIO](
        configurationRolloutRepository, configurationDeploymentRepository,
        transactionIdGenerator, transactionRunner, schedulerInstanceId, loggers.configuration),
      configurationProfileManagement = new ConfigurationProfileManagement[ConnectionIO](configurationProfileRepository,
        transactionIdGenerator, transactionTimeProvider, auditRecorder),
      listIncidents = ListIncidents[ConnectionIO](incidentListQuery),
      listMonitorRules = ListMonitorRules[ConnectionIO](
        resourceRepository,
        monitorRuleRepository,
        monitorRuleStateRepository
      ),
      createMonitorRule = CreateMonitorRule[ConnectionIO](
        resourceRepository,
        monitorRuleRepository,
        transactionIdGenerator,
        transactionTimeProvider,
        auditRecorder
      ),
      updateMonitorRule = UpdateMonitorRule[ConnectionIO](
        monitorRuleRepository,
        monitorRuleStateRepository,
        incidentRepository,
        recordNotificationDeliveries,
        auditRecorder,
        historyRecorder,
        transactionTimeProvider
      ),
      getConnection = GetConnection[ConnectionIO](
        connectionRepository,
        syncSessionRepository,
        connectionScheduleRepository
      ),
      // One read of fixed size for the whole list, not three queries per connection.
      listConnections = ListConnections[ConnectionIO](
        connectionOverviewQuery
      ),
      sshConnectionManagement = new SshConnectionManagement[ConnectionIO](
        connectionRepository,
        connectionScheduleRepository,
        connectionSecretRepository,
        projectRepository,
        environmentRepository,
        transactionRunner,
        integrations.sshConnectionProbe,
        integrations.sshCredentialResolver,
        integrations.secretCipher,
        auditRecorder,
        connectionLifecycleRepository
      ),
      openSshTerminal = integrations.openSshTerminal,
      terminalSessionLifecycle = new application.terminal.TerminalSessionLifecycle(
        terminalSessionRepository, transactionRunner, schedulerInstanceId,
        config.terminal.maxUserSessions, config.terminal.maxOrganizationSessions,
        config.terminal.leaseDuration, loggers.terminal),
      listConnectionSyncSessions =
        new ListConnectionSyncSessions[ConnectionIO](connectionRepository, syncSessionRepository),
      getConnectionSyncSession = new GetConnectionSyncSession[ConnectionIO](syncSessionRepository),
      runManualConnectionSync = new RunManualConnectionSync[ConnectionIO](
        runConnectionSync,
        syncSessionRepository,
        connectionRepository,
        auditRecorder,
        transactionRunner
      ),
      getOrganization = GetOrganization[ConnectionIO](organizationRepository),
      listProjects = ListProjects[ConnectionIO](organizationRepository, projectRepository),
      listEnvironments =
        ListEnvironments[ConnectionIO](organizationRepository, projectRepository, environmentRepository),
      getEnvironmentContext = GetEnvironmentContext[ConnectionIO](navigationQueryRepository),
      createProject = new CreateProject[ConnectionIO](
        organizationRepository,
        projectRepository,
        transactionIdGenerator,
        transactionTimeProvider,
        auditRecorder
      ),
      createEnvironment = new CreateEnvironment[ConnectionIO](
        projectRepository,
        environmentRepository,
        transactionIdGenerator,
        transactionTimeProvider,
        auditRecorder
      ),
      login = new Login[ConnectionIO](
        userAccountRepository,
        authSessionRepository,
        loginThrottleRepository,
        securityEventRepository,
        loginThrottleHasher,
        config.loginRateLimit,
        transactionRunner,
        passwordHasher,
        sessionTokens,
        timeProvider,
        config.auth.ttlSeconds
      ),
      cleanupLoginThrottle = new CleanupLoginThrottle[IO, ConnectionIO](
        loginThrottleRepository,
        transactionRunner,
        loggers.account,
        config.loginRateLimit.retention,
        LoginThrottleCleanupInterval
      ),
      cleanupSecurityEvents = new CleanupSecurityEvents[IO, ConnectionIO](
        securityEventRepository, transactionRunner, loggers.account, config.securityEvents.retention,
        SecurityEventCleanupInterval
      ),
      administration = new application.administration.Administration[ConnectionIO](administrationRepository,
        userAccountRepository,membershipRepository,organizationRepository,transactionRunner,passwordHasher,
        transactionIdGenerator,transactionTimeProvider,auditRecorder),
      authentication = new Authentication[ConnectionIO](
        authSessionRepository,
        membershipRepository,
        transactionRunner,
        sessionTokens,
        Some(administrationRepository)
      ),
      changePassword = new ChangePassword[ConnectionIO](
        userAccountRepository,
        authSessionRepository,
        securityEventRepository,
        accountAudit,
        transactionRunner,
        passwordHasher,
        timeProvider
      ),
      updateAccountProfile = new UpdateAccountProfile[ConnectionIO](
        userAccountRepository,
        accountAudit,
        transactionRunner,
        timeProvider
      ),
      listUserSessions = new ListUserSessions[ConnectionIO](
        authSessionRepository, transactionRunner, timeProvider),
      revokeUserSession = new RevokeUserSession[ConnectionIO](
        authSessionRepository, securityEventRepository, accountAudit, transactionRunner, timeProvider),
      revokeOtherUserSessions = new RevokeOtherUserSessions[ConnectionIO](
        authSessionRepository, securityEventRepository, accountAudit, transactionRunner, timeProvider),
      revokeAllUserSessions = new RevokeAllUserSessions[ConnectionIO](
        authSessionRepository, securityEventRepository, accountAudit, transactionRunner, timeProvider),
      listSecurityEvents = new ListSecurityEvents[ConnectionIO](securityEventQuery),
      bootstrapAdmin = new BootstrapAdmin[ConnectionIO](
        userAccountRepository,
        membershipRepository,
        organizationRepository,
        organizationProvisioning,
        transactionRunner,
        passwordHasher
      ),
      listAuditEvents = new ListAuditEvents[ConnectionIO](auditEventRepository),
      listNotificationChannels =
        new ListNotificationChannels[ConnectionIO](notificationChannelRepository),
      getNotificationChannel =
        new GetNotificationChannel[ConnectionIO](notificationChannelRepository),
      notificationChannelManagement = new NotificationChannelManagement[ConnectionIO](
        notificationChannelRepository,
        notificationChannelSecretRepository,
        transactionIdGenerator,
        transactionTimeProvider,
        integrations.notificationChannelCipher,
        auditRecorder
      ),
      integrationManagement = integrationManagement,
      testIntegration = new TestIntegration[ConnectionIO](integrationManagement, transactionRunner,
        integrations.integrationCredentialCipher, integrations.integrationProviderRegistry),
      integrationProviderRegistry = integrations.integrationProviderRegistry,
      integrationSync = integrationSync,
      integrationActions = integrationActions,
      integrationActionWorker = integrationActionWorker,
      integrationActionsEnabled = actionSettings.enabled,
      integrationDesiredStates = integrationDesiredStatesService,
      // Desired state and execution stay apart: this worker is given no provider, client or credential.
      integrationDesiredStateWorker = new IntegrationDesiredStateWorker[ConnectionIO](
        integrationDesiredStateRepository, transactionRunner, timeProvider, loggers.integration,
        IntegrationDesiredStateSettings(desiredStateSettings.pollInterval, desiredStateSettings.batchSize,
          desiredStateSettings.maxConcurrency, desiredStateSettings.claimLease,
          // Observations nudge reconciliation; this is only the fallback for a missed nudge.
          idleInterval = config.integrations.sync.interval * 5), UUID.randomUUID()),
      integrationConfigProfiles = integrationConfigProfiles,
      integrationConfigDeploymentWorker = integrationConfigDeploymentWorker,
      integrationConfigRollouts = integrationConfigRolloutService,
      integrationConfigRolloutWorker = integrationConfigRolloutWorker,
      integrationBindings = integrationBindingsService,
      integrationInventoryMaintenance = new application.integration.IntegrationInventoryMaintenance[ConnectionIO](
        integrationRepository, integrationInventoryRepository, integrationActionRepository,
        integrationDesiredStatesService, integrationBindingsService, transactionTimeProvider, auditRecorder),
      // Its own claims, lease and instance identity: never the connection scheduler's.
      integrationSyncScheduler = new IntegrationSyncScheduler[ConnectionIO](integrationSync,
        integrationSyncStateRepository, transactionRunner, timeProvider, loggers.integration,
        IntegrationSyncSchedulerSettings(integrationSyncSettings.pollInterval, integrationSyncSettings.interval,
          integrationSyncSettings.batchSize, integrationSyncSettings.maxConcurrency, integrationSyncSettings.claimLease),
        UUID.randomUUID()),
      testNotificationChannel = new TestNotificationChannel(managedNotificationSender),
      listHistoryEvents = new ListHistoryEvents[ConnectionIO](historyEventQuery),
      getOperationsOverview = new GetOperationsOverview[ConnectionIO](
        operationsOverviewQuery,
        historyEventQuery,
        projectRepository,
        navigationQueryRepository,
        transactionTimeProvider
      ),
      resourceOperationPreparation = resourceOperationPreparation,
      executeResourceOperation = new ExecuteResourceOperation[ConnectionIO](
        resourceOperationPreparation, operationExecutionRepository,
        integrations.resourceOperationExecutor, transactionRunner, timeProvider, historyRecorder,
        loggers.operation),
      listResourceOperationExecutions = new ListResourceOperationExecutions[ConnectionIO](
        resourceOperationTargetQuery, operationExecutionRepository),
      scheduler = new SyncScheduler[IO, ConnectionIO](
        connectionScheduleRepository,
        runConnectionSync,
        transactionRunner,
        timeProvider,
        loggers.scheduler,
        config.scheduler.maxConcurrency,
        schedulerInstanceId,
        config.scheduler.claimLease
      ),
      notificationDispatcher = transports.legacyWebhookSender.map(sender =>
        new NotificationDispatcher[IO, ConnectionIO](
          notificationDeliveryRepository,
          sender,
          transactionRunner,
          timeProvider,
          loggers.notification,
          // This worker speaks the deployment's own webhook and nothing else. Deliveries
          // addressed to configured channels wait for the worker that can send them.
          NotificationDeliveryScope.Legacy,
          config.notification.maxConcurrency,
          notificationDispatcherInstanceId,
          config.notification.claimLease,
          config.notification.maxAttempts
        )
      ),
      // The worker that serves configured channels. It resolves the channel of each delivery at
      // the moment of sending, so it needs no transport decided in advance.
      managedNotificationDispatcher = new NotificationDispatcher[IO, ConnectionIO](
        notificationDeliveryRepository,
        managedNotificationSender,
        transactionRunner,
        timeProvider,
        loggers.notification,
        NotificationDeliveryScope.Managed,
        config.notification.maxConcurrency,
        // Its own identity: two workers of one process must not fence each other's claims.
        managedDispatcherInstanceId,
        config.notification.claimLease,
        config.notification.maxAttempts
      )
    )
  }
}
