package ru.bitec.app.ops
package bootstrap

import cats.effect.IO
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Named loggers injected into the components that emit observability events.
  *
  * The names are part of the stage 1 observability contract, so they are declared in one
  * place instead of being rediscovered inside business operations.
  */
final case class AppLoggers(
  migration: Logger[IO],
  httpRequests: Logger[IO],
  health: Logger[IO],
  sync: Logger[IO],
  scheduler: Logger[IO],
  monitor: Logger[IO],
  notification: Logger[IO],
  authorization: Logger[IO]
)

object AppLoggers {

  def slf4j: AppLoggers = AppLoggers(
    migration = named("infrastructure.database.DatabaseMigrator"),
    httpRequests = named("infrastructure.http.requests"),
    health = named("infrastructure.http.health"),
    sync = named("application.connector.SyncConnection"),
    scheduler = named("application.scheduler.SyncScheduler"),
    monitor = named("application.monitor.EvaluateMonitorRules"),
    notification = named("application.notification.NotificationDispatcher"),
    authorization = named("infrastructure.http.OrganizationAuthorization")
  )

  private def named(name: String): Logger[IO] = Slf4jLogger.getLoggerFromName[IO](name)
}
