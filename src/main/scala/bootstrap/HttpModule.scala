package ru.bitec.app.ops
package bootstrap

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.semigroupk._
import infrastructure.http.{
  AuditRoutes,
  AuthBoundary,
  AuthRoutes,
  AuthSettings,
  ConnectionRoutes,
  ConnectionSyncRoutes,
  HealthRoutes,
  HistoryRoutes,
  IncidentRoutes,
  MonitorRuleRoutes,
  NavigationRoutes,
  NotificationChannelRoutes,
  OperationsOverviewRoutes,
  OrganizationAuthorization,
  ResourceRoutes,
  ResourceOperationRoutes,
  SshConnectionMutationRoutes,
  WorkspaceMutationRoutes
}
import infrastructure.http.middleware.{HttpRequestLogging, RequestIdMiddleware}
import org.http4s.{HttpApp, Method, Request}

/** Assembles routes, the authentication boundary, the platform endpoints and the observability
  * middleware into the single HttpApp served by the runtime.
  */
object HttpModule {

  def build(
    persistence: PersistenceComponents,
    application: ApplicationComponents,
    authSettings: AuthSettings,
    loggers: AppLoggers
  ): HttpApp[IO] = {
    val transactionRunner = persistence.transactionRunner
    // Every organization-scoped handler states the permission it needs through this helper; the
    // authentication boundary no longer knows which operations are privileged.
    val authorization = new OrganizationAuthorization(loggers.authorization)

    val businessApp = (
      new ResourceRoutes(
        application.getResource,
        application.listEnvironmentResources,
        application.getResourceMetricHistory,
        transactionRunner,
        authorization
      ).routes <+>
        new ResourceOperationRoutes(
          application.resourceOperationPreparation,
          application.executeResourceOperation,
          application.listResourceOperationExecutions,
          transactionRunner,
          authorization
        ).routes <+>
        new IncidentRoutes(application.getIncident, application.listIncidents, transactionRunner,
          authorization).routes <+>
        new MonitorRuleRoutes(
          application.listMonitorRules,
          application.createMonitorRule,
          application.updateMonitorRule,
          transactionRunner,
          authorization
        ).routes <+>
        new ConnectionRoutes(application.getConnection, application.listConnections,
          transactionRunner, authorization).routes <+>
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
        new AuditRoutes(application.listAuditEvents, transactionRunner, authorization).routes <+>
        new HistoryRoutes(application.listHistoryEvents, persistence.resourceRepository,
          transactionRunner, authorization).routes <+>
        // Several statements that must agree with each other: one read-only snapshot.
        new OperationsOverviewRoutes(application.getOperationsOverview,
          persistence.readOnlySnapshotRunner, authorization).routes
    ).orNotFound

    val authRoutes = new AuthRoutes(application.login, application.authentication, authSettings)
    val protectedApp = new AuthBoundary(authRoutes, application.authentication, businessApp).app
    val platformApp = new HealthRoutes(persistence.readinessCheck, loggers.health).routes.orNotFound

    val routed: HttpApp[IO] = Kleisli { request: Request[IO] =>
      val path = request.uri.path.renderString
      if (request.method == Method.GET && (path == "/health" || path == "/ready"))
        platformApp.run(request)
      else protectedApp.run(request)
    }

    RequestIdMiddleware(HttpRequestLogging(routed, loggers.httpRequests))
  }
}
