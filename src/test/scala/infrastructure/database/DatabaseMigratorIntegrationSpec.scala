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
  test("V43 configuration, integration and desired-state data survive migration to V45") {
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
        .locations("classpath:db/migration").target("43").load()
      assertEquals(flyway.migrate().migrationsExecuted, 43)
      val org = UUID.randomUUID(); val integration = UUID.randomUUID(); val secret = UUID.randomUUID()
      val user = UUID.randomUUID(); val project = UUID.randomUUID(); val environment = UUID.randomUUID()
      val resource = UUID.randomUUID(); val session = UUID.randomUUID(); val inventory = UUID.randomUUID()
      val profile = UUID.randomUUID(); val revision = UUID.randomUUID(); val desired = UUID.randomUUID()
      val configObject = UUID.randomUUID(); val remoteProfile = UUID.randomUUID()
      val remoteRevision = UUID.randomUUID(); val configBinding = UUID.randomUUID()
      val configDeployment = UUID.randomUUID(); val remoteHash = "a" * 64
      sql(testConfig.url, s"insert into organization (id, code, name) values ('$org', 'upgrade-test', 'Upgrade test')")
      sql(testConfig.url, s"insert into user_account (id, email, password_hash, display_name, created_at, updated_at) " +
        s"values ('$user', '$user@example.test', 'x', 'Upgrade actor', now(), now())")
      sql(testConfig.url, s"insert into project (id, organization_id, code, name) values ('$project', '$org', 'upgrade', 'Upgrade')")
      sql(testConfig.url, s"insert into environment (id, organization_id, project_id, code, name, kind) " +
        s"values ('$environment', '$org', '$project', 'production', 'Production', 'PROD')")
      sql(testConfig.url, s"insert into resource (id, organization_id, environment_id, resource_type_id, code, name) " +
        s"values ('$resource', '$org', '$environment', '10000000-0000-0000-0000-000000000001', 'node', 'Node')")
      sql(testConfig.url, s"insert into configuration_profile (id, organization_id, code, name, " +
        s"latest_revision_number, created_at, updated_at) values ('$profile', '$org', " +
        "'preserved', 'Preserved profile', 1, now(), now())")
      sql(testConfig.url, s"insert into configuration_revision (id, organization_id, profile_id, revision_number, " +
        s"template_text, created_by_user_id, created_at) values ('$revision', '$org', '$profile', 1, " +
        s"'original-template', '$user', now())")
      sql(testConfig.url, s"insert into integration_secret (id, organization_id, kind, nonce, ciphertext, created_at) " +
        s"values ('$secret', '$org', 'INTEGRATION_CREDENTIAL', decode('000102030405060708090a0b', 'hex'), " +
        "decode('00112233445566778899aabbccddeeff0011', 'hex'), now())")
      sql(testConfig.url, s"insert into integration (id, organization_id, name, provider_type, base_url, enabled, " +
        s"secret_id, caddy_api_key_configured, created_at, updated_at) values ('$integration', '$org', 'Panel', " +
        s"'REMNAWAVE', 'https://panel.example.test', true, '$secret', true, now(), now())")
      sql(testConfig.url, s"insert into integration_sync_state (organization_id, integration_id, next_run_at, updated_at) " +
        s"values ('$org', '$integration', now(), now())")
      sql(testConfig.url, s"insert into integration_sync_session (id, organization_id, integration_id, trigger, " +
        s"requested_by_user_id, started_at, recover_after_at, finished_at, status) values " +
        s"('$session', '$org', '$integration', 'MANUAL', '$user', now(), now() + interval '1 minute', now(), 'COMPLETED')")
      sql(testConfig.url, s"insert into integration_inventory_object (id, organization_id, integration_id, " +
        s"object_type, external_id, display_name, summary_version, summary, is_active, first_seen_at, last_seen_at, " +
        s"last_seen_sync_session_id, created_at, updated_at) values ('$inventory', '$org', '$integration', " +
        s"'NODE', '${UUID.randomUUID()}', 'Upgrade node', 1, '{}'::jsonb, true, now(), now(), '$session', now(), now())")
      sql(testConfig.url, s"insert into integration_inventory_object (id, organization_id, integration_id, " +
        s"object_type, external_id, display_name, summary_version, summary, is_active, first_seen_at, last_seen_at, " +
        s"last_seen_sync_session_id, created_at, updated_at) values ('$configObject', '$org', '$integration', " +
        s"'CONFIG_PROFILE', '${UUID.randomUUID()}', 'Remote config', 1, '{}'::jsonb, true, now(), now(), '$session', now(), now())")
      sql(testConfig.url, s"insert into configuration_profile (id, organization_id, code, name, kind, " +
        s"latest_revision_number, created_at, updated_at) values ('$remoteProfile', '$org', 'remote-preserved', " +
        s"'Remote preserved', 'REMNAWAVE_CONFIG', 1, now(), now())")
      sql(testConfig.url, s"insert into configuration_revision (id, organization_id, profile_id, revision_number, " +
        s"template_text, created_by_user_id, created_at) values ('$remoteRevision', '$org', '$remoteProfile', 1, '', '$user', now())")
      sql(testConfig.url, s"insert into configuration_revision_secure_payload (revision_id, organization_id, " +
        s"profile_id, purpose, nonce, ciphertext, content_sha256, created_at) values ('$remoteRevision', '$org', " +
        s"'$remoteProfile', 'infradesk/configuration/remnawave/v1', decode('000102030405060708090a0b','hex'), " +
        s"decode('00112233445566778899aabbccddeeff0011','hex'), '$remoteHash', now())")
      sql(testConfig.url, s"insert into integration_config_profile_binding (id, organization_id, integration_id, " +
        s"inventory_object_id, configuration_profile_id, created_by_user_id, created_at, updated_at) values " +
        s"('$configBinding', '$org', '$integration', '$configObject', '$remoteProfile', '$user', now(), now())")
      sql(testConfig.url, s"insert into integration_config_deployment (id, organization_id, integration_id, " +
        s"inventory_object_id, binding_id, configuration_profile_id, configuration_revision_id, revision_number, " +
        s"request_id, requested_by_user_id, status, expected_remote_sha256, desired_sha256, created_at, finished_at) " +
        s"values ('$configDeployment', '$org', '$integration', '$configObject', '$configBinding', '$remoteProfile', " +
        s"'$remoteRevision', 1, '${UUID.randomUUID()}', '$user', 'SUCCEEDED', '$remoteHash', '$remoteHash', now(), now())")
      sql(testConfig.url, s"insert into integration_resource_binding (id, organization_id, integration_id, " +
        s"inventory_object_id, resource_id, created_by_user_id, created_at, updated_at) values " +
        s"('${UUID.randomUUID()}', '$org', '$integration', '$inventory', '$resource', '$user', now(), now())")
      sql(testConfig.url, s"update integration set management_mode = 'MANAGED_SELECTED' where id = '$integration'")
      sql(testConfig.url, s"insert into integration_desired_state (id, organization_id, integration_id, " +
        s"inventory_object_id, desired_state, version, set_by_user_id, created_at, updated_at, next_reconcile_at) " +
        s"values ('$desired', '$org', '$integration', '$inventory', 'ENABLED', 1, '$user', now(), now(), now())")
      // Manual action history written by V41 remains manual after the upgrade.
      sql(testConfig.url, s"insert into integration_action_execution (id, organization_id, integration_id, " +
        s"inventory_object_id, request_id, action_code, external_id_snapshot, display_name_snapshot, " +
        s"requested_by_user_id, status, created_at, finished_at, updated_at) values ('${UUID.randomUUID()}', '$org', " +
        s"'$integration', '$inventory', '${UUID.randomUUID()}', 'NODE_RESTART', 'x', 'Upgrade node', '$user', " +
        "'SUCCEEDED', now(), now(), now())")
      val result = DatabaseMigrator.migrate(testConfig,
        Slf4jLogger.getLoggerFromName[IO]("test.database.migrator")).unsafeRunSync()
      assertEquals(result.migrationsApplied, 2)
      assertEquals(result.currentVersion, "45")
      val connection = DriverManager.getConnection(testConfig.url, testConfig.user, testConfig.password)
      try {
        val statement = connection.createStatement()
        try {
          val rows = statement.executeQuery("select (select count(*) from configuration_profile " +
            "where code = 'preserved' and kind = 'FILE_TEMPLATE'), " +
            s"(select count(*) from integration i join integration_secret s on s.id = i.secret_id " +
            s"where i.id = '$integration' and i.enabled and i.caddy_api_key_configured " +
            "and s.ciphertext = decode('00112233445566778899aabbccddeeff0011', 'hex')), " +
            s"(select count(*) from integration_sync_state where integration_id = '$integration' " +
            "and consecutive_failures = 0 and claim_token is null), " +
            "(select count(*) from flyway_schema_history where version = '44' and success), " +
            "(select count(*) from flyway_schema_history where version = '45' and success), " +
            s"(select count(*) from integration_inventory_object where id = '$inventory' and display_name = 'Upgrade node'), " +
            s"(select count(*) from integration_resource_binding where inventory_object_id = '$inventory'), " +
            s"(select count(*) from integration_sync_session where id = '$session' and status = 'COMPLETED'), " +
            s"(select count(*) from integration where id = '$integration' and management_mode = 'MANAGED_SELECTED'), " +
            s"(select count(*) from integration_desired_state where id = '$desired' and desired_state = 'ENABLED'), " +
            s"(select count(*) from integration_action_execution where integration_id = '$integration' " +
            "and source = 'MANUAL' and desired_state_id_snapshot is null and desired_state_version_snapshot is null), " +
            s"(select count(*) from configuration_revision where id = '$revision' and template_text = 'original-template'), " +
            s"(select count(*) from integration_config_deployment where id = '$configDeployment' and source = 'MANUAL' and rollout_id is null), " +
            "(select count(*) from information_schema.tables where table_schema = current_schema() and table_name in " +
            "('integration_config_rollout','integration_config_rollout_node_baseline'))")
          try {
            assert(rows.next())
            assertEquals(rows.getInt(1), 1)
            assertEquals(rows.getInt(2), 1)
            assertEquals(rows.getInt(3), 1)
            assertEquals(rows.getInt(4), 1)
            assertEquals(rows.getInt(5), 1)
            assertEquals(rows.getInt(6), 1)
            assertEquals(rows.getInt(7), 1)
            assertEquals(rows.getInt(8), 1)
            assertEquals(rows.getInt(9), 1)
            assertEquals(rows.getInt(10), 1)
            assertEquals(rows.getInt(11), 1)
            assertEquals(rows.getInt(12), 1)
            assertEquals(rows.getInt(13), 1)
            assertEquals(rows.getInt(14), 2)
          } finally rows.close()
        } finally statement.close()
      } finally connection.close()
    } finally sql(maintenanceUrl, s"DROP DATABASE $databaseName WITH (FORCE)")
  }

  test("Flyway applies V1 through V45 to an empty PostgreSQL database and is idempotent") {
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
        assertEquals(first.migrationsApplied, 45)
        assertEquals(first.currentVersion, "45")
        assertEquals(second.migrationsApplied, 0)
        assertEquals(second.currentVersion, "45")
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
