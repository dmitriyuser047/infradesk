package ru.bitec.app.ops
package bootstrap

import cats.effect.{IO, Resource}
import cats.syntax.all._
import infrastructure.config.{AppConfig, HttpConfig}
import infrastructure.database.{Database, DatabaseMigrator}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

import java.util.UUID

/** Startup and shutdown of the whole application.
  *
  * Order: config -> migration -> scheduler identity -> database resource -> component assembly
  * -> bootstrap admin -> HTTP server resource -> scheduler loop. Nothing that touches the
  * business runtime starts before the migration has succeeded.
  */
object InfraDeskApplication {

  def run: IO[Unit] = AppConfig.load.flatMap(run)

  def run(config: AppConfig): IO[Unit] = {
    val loggers = AppLoggers.slf4j
    for {
      // The first line of a deployment's log says which build is running: an image tag can be
      // wrong, the artifact cannot.
      _ <- loggers.lifecycle.info(
        s"application.starting version=${BuildInfo.version} gitSha=${BuildInfo.gitSha} " +
          s"httpHost=${config.http.host} httpPort=${config.http.port} " +
          s"dbMaxPoolSize=${config.database.maxPoolSize} " +
          s"scheduler=${if (config.scheduler.enabled) "enabled" else "disabled"} " +
          s"schedulerMaxConcurrency=${config.scheduler.maxConcurrency} " +
          s"legacyNotificationWebhook=${if (config.notification.enabled) "configured" else "absent"}"
      )
      // Nothing that serves traffic starts before the schema is at the version this build needs.
      _ <- DatabaseMigrator.migrate(config.database, loggers.migration)
      // Process identity for scheduler claims: created once per JVM run, never per tick or job.
      schedulerInstanceId <- IO(UUID.randomUUID())
      // Process identity for notification claims, created once per JVM run like the scheduler's.
      dispatcherInstanceId <- IO(UUID.randomUUID())
      // One registry per runtime; a duplicate resource type code fails startup here.
      resourceTypes <- IO.fromEither(PersistenceModule.resourceDefinitionRegistry)
      runtime = (
        Database.transactor(config.database),
        IntegrationModule.notificationTransports(config.notification, loggers.notification),
        IntegrationModule.integrationProviders(config.integrations),
        IntegrationModule.nodeReleaseVerifier
      ).tupled
      _ <- runtime.use { case (xa, transports, providers, nodeReleaseVerifier) =>
        val persistence = PersistenceModule.build(xa, resourceTypes)
        IntegrationModule.build(config, persistence, providers).flatMap { integrations =>
        val application = ApplicationModule.build(config, persistence, integrations, loggers,
          schedulerInstanceId, transports, dispatcherInstanceId, Some(nodeReleaseVerifier))
        val schedulerWorkers =
          if (config.scheduler.enabled)
            List(application.scheduler.run(config.scheduler.pollInterval,
              limit = config.scheduler.batchSize))
          else Nil
        // Two notification workers: the deployment's own webhook, when it has one, and the
        // channels its organizations configured. They claim disjoint scopes, so neither can take
        // a delivery the other is responsible for.
        val notificationWorkers =
          (application.notificationDispatcher.toList :+ application.managedNotificationDispatcher)
            .map(_.run(config.notification.pollInterval, limit = config.notification.batchSize))
        // A light periodic sweep keeps the login-throttle table bounded; it runs beside the other
        // background workers and never on the login path itself.
        // Remote configuration work: the deployment worker owns every SSH action, the rollout
        // orchestrator only moves durable state. Both are lease-fenced, so any instance may run them.
        val configurationWorkers =
          if (config.configurationDeployment.enabled)
            List(application.configurationDeploymentWorker.run, application.configurationRolloutWorker.run)
          else Nil
        // Rule reconciliation only creates desired assignments; it is independent of deployments.
        val ruleWorkers =
          if (config.configurationRules.enabled) List(application.configurationAssignmentRuleWorker.run) else Nil
        // Read-only observation of enabled integrations; manual synchronization works without it.
        val integrationWorkers =
          (if (config.integrations.sync.enabled) List(application.integrationSyncScheduler.run) else Nil) ++
            (if (config.integrations.actions.enabled) List(application.integrationActionWorker.run) else Nil) ++
            (if (config.integrations.desiredStateOperational) List(application.integrationDesiredStateWorker.run) else Nil) ++
            List(application.integrationConfigDeploymentWorker.run) ++
            (if (config.integrations.configRolloutsOperational)
              List(application.integrationConfigRolloutWorker.run) else Nil) ++
            (if (config.integrations.fleetsOperational) List(application.remnawaveFleetObserver.run, application.remnawaveFleetRolloutWorker.run,
              application.remnawaveFleetUpgradeWorker.run) else Nil)
        val provisioningWorkers = (if (config.provisioning.enabled) List(application.provisioningWorker.run,application.remnawaveOnboardingWorker.run) else Nil) ++
          List(application.provisioningPlanCleanup.run)
        val workers = schedulerWorkers ++ notificationWorkers ++ configurationWorkers ++ ruleWorkers ++ provisioningWorkers ++
          integrationWorkers ++ List(
          application.cleanupLoginThrottle.run,
          application.cleanupSecurityEvents.run, application.terminalSessionLifecycle.reap)

        application.bootstrapAdmin.run(config.bootstrap) *>
          loggers.lifecycle.info(
            s"application.started version=${BuildInfo.version} gitSha=${BuildInfo.gitSha} " +
              s"workers=${workers.size}"
          ) *>
          serve(httpServer(config.http, persistence, application, config.auth, config.terminal, loggers), workers)
        }
      }
    } yield ()
  }

  /** The background workers run inside the server resource scope, so none of them is a detached
    * fiber: cancelling the application cancels them first, then releases the server, then the
    * HTTP client and the database pool. A worker that fails cancels its siblings and the server.
    */
  private[bootstrap] def serve(server: Resource[IO, Any], workers: List[IO[Nothing]]): IO[Unit] =
    // The HTTP server is useful even when every optional background subsystem is disabled.
    // The never-ending lifecycle fiber keeps its Resource scope open; a failed worker still
    // cancels it and therefore releases the server, client and database resources in order.
    server.use(_ => (IO.never[Unit] :: workers).parTraverse_(identity)).void

  private def httpServer(
    config: HttpConfig,
    persistence: PersistenceComponents,
    application: ApplicationComponents,
    authSettings: infrastructure.http.AuthSettings,
    terminalConfig: infrastructure.config.TerminalConfig,
    loggers: AppLoggers
  ): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpWebSocketApp(builder =>
        HttpModule.build(persistence, application, authSettings, loggers, terminalConfig, Some(builder)))
      .build
}
