package ru.bitec.app.ops
package infrastructure.database

import cats.effect.IO
import cats.syntax.all._
import org.flywaydb.core.Flyway
import org.typelevel.log4cats.Logger

final case class MigrationResult(migrationsApplied: Int, currentVersion: String)

object DatabaseMigrator {
  def migrate(config: DatabaseConfig, logger: Logger[IO]): IO[MigrationResult] =
    logger.info("database.migration.started") *>
      IO.blocking {
        val flyway = Flyway.configure()
          .dataSource(config.url, config.user, config.password)
          .locations("classpath:db/migration")
          .load()
        val result = flyway.migrate()
        val current = Option(flyway.info().current())
          .map(_.getVersion.toString)
          .getOrElse("none")
        MigrationResult(result.migrationsExecuted, current)
      }.attempt.flatMap {
        case Right(result) =>
          logger.info(s"database.migration.completed migrationsApplied=${result.migrationsApplied} currentVersion=${result.currentVersion}")
            .as(result)
        case Left(error) =>
          // Flyway/JDBC causes may carry a database URL. Keep startup failure observable without
          // serializing the throwable or its connection details.
          logger.error(s"database.migration.failed errorType=${error.getClass.getSimpleName}") *>
            IO.raiseError(error)
      }
}
