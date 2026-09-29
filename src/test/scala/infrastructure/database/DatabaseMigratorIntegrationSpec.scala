package ru.bitec.app.ops
package infrastructure.database

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import munit.FunSuite
import org.flywaydb.core.Flyway
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.sql.DriverManager
import java.util.UUID

final class DatabaseMigratorIntegrationSpec extends FunSuite {
  test("V37 configuration data survives migration to V38") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")
    val base = DatabaseConfig.fromEnvironment(sys.env).fold(throw _, identity)
    val url = "^(jdbc:postgresql://[^/]+/)[^?]+(\\?.*)?$".r
    val (prefix, suffix) = base.url match {
      case url(hostAndPort, query) => (hostAndPort, Option(query).getOrElse(""))
      case _ => fail("Integration test requires a PostgreSQL JDBC URL with explicit host and database")
    }
    val databaseName = "infradesk_upgrade_" + UUID.randomUUID().toString.replace("-", "")
    val testConfig = base.copy(url = s"$prefix$databaseName$suffix")
    val maintenanceUrl = s"${prefix}postgres$suffix"
    def sql(jdbcUrl: String, statement: String): Unit = {
      val connection = DriverManager.getConnection(jdbcUrl, base.user, base.password)
      try {
        val command = connection.createStatement()
        try command.execute(statement)
        finally command.close()
      } finally connection.close()
    }
    sql(maintenanceUrl, s"CREATE DATABASE $databaseName")
    try {
      val flyway = Flyway.configure().dataSource(testConfig.url, testConfig.user, testConfig.password)
        .locations("classpath:db/migration").target("37").load()
      assertEquals(flyway.migrate().migrationsExecuted, 37)
      val org = UUID.randomUUID()
      sql(testConfig.url, s"insert into organization (id, code, name) values ('$org', 'upgrade-test', 'Upgrade test')")
      sql(testConfig.url, s"insert into configuration_profile (id, organization_id, code, name, " +
        s"latest_revision_number, created_at, updated_at) values ('${UUID.randomUUID()}', '$org', " +
        "'preserved', 'Preserved profile', 1, now(), now())")
      val result = DatabaseMigrator.migrate(testConfig,
        Slf4jLogger.getLoggerFromName[IO]("test.database.migrator")).unsafeRunSync()
      assertEquals(result.migrationsApplied, 1)
      assertEquals(result.currentVersion, "38")
      val connection = DriverManager.getConnection(testConfig.url, testConfig.user, testConfig.password)
      try {
        val statement = connection.createStatement()
        try {
          val rows = statement.executeQuery("select (select count(*) from configuration_profile " +
            "where code = 'preserved'), (select count(*) from information_schema.tables " +
            "where table_schema = current_schema() and table_name in ('integration', 'integration_secret')), " +
            "(select count(*) from flyway_schema_history where version = '38' and success)")
          try {
            assert(rows.next())
            assertEquals(rows.getInt(1), 1)
            assertEquals(rows.getInt(2), 2)
            assertEquals(rows.getInt(3), 1)
          } finally rows.close()
        } finally statement.close()
      } finally connection.close()
    } finally sql(maintenanceUrl, s"DROP DATABASE $databaseName WITH (FORCE)")
  }

  test("Flyway applies V1 through V38 to an empty PostgreSQL database and is idempotent") {
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
        assertEquals(first.migrationsApplied, 38)
        assertEquals(first.currentVersion, "38")
        assertEquals(second.migrationsApplied, 0)
        assertEquals(second.currentVersion, "38")
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
                // One idempotency index per kind of target, plus the claim path.
                "and indexname in ('ux_notification_delivery_legacy_event', " +
                "'ux_notification_delivery_managed_event', 'ix_notification_delivery_claimable'))"
            )
            try {
              assert(outbox.next())
              assert(outbox.getString(1) != null)
              assertEquals(outbox.getInt(2), 3)
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
                "'ck_operation_execution_status', 'ck_operation_execution_lifecycle', " +
                "'ck_operation_execution_recover_after_at')), " +
                "(select count(*) from information_schema.columns where table_schema = current_schema() " +
                "and table_name = 'operation_execution' and column_name = 'recover_after_at' " +
                "and is_nullable = 'NO'), " +
                "(select indexdef like '%WHERE%''RUNNING''%' from pg_indexes " +
                "where schemaname = current_schema() " +
                "and indexname = 'ux_operation_execution_resource_running')"
            )
            try {
              assert(operations.next())
              assert(operations.getString(1) != null)
              assertEquals(operations.getInt(2), 2)
              assertEquals(operations.getInt(3), 4)
              // Every execution stores its own recovery deadline; the column cannot be absent.
              assertEquals(operations.getInt(4), 1)
              // The uniqueness that serializes remediation is the partial one, not a global one.
              assertEquals(operations.getBoolean(5), true)
            } finally operations.close()
            val history = statement.executeQuery(
              "select to_regclass('public.history_event'), " +
                "(select count(*) from pg_indexes where schemaname = current_schema() " +
                "and indexname in ('ix_history_event_organization_recent', " +
                "'ix_history_event_resource_recent')), " +
                "(select count(*) from information_schema.columns where table_schema = current_schema() " +
                "and table_name = 'history_event' and column_name in ('organization_id', " +
                "'event_type', 'source', 'resource_id', 'connection_id', 'incident_id', " +
                "'operation_execution_id', 'sync_session_id', 'actor_user_id', 'occurred_at', " +
                "'created_at')), " +
                "(select count(*) from information_schema.table_constraints " +
                "where table_schema = current_schema() and table_name = 'history_event' " +
                "and constraint_name in ('ck_history_event_type', 'ck_history_event_source', " +
                "'ck_history_event_actor'))"
            )
            try {
              assert(history.next())
              assert(history.getString(1) != null)
              assertEquals(history.getInt(2), 2)
              assertEquals(history.getInt(3), 11)
              assertEquals(history.getInt(4), 3)
            } finally history.close()
            // V37: labels, rules, normalized selectors, exclusions, issues and assignment provenance.
            val rules = statement.executeQuery(
              "select (select count(*) from information_schema.tables where table_schema = current_schema() and table_name in (" +
                "'resource_label', 'resource_label_state', 'configuration_assignment_rule', " +
                "'configuration_assignment_rule_project', 'configuration_assignment_rule_environment', " +
                "'configuration_assignment_rule_label', 'configuration_assignment_rule_exclusion', " +
                "'configuration_assignment_rule_issue')), " +
                "(select count(*) from information_schema.columns where table_schema = current_schema() " +
                "and table_name = 'configuration_assignment' and column_name = 'source_rule_id'), " +
                "(select count(*) from pg_constraint where conname in ('fk_configuration_assignment_source_rule', " +
                "'uq_configuration_assignment_rule_code', 'ck_configuration_assignment_rule_lease', " +
                "'fk_resource_label_resource_tenant', 'fk_configuration_assignment_rule_profile_tenant')), " +
                "(select count(*) from pg_indexes where schemaname = current_schema() and indexname in (" +
                "'ix_resource_label_selector', 'ix_configuration_assignment_rule_claim', " +
                "'ix_configuration_assignment_source_rule', 'ix_configuration_assignment_rule_issue_rule')), " +
                "(select count(*) from pg_constraint where conname = 'ck_audit_event_action' " +
                "and pg_get_constraintdef(oid) like '%CONFIGURATION_RULE_REVISION_PROMOTED%')"
            )
            try {
              assert(rules.next())
              assertEquals(rules.getInt(1), 8)
              assertEquals(rules.getInt(2), 1)
              assertEquals(rules.getInt(3), 5)
              assertEquals(rules.getInt(4), 4)
              assertEquals(rules.getInt(5), 1)
            } finally rules.close()
            val integrations = statement.executeQuery(
              "select (select count(*) from information_schema.tables where table_schema = current_schema() " +
                "and table_name in ('integration', 'integration_secret')), " +
                "(select count(*) from pg_constraint where conname = 'fk_integration_secret'), " +
                "(select count(*) from pg_indexes where schemaname = current_schema() " +
                "and indexname in ('ix_integration_organization_name', " +
                "'uq_integration_organization_lower_name'))"
            )
            try {
              assert(integrations.next())
              assertEquals(integrations.getInt(1), 2)
              assertEquals(integrations.getInt(2), 1)
              assertEquals(integrations.getInt(3), 2)
            } finally integrations.close()
            val incidentByResource = statement.executeQuery(
              "select count(*) from pg_indexes where schemaname = current_schema() " +
                "and indexname = 'ix_incident_resource_opened'"
            )
            try {
              assert(incidentByResource.next())
              // The per-resource incident path of the infrastructure read models (V31).
              assertEquals(incidentByResource.getInt(1), 1)
            } finally incidentByResource.close()
            val configuration = statement.executeQuery(
              "select to_regclass('public.configuration_profile'), to_regclass('public.configuration_revision'), " +
                "to_regclass('public.configuration_revision_variable'), " +
                "(select count(*) from information_schema.table_constraints where table_schema = current_schema() " +
                "and constraint_name in ('uq_configuration_profile_organization_code', " +
                "'uq_configuration_revision_profile_number', 'fk_configuration_revision_profile_tenant', " +
                "'fk_configuration_revision_variable_revision_tenant', 'ck_configuration_revision_variable_type', " +
                "'pk_configuration_revision_variable')), " +
                "(select count(*) from pg_trigger where not tgisinternal and tgname in " +
                "('tr_configuration_revision_immutable', 'tr_configuration_revision_variable_immutable')), " +
                "(select pg_get_constraintdef(oid) like '%CONFIGURATION_REVISION_CREATED%' from pg_constraint " +
                "where conname = 'ck_audit_event_action'), " +
                "(select pg_get_constraintdef(oid) like '%CONFIGURATION_PROFILE%' from pg_constraint " +
                "where conname = 'ck_audit_event_target_type')"
            )
            try {
              assert(configuration.next())
              (1 to 3).foreach(index => assert(configuration.getString(index) != null))
              // Codes per organization, numbers per profile, tenants carried through every relation.
              assertEquals(configuration.getInt(4), 6)
              // Revisions are immutable in the database itself, not only by convention.
              assertEquals(configuration.getInt(5), 2)
              assertEquals(configuration.getBoolean(6), true)
              assertEquals(configuration.getBoolean(7), true)
            } finally configuration.close()
            val assignments = statement.executeQuery(
              "select to_regclass('public.configuration_assignment'), to_regclass('public.configuration_assignment_value'), " +
                "(select count(*) from information_schema.table_constraints where table_schema = current_schema() " +
                "and constraint_name in ('fk_configuration_assignment_resource_tenant', " +
                "'fk_configuration_assignment_profile_tenant', 'fk_configuration_assignment_revision', " +
                "'fk_configuration_assignment_value_assignment_tenant', 'pk_configuration_assignment_value', " +
                "'ck_configuration_assignment_version', 'ck_configuration_assignment_target_path')), " +
                "(select indexdef like '%UNIQUE%' and indexdef like '%(organization_id, resource_id, target_path)%' " +
                "and indexdef like '%WHERE (removed_at IS NULL)%' from pg_indexes " +
                "where schemaname = current_schema() and indexname = 'ux_configuration_assignment_active_target'), " +
                "(select count(*) from information_schema.columns where table_schema = current_schema() " +
                "and table_name = 'configuration_assignment' and column_name in ('removed_at', 'version') " +
                "and ((column_name = 'removed_at' and is_nullable = 'YES') or (column_name = 'version' and is_nullable = 'NO'))), " +
                "(select count(*) from information_schema.columns where table_schema = current_schema() " +
                "and table_name = 'configuration_assignment' and column_name like '%connection%'), " +
                "(select pg_get_constraintdef(oid) like '%CONFIGURATION_ASSIGNMENT_REMOVED%' from pg_constraint " +
                "where conname = 'ck_audit_event_action'), " +
                "(select pg_get_constraintdef(oid) like '%CONFIGURATION_ASSIGNMENT%' from pg_constraint " +
                "where conname = 'ck_audit_event_target_type')"
            )
            try {
              assert(assignments.next())
              (1 to 2).foreach(index => assert(assignments.getString(index) != null))
              // Tenants carried through the resource, the profile, the exact revision and every value.
              assertEquals(assignments.getInt(3), 7)
              // One active owner per file on a resource; a removed assignment frees the path.
              assertEquals(assignments.getBoolean(4), true)
              assertEquals(assignments.getInt(5), 2)
              // Desired state, not transport: no connection column.
              assertEquals(assignments.getInt(6), 0)
              assertEquals(assignments.getBoolean(7), true)
              assertEquals(assignments.getBoolean(8), true)
            } finally assignments.close()
            val syncDeadline = statement.executeQuery(
              "select (select count(*) from information_schema.columns " +
                "where table_schema = current_schema() and table_name = 'sync_session' " +
                "and column_name = 'recover_after_at' and is_nullable = 'NO'), " +
                "(select count(*) from information_schema.table_constraints " +
                "where table_schema = current_schema() and table_name = 'sync_session' " +
                "and constraint_name = 'ck_sync_session_recover_after_at'), " +
                "(select count(*) from pg_indexes where schemaname = current_schema() " +
                "and indexname = 'ux_sync_session_running_connection')"
            )
            try {
              assert(syncDeadline.next())
              // Every session carries its own recovery deadline, and the running-slot invariant
              // of stage 4 is untouched by adding it.
              assertEquals(syncDeadline.getInt(1), 1)
              assertEquals(syncDeadline.getInt(2), 1)
              assertEquals(syncDeadline.getInt(3), 1)
            } finally syncDeadline.close()
            val secretKinds = statement.executeQuery(
              "select pg_get_constraintdef(oid) from pg_constraint " +
                "where conname = 'ck_connection_secret_kind'"
            )
            try {
              assert(secretKinds.next())
              val definition = secretKinds.getString(1)
              // A credential is a password or a private key; both encrypt into the same table.
              assert(definition.contains("SSH_PASSWORD"), definition)
              assert(definition.contains("SSH_CREDENTIAL"), definition)
            } finally secretKinds.close()
          } finally statement.close()
        } finally connection.close()
      }
      _ <- IO.blocking {
        val connection = DriverManager.getConnection(testConfig.url, testConfig.user, testConfig.password)
        try {
          val statement = connection.createStatement()
          try assertEquals(statement.executeUpdate(
            "update flyway_schema_history set checksum = checksum + 1 where version = '22'"
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
