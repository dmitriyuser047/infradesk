package ru.bitec.app.ops
package bootstrap

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.semigroupk._
import infrastructure.http.{
  AuthBoundary,
  AuthRoutes,
  AuthSettings,
  ConnectionRoutes,
  ConnectionSyncRoutes,
  HealthRoutes,
  IncidentRoutes,
  MonitorRuleRoutes,
  NavigationRoutes,
  ResourceRoutes,
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

    val businessApp = (
      new ResourceRoutes(
        application.getResource,
        application.listEnvironmentResources,
        application.getResourceMetricHistory,
        transactionRunner
      ).routes <+>
        new IncidentRoutes(application.getIncident, application.listIncidents, transactionRunner).routes <+>
        new MonitorRuleRoutes(
          application.listMonitorRules,
          application.createMonitorRule,
          application.updateMonitorRule,
          transactionRunner
        ).routes <+>
        new ConnectionRoutes(application.getConnection, application.listConnections, transactionRunner).routes <+>
        new SshConnectionMutationRoutes(application.sshConnectionManagement).routes <+>
        new ConnectionSyncRoutes(
          application.listConnectionSyncSessions,
          application.getConnectionSyncSession,
          application.runManualConnectionSync,
          transactionRunner
        ).routes <+>
        new NavigationRoutes(
          application.getOrganization,
          application.listProjects,
          application.listEnvironments,
          application.getEnvironmentContext,
          transactionRunner
        ).routes <+>
        new WorkspaceMutationRoutes(
          application.createProject,
          application.createEnvironment,
          transactionRunner
        ).routes
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
