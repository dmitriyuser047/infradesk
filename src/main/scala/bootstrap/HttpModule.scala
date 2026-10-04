package ru.bitec.app.ops
package bootstrap

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.semigroupk._
import infrastructure.http.{
  AccountRoutes,
  AuditRoutes,
  AuthBoundary,
  AuthRoutes,
  AuthSettings,
  ConnectionRoutes,
  ConnectionSyncRoutes,
  HealthRoutes,
  HistoryRoutes,
  ConfigurationAssignmentRoutes,
  ConfigurationDeploymentRoutes,
  ConfigurationProfileRoutes,
  ConfigurationPromotionRoutes,
  ConfigurationRolloutRoutes,
  IncidentRoutes,
  InfrastructureContextRoutes,
  IntegrationRoutes,
  IntegrationActionRoutes,
  IntegrationDesiredStateRoutes,
  IntegrationConfigProfileRoutes,
  MonitorRuleRoutes,
  NavigationRoutes,
  NotificationChannelRoutes,
  OperationsOverviewRoutes,
  OrganizationAuthorization,
  ResourceRoutes,
  ResourceOperationRoutes,
  ProvisioningRoutes,
  ServerProfileRoutes,
  SshConnectionMutationRoutes,
  TerminalRoutes,
  WorkspaceMutationRoutes
}
import infrastructure.config.TerminalConfig
import infrastructure.http.middleware.{HttpRequestLogging, RequestIdMiddleware}
import org.http4s.{HttpApp, Method, Request}
import org.http4s.server.websocket.WebSocketBuilder2

/** Assembles routes, the authentication boundary, the platform endpoints and the observability
  * middleware into the single HttpApp served by the runtime.
  */
object HttpModule {

  def build(
    persistence: PersistenceComponents,
    application: ApplicationComponents,
    authSettings: AuthSettings,
    loggers: AppLoggers
  ): HttpApp[IO] = build(persistence, application, authSettings, loggers, TerminalConfig.default, None)

  def build(
    persistence: PersistenceComponents,
    application: ApplicationComponents,
    authSettings: AuthSettings,
    loggers: AppLoggers,
    terminalConfig: TerminalConfig,
    webSocketBuilder: Option[WebSocketBuilder2[IO]]
  ): HttpApp[IO] = {
    val transactionRunner = persistence.transactionRunner
    // Every organization-scoped handler states the permission it needs through this helper; the
    // authentication boundary no longer knows which operations are privileged.
    val authorization = new OrganizationAuthorization(loggers.authorization)

    val terminalRoutes = webSocketBuilder.fold(org.http4s.HttpRoutes.empty[IO]) { builder =>
      new TerminalRoutes(application.openSshTerminal, terminalConfig, authorization, loggers.terminal,
        Some(application.terminalSessionLifecycle)).routes(builder)
    }

    val businessApp = (
      terminalRoutes <+>
      new ResourceRoutes(
        application.getResource,
        application.listEnvironmentResources,
        application.getResourceMetricHistory,
        transactionRunner,
        authorization
      ).routes <+>
      new ProvisioningRoutes(application.provisioningRuns, authorization, loggers.configuration).routes <+>
        new ServerProfileRoutes(application.serverProfiles,authorization,loggers.configuration).routes <+>
        new ResourceOperationRoutes(
          application.resourceOperationPreparation,
          application.executeResourceOperation,
          application.listResourceOperationExecutions,
          transactionRunner,
          authorization
        ).routes <+>
        new IncidentRoutes(application.getIncident, application.listIncidents, transactionRunner,
          authorization, loggers.readModels).routes <+>
        // Counts and the previews they summarize must agree: one read-only snapshot.
        new InfrastructureContextRoutes(
          application.getConnectionInfrastructureSummary,
          application.listConnectionResources,
          application.listConnectionIncidents,
          application.listConnectionInfrastructureCounts,
          application.getResourceContext,
          application.listResourceIncidents,
          application.listEnvironmentResourceSources,
          persistence.readOnlySnapshotRunner,
          authorization,
          loggers.readModels
        ).routes <+>
        // Writes are short transactions; a profile page is two reads that must agree.
        new ConfigurationProfileRoutes(
          application.configurationProfileQueries,
          application.configurationProfileManagement,
          transactionRunner,
          persistence.readOnlySnapshotRunner,
          authorization,
          loggers.configuration
        ).routes <+>
        // Desired state only: an assignment page is a few reads that must agree.
        new ConfigurationAssignmentRoutes(
          application.configurationAssignments,
          application.configurationAssignmentQueries,
          persistence.readOnlySnapshotRunner,
          authorization,
          loggers.configuration
        ).routes <+>
        new ConfigurationDeploymentRoutes(application.configurationDeployments, authorization,
          loggers.configuration).routes <+>
        new ConfigurationPromotionRoutes(application.configurationPromotions, authorization,
          loggers.configuration).routes <+>
        new ConfigurationRolloutRoutes(application.configurationRollouts, authorization,
          loggers.configuration).routes <+>
        new infrastructure.http.ConfigurationAssignmentRuleRoutes(application.configurationAssignmentRules,
          application.resourceLabels, authorization, loggers.configuration).routes <+>
        new MonitorRuleRoutes(
          application.listMonitorRules,
          application.createMonitorRule,
          application.updateMonitorRule,
          transactionRunner,
          authorization
        ).routes <+>
        new ConnectionRoutes(application.getConnection, application.listConnections,
          transactionRunner, authorization, loggers.readModels).routes <+>
        new SshConnectionMutationRoutes(application.sshConnectionManagement, authorization).routes <+>
        new ConnectionSyncRoutes(
          application.listConnectionSyncSessions,
          application.getConnectionSyncSession,
          application.runManualConnectionSync,
          transactionRunner,
          authorization
        ).routes <+>
        new NavigationRoutes(
          application.getOrganization,
          application.listProjects,
          application.listEnvironments,
          application.getEnvironmentContext,
          transactionRunner,
          authorization
        ).routes <+>
        new WorkspaceMutationRoutes(
          application.createProject,
          application.createEnvironment,
          transactionRunner,
          authorization
        ).routes <+>
        new NotificationChannelRoutes(
          application.listNotificationChannels,
          application.getNotificationChannel,
          application.notificationChannelManagement,
          application.testNotificationChannel,
          transactionRunner,
          authorization
        ).routes <+>
        new IntegrationRoutes(application.integrationManagement, application.testIntegration,
          application.integrationProviderRegistry, transactionRunner, authorization, application.integrationSync,
          application.integrationBindings, persistence.integrationInventoryQuery,
          persistence.integrationSyncSessionRepository).routes <+>
        new IntegrationActionRoutes(application.integrationActions, application.integrationManagement,
          persistence.integrationActionRepository,
          transactionRunner, authorization, application.integrationActionsEnabled, loggers.integration).routes <+>
        new IntegrationDesiredStateRoutes(application.integrationDesiredStates, transactionRunner, authorization,
          loggers.integration).routes <+>
        new infrastructure.http.RemnawaveOnboardingRoutes(application.remnawaveOnboarding,authorization,loggers.integration).routes <+>
        new infrastructure.http.RemnawaveFleetRoutes(application.remnawaveFleets,authorization,loggers.integration).routes <+>
        new IntegrationConfigProfileRoutes(application.integrationConfigProfiles,
          application.integrationConfigRollouts, authorization).routes <+>
        new AuditRoutes(application.listAuditEvents, transactionRunner, authorization).routes <+>
        new HistoryRoutes(application.listHistoryEvents, persistence.resourceRepository,
          transactionRunner, authorization).routes <+>
        // Several statements that must agree with each other: one read-only snapshot.
        new OperationsOverviewRoutes(application.getOperationsOverview,
          persistence.readOnlySnapshotRunner, authorization).routes
    ).orNotFound

    val authRoutes = new AuthRoutes(application.login, application.authentication, authSettings)
    val accountRoutes = new AccountRoutes(
      application.changePassword,
      application.updateAccountProfile,
      application.listUserSessions,
      application.revokeUserSession,
      application.revokeOtherUserSessions,
      application.revokeAllUserSessions,
      application.listSecurityEvents,
      transactionRunner,
      authSettings,
      loggers.account)
    val protectedApp =
      new AuthBoundary(authRoutes, accountRoutes, application.authentication, businessApp).app
    val platformApp = new HealthRoutes(persistence.readinessCheck, loggers.health,
      build = HealthRoutes.Build(BuildInfo.version, BuildInfo.gitSha)).routes.orNotFound

    val routed: HttpApp[IO] = Kleisli { request: Request[IO] =>
      val path = request.uri.path.renderString
      if (request.method == Method.GET && (path == "/health" || path == "/ready"))
        platformApp.run(request)
      else protectedApp.run(request)
    }

    RequestIdMiddleware(HttpRequestLogging(routed, loggers.httpRequests))
  }
}
