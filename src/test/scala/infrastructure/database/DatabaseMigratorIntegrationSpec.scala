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
  test("Flyway applies V1 through V18 to an empty PostgreSQL database and is idempotent") {
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
        assertEquals(first.migrationsApplied, 18)
        assertEquals(first.currentVersion, "18")
        assertEquals(second.migrationsApplied, 0)
        assertEquals(second.currentVersion, "18")
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
            val outbox = statement.executeQuery(
              "select to_regclass('public.notification_delivery'), " +
                "(select count(*) from pg_indexes where schemaname = current_schema() " +
                "and indexname in ('ux_notification_delivery_event', 'ix_notification_delivery_claimable'))"
            )
            try {
              assert(outbox.next())
              assert(outbox.getString(1) != null)
              assertEquals(outbox.getInt(2), 2)
            } finally outbox.close()
            val audit = statement.executeQuery(
              "select to_regclass('public.audit_event'), " +
                "(select count(*) from pg_indexes where schemaname = current_schema() " +
                "and indexname = 'ix_audit_event_organization_recent'), " +
                "(select count(*) from information_schema.columns where table_schema = current_schema() " +
                "and table_name = 'audit_event' " +
                "and column_name in ('organization_id', 'actor_user_id', 'action', 'target_type', " +
                "'target_id', 'occurred_at')), " +
                "(select count(*) from information_schema.table_constraints " +
                "where table_schema = current_schema() and table_name = 'audit_event' " +
                "and constraint_name in ('ck_audit_event_action', 'ck_audit_event_target_type'))"
            )
            try {
              assert(audit.next())
              assert(audit.getString(1) != null)
              assertEquals(audit.getInt(2), 1)
              assertEquals(audit.getInt(3), 6)
              assertEquals(audit.getInt(4), 2)
            } finally audit.close()
            val operations = statement.executeQuery(
              "select to_regclass('public.operation_execution'), " +
                "(select count(*) from pg_indexes where schemaname = current_schema() " +
                "and indexname in ('ux_operation_execution_resource_running', " +
                "'ix_operation_execution_resource_history')), " +
                "(select count(*) from information_schema.table_constraints " +
                "where table_schema = current_schema() and table_name = 'operation_execution' " +
                "and constraint_name in ('ck_operation_execution_operation', " +
                "'ck_operation_execution_status', 'ck_operation_execution_lifecycle')), " +
                "(select indexdef like '%WHERE%''RUNNING''%' from pg_indexes " +
                "where schemaname = current_schema() " +
                "and indexname = 'ux_operation_execution_resource_running')"
            )
            try {
              assert(operations.next())
              assert(operations.getString(1) != null)
              assertEquals(operations.getInt(2), 2)
              assertEquals(operations.getInt(3), 3)
              // The uniqueness that serializes remediation is the partial one, not a global one.
              assertEquals(operations.getBoolean(4), true)
            } finally operations.close()
          } finally statement.close()
        } finally connection.close()
      }
      _ <- IO.blocking {
        val connection = DriverManager.getConnection(testConfig.url, testConfig.user, testConfig.password)
        try {
          val statement = connection.createStatement()
          try assertEquals(statement.executeUpdate(
            "update flyway_schema_history set checksum = checksum + 1 where version = '18'"
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
