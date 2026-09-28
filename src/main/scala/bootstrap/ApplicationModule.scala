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
  ConfigurationProfileManagement,
  ConfigurationProfileQueries
}
import application.context.{
  GetConnectionInfrastructureSummary,
  GetResourceContext,
  ListConnectionInfrastructureCounts,
  ListConnectionResources,
  ListEnvironmentResourceSources
}
import application.incident.{GetIncidentDetail, ListConnectionIncidents, ListIncidents, ListResourceIncidents}
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
    notificationDispatcherInstanceId: UUID = UUID.randomUUID()
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
      configurationDeployments = new ConfigurationDeployments[IO, ConnectionIO](
        configurationAssignmentRepository, configurationAssignmentQuery, configurationProfileQuery,
        configurationDeploymentSourceQuery, configurationDeploymentRepository,
        integrations.configurationTransport, transactionIdGenerator, transactionTimeProvider,
        auditRecorder, readOnlySnapshotRunner, transactionRunner),
      configurationDeploymentWorker = new ConfigurationDeploymentWorker[ConnectionIO](
        configurationDeploymentRepository, configurationAssignmentRepository, configurationAssignmentQuery,
        configurationProfileQuery, configurationDeploymentSourceQuery, integrations.configurationTransport,
        transactionRunner, schedulerInstanceId),
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
      listConnections = ListConnections[ConnectionIO](
        connectionRepository,
        syncSessionRepository,
        connectionScheduleRepository
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
        auditRecorder
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
      authentication = new Authentication[ConnectionIO](
        authSessionRepository,
        membershipRepository,
        transactionRunner,
        sessionTokens
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
