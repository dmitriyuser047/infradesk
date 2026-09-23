package ru.bitec.app.ops
package bootstrap

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
import application.monitor.{CreateMonitorRule, EvaluateMonitorRules, ListMonitorRules, UpdateMonitorRule}
import application.navigation.{GetEnvironmentContext, GetOrganization, ListEnvironments, ListProjects}
import application.resource.{
  GetResource,
  GetResourceMetricHistory,
  ListEnvironmentResources,
  PersistExternalResource,
  RecordResourceObservations
}
import application.scheduler.SyncScheduler
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
  scheduler: SyncScheduler[IO, ConnectionIO]
)

object ApplicationModule {

  def build(
    config: AppConfig,
    persistence: PersistenceComponents,
    integrations: IntegrationComponents,
    loggers: AppLoggers,
    schedulerInstanceId: UUID
  ): ApplicationComponents = {
    import persistence._

    // IO-level primitives for runtime use cases, ConnectionIO-level ones for operations that
    // must generate ids and read the clock inside the transaction they participate in.
    val idGenerator = new SystemIdGenerator
    val timeProvider = new SystemTimeProvider
    val transactionIdGenerator = new ConnectionIOIdGenerator
    val transactionTimeProvider = new ConnectionIOTimeProvider

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
        recordResourceObservations
      )

    val evaluateMonitorRules =
      new EvaluateMonitorRules[ConnectionIO](
        monitorEvaluationQuery,
        monitorRuleStateRepository,
        incidentRepository,
        transactionIdGenerator
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

    ApplicationComponents(
      getResource = GetResource[ConnectionIO](resourceRepository),
      listEnvironmentResources = ListEnvironmentResources[ConnectionIO](resourceRepository),
      getResourceMetricHistory =
        GetResourceMetricHistory[ConnectionIO](resourceRepository, metricObservationRepository),
      getIncident = GetIncident[ConnectionIO](incidentRepository),
      listIncidents = ListIncidents[ConnectionIO](incidentRepository),
      listMonitorRules = ListMonitorRules[ConnectionIO](
        resourceRepository,
        monitorRuleRepository,
        monitorRuleStateRepository
      ),
      createMonitorRule = CreateMonitorRule[ConnectionIO](
        resourceRepository,
        monitorRuleRepository,
        transactionIdGenerator,
        transactionTimeProvider
      ),
      updateMonitorRule = UpdateMonitorRule[ConnectionIO](monitorRuleRepository, transactionTimeProvider),
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
        integrations.sshPasswordResolver,
        integrations.secretCipher
      ),
      listConnectionSyncSessions =
        new ListConnectionSyncSessions[ConnectionIO](connectionRepository, syncSessionRepository),
      getConnectionSyncSession = new GetConnectionSyncSession[ConnectionIO](syncSessionRepository),
      runManualConnectionSync =
        new RunManualConnectionSync[ConnectionIO](runConnectionSync, syncSessionRepository, transactionRunner),
      getOrganization = GetOrganization[ConnectionIO](organizationRepository),
      listProjects = ListProjects[ConnectionIO](organizationRepository, projectRepository),
      listEnvironments =
        ListEnvironments[ConnectionIO](organizationRepository, projectRepository, environmentRepository),
      getEnvironmentContext = GetEnvironmentContext[ConnectionIO](navigationQueryRepository),
      createProject = new CreateProject[ConnectionIO](
        organizationRepository,
        projectRepository,
        transactionIdGenerator,
        transactionTimeProvider
      ),
      createEnvironment = new CreateEnvironment[ConnectionIO](
        projectRepository,
        environmentRepository,
        transactionIdGenerator,
        transactionTimeProvider
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
      scheduler = new SyncScheduler[IO, ConnectionIO](
        connectionScheduleRepository,
        runConnectionSync,
        transactionRunner,
        timeProvider,
        loggers.scheduler,
        config.scheduler.maxConcurrency,
        schedulerInstanceId,
        config.scheduler.claimLease
      )
    )
  }
}
