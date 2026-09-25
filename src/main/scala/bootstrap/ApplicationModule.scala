package ru.bitec.app.ops
package bootstrap

import application.audit.{AuditRecorder, ListAuditEvents}
import application.history.{
  HistoryRecorder,
  HistoryRecordingMonitorRuleEvaluator,
  ListHistoryEvents
}
import application.auth.{Authentication, BCryptPasswordHasher, BootstrapAdmin, Login, SessionTokens}
import application.connection.{
  GetConnection,
  GetConnectionSyncSession,
  ListConnectionSyncSessions,
  ListConnections,
  RunManualConnectionSync,
  SshConnectionManagement
}
import application.connector.{RunConnectionSync, SyncConnection, SyncConnectionById}
import application.discovery.{CreateDiscoveredResource, ReconcileDiscoveredResource, SyncDiscoveredSnapshot}
import application.incident.{GetIncident, ListIncidents}
import application.overview.GetOperationsOverview
import application.monitor.{CreateMonitorRule, EvaluateMonitorRules, ListMonitorRules, UpdateMonitorRule}
import application.notification.{
  GetNotificationChannel,
  ListNotificationChannels,
  NotificationChannelManagement,
  NotificationDispatcher,
  NotificationRecordingMonitorRuleEvaluator,
  RecordNotificationDeliveries
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
import domain.notification.NotificationChannelType
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
  getResource: GetResource[ConnectionIO],
  listEnvironmentResources: ListEnvironmentResources[ConnectionIO],
  getResourceMetricHistory: GetResourceMetricHistory[ConnectionIO],
  getIncident: GetIncident[ConnectionIO],
  listIncidents: ListIncidents[ConnectionIO],
  listMonitorRules: ListMonitorRules[ConnectionIO],
  createMonitorRule: CreateMonitorRule[ConnectionIO],
  updateMonitorRule: UpdateMonitorRule[ConnectionIO],
  getConnection: GetConnection[ConnectionIO],
  listConnections: ListConnections[ConnectionIO],
  sshConnectionManagement: SshConnectionManagement[ConnectionIO],
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
  authentication: Authentication[ConnectionIO],
  bootstrapAdmin: BootstrapAdmin[ConnectionIO],
  listAuditEvents: ListAuditEvents[ConnectionIO],
  listNotificationChannels: ListNotificationChannels[ConnectionIO],
  getNotificationChannel: GetNotificationChannel[ConnectionIO],
  notificationChannelManagement: NotificationChannelManagement[ConnectionIO],
  listHistoryEvents: ListHistoryEvents[ConnectionIO],
  getOperationsOverview: GetOperationsOverview[ConnectionIO],
  resourceOperationPreparation: ResourceOperationPreparation[ConnectionIO],
  executeResourceOperation: ExecuteResourceOperation[ConnectionIO],
  listResourceOperationExecutions: ListResourceOperationExecutions[ConnectionIO],
  scheduler: SyncScheduler[IO, ConnectionIO],
  /** Absent when no notification channel is configured, so no worker is started for it. */
  notificationDispatcher: Option[NotificationDispatcher[IO, ConnectionIO]]
)

object ApplicationModule {

  def build(
    config: AppConfig,
    persistence: PersistenceComponents,
    integrations: IntegrationComponents,
    loggers: AppLoggers,
    schedulerInstanceId: UUID,
    notificationSender: Option[NotificationSender[IO]] = None,
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

    // Only channels the deployment can actually deliver to are recorded.
    val notificationChannelTypes: List[NotificationChannelType] =
      if (notificationSender.isDefined) List(NotificationChannelType.Webhook) else Nil

    val recordNotificationDeliveries =
      new RecordNotificationDeliveries[ConnectionIO](
        notificationDeliveryRepository,
        transactionIdGenerator,
        transactionTimeProvider,
        notificationChannelTypes
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

    val passwordHasher = new BCryptPasswordHasher
    val sessionTokens = new SessionTokens
    val resourceOperationPreparation = new ResourceOperationPreparation[ConnectionIO](
      resourceOperationTargetQuery, operationExecutionRepository, transactionIdGenerator,
      transactionTimeProvider, auditRecorder, historyRecorder,
      integrations.resourceOperationBudget)

    ApplicationComponents(
      getResource = GetResource[ConnectionIO](resourceRepository),
      listEnvironmentResources = ListEnvironmentResources[ConnectionIO](resourceRepository),
      getResourceMetricHistory =
        GetResourceMetricHistory[ConnectionIO](resourceRepository, metricObservationRepository),
      getIncident = GetIncident[ConnectionIO](incidentRepository),
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
        transactionRunner,
        passwordHasher,
        sessionTokens,
        config.auth.ttlSeconds
      ),
      authentication = new Authentication[ConnectionIO](
        authSessionRepository,
        membershipRepository,
        transactionRunner,
        sessionTokens
      ),
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
      notificationDispatcher = notificationSender.map(sender =>
        new NotificationDispatcher[IO, ConnectionIO](
          notificationDeliveryRepository,
          sender,
          transactionRunner,
          timeProvider,
          loggers.notification,
          config.notification.maxConcurrency,
          notificationDispatcherInstanceId,
          config.notification.claimLease,
          config.notification.maxAttempts
        )
      )
    )
  }
}
