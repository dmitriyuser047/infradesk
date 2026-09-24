package ru.bitec.app.ops
package bootstrap

import cats.effect.{IO, Resource}
import cats.syntax.all._
import infrastructure.config.{AppConfig, HttpConfig}
import infrastructure.database.{Database, DatabaseMigrator}
import org.http4s.HttpApp
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
      _ <- DatabaseMigrator.migrate(config.database, loggers.migration)
      // Process identity for scheduler claims: created once per JVM run, never per tick or job.
      schedulerInstanceId <- IO(UUID.randomUUID())
      // Process identity for notification claims, created once per JVM run like the scheduler's.
      dispatcherInstanceId <- IO(UUID.randomUUID())
      // One registry per runtime; a duplicate resource type code fails startup here.
      resourceTypes <- IO.fromEither(PersistenceModule.resourceDefinitionRegistry)
      runtime = (
        Database.transactor(config.database),
        IntegrationModule.notificationSender(config.notification)
      ).tupled
      _ <- runtime.use { case (xa, notificationSender) =>
        val persistence = PersistenceModule.build(xa, resourceTypes)
        val integrations = IntegrationModule.build(config, persistence)
        val application = ApplicationModule.build(config, persistence, integrations, loggers,
          schedulerInstanceId, notificationSender, dispatcherInstanceId)
        val httpApp = HttpModule.build(persistence, application, config.auth, loggers)

        val workers =
          application.scheduler.run(config.scheduler.pollInterval, limit = config.scheduler.batchSize) ::
            application.notificationDispatcher.map(
              _.run(config.notification.pollInterval, limit = config.notification.batchSize)
            ).toList

        application.bootstrapAdmin.run(config.bootstrap) *>
          serve(httpServer(config.http, httpApp), workers)
      }
    } yield ()
  }

  /** The background workers run inside the server resource scope, so none of them is a detached
    * fiber: cancelling the application cancels them first, then releases the server, then the
    * HTTP client and the database pool. A worker that fails cancels its siblings and the server.
    */
  private[bootstrap] def serve(server: Resource[IO, Any], workers: List[IO[Nothing]]): IO[Unit] =
    server.use(_ => workers.parTraverse_(identity)).void

  private def httpServer(config: HttpConfig, app: HttpApp[IO]): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(app)
      .build
}
