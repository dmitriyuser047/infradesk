package ru.bitec.app.ops
package infrastructure.database

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import munit.FunSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.sql.DriverManager
import java.util.UUID

final class DatabaseMigratorIntegrationSpec extends FunSuite {
  test("Flyway applies V1 through V15 to an empty PostgreSQL database and is idempotent") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")

    val base = DatabaseConfig.fromEnvironment(sys.env).fold(throw _, identity)
    val url = "^(jdbc:postgresql://[^/]+/)[^?]+(\\?.*)?$".r
    val (prefix, suffix) = base.url match {
      case url(hostAndPort, query) => (hostAndPort, Option(query).getOrElse(""))
      case _ => fail("Integration test requires a PostgreSQL JDBC URL with explicit host and database")
    }
    val maintenanceUrl = s"${prefix}postgres$suffix"
    val databaseName = "infradesk_migration_" + UUID.randomUUID().toString.replace("-", "")
    val testConfig = base.copy(url = s"$prefix$databaseName$suffix")
    val logger = Slf4jLogger.getLoggerFromName[IO]("test.database.migrator")

    def adminSql(sql: String): IO[Unit] = IO.blocking {
      val connection = DriverManager.getConnection(maintenanceUrl, base.user, base.password)
      try {
        val statement = connection.createStatement()
        try statement.execute(sql)
        finally statement.close()
      } finally connection.close()
    }

    val program = for {
      first <- DatabaseMigrator.migrate(testConfig, logger)
      second <- DatabaseMigrator.migrate(testConfig, logger)
      _ <- IO.blocking {
        assertEquals(first.migrationsApplied, 15)
        assertEquals(first.currentVersion, "15")
        assertEquals(second.migrationsApplied, 0)
        assertEquals(second.currentVersion, "15")
        val connection = DriverManager.getConnection(testConfig.url, testConfig.user, testConfig.password)
        try {
          val statement = connection.createStatement()
          try {
            val result = statement.executeQuery(
              "select to_regclass('public.flyway_schema_history'), to_regclass('public.organization'), " +
                "to_regclass('public.resource'), to_regclass('public.sync_session'), to_regclass('public.incident')"
            )
            try {
              assert(result.next())
              (1 to 5).foreach(index => assert(result.getString(index) != null))
            } finally result.close()
            val monitoringColumns = statement.executeQuery(
              "select count(*) from information_schema.columns where table_schema = current_schema() " +
                "and ((table_name = 'monitor_rule' and column_name = 'no_data_seconds') " +
                "or (table_name = 'incident' and column_name = 'reason'))"
            )
            try {
              assert(monitoringColumns.next())
              assertEquals(monitoringColumns.getInt(1), 2)
            } finally monitoringColumns.close()
          } finally statement.close()
        } finally connection.close()
      }
      _ <- IO.blocking {
        val connection = DriverManager.getConnection(testConfig.url, testConfig.user, testConfig.password)
        try {
          val statement = connection.createStatement()
          try assertEquals(statement.executeUpdate(
            "update flyway_schema_history set checksum = checksum + 1 where version = '15'"
          ), 1)
          finally statement.close()
        } finally connection.close()
      }
      checksumMismatch <- DatabaseMigrator.migrate(testConfig, logger).attempt
      _ <- IO(assert(checksumMismatch.isLeft))
    } yield ()

    (adminSql(s"CREATE DATABASE $databaseName") *> program
      .guarantee(adminSql(s"DROP DATABASE $databaseName WITH (FORCE)"))).unsafeRunSync()
  }
}
