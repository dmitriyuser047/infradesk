package ru.bitec.app.ops
package bootstrap

import cats.effect.{IO, Resource}
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
      // One registry per runtime; a duplicate resource type code fails startup here.
      resourceTypes <- IO.fromEither(PersistenceModule.resourceDefinitionRegistry)
      _ <- Database.transactor(config.database).use { xa =>
        val persistence = PersistenceModule.build(xa, resourceTypes)
        val integrations = IntegrationModule.build(config, persistence)
        val application = ApplicationModule.build(config, persistence, integrations, loggers, schedulerInstanceId)
        val httpApp = HttpModule.build(persistence, application, config.auth, loggers)

        application.bootstrapAdmin.run(config.bootstrap) *>
          serve(
            httpServer(config.http, httpApp),
            application.scheduler.run(config.scheduler.pollInterval, limit = config.scheduler.batchSize)
          )
      }
    } yield ()
  }

  /** The scheduler runs inside the server resource scope, so it is never a detached fiber:
    * cancelling the application cancels the scheduler first, then releases the server, then
    * releases the database pool.
    */
  private[bootstrap] def serve(server: Resource[IO, Any], scheduler: IO[Nothing]): IO[Unit] =
    server.use(_ => scheduler).void

  private def httpServer(config: HttpConfig, app: HttpApp[IO]): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(app)
      .build
}
