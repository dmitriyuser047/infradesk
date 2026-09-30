package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.integration.{CreateIntegrationCommand, IntegrationManagement, UpdateIntegrationCommand}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.integration.{IntegrationProviderType, RemnawaveCredential}
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import integration.secret.IntegrationCredentialCipher
import integration.ssh.SecretEncryptionConfig
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.util.{Base64, UUID}

final class IntegrationLifecycleSpec extends FunSuite {
  test("integration lifecycle is tenant-scoped, encrypted, idempotent and audited") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"))
    val org = UUID.randomUUID(); val foreign = UUID.randomUUID(); val user = UUID.randomUUID()
    val token = "stage24a-database-private-token"
    val cipher = IntegrationCredentialCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(Map(
      "INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(Array.fill[Byte](32)(9)))).toOption.get)
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val run = new DoobieTransactionRunner(xa)
      val repository = new PostgresIntegrationRepository
      val secrets = new PostgresIntegrationSecretRepository
      val audit = new AuditRecorder[ConnectionIO](new PostgresAuditEventRepository,
        new ConnectionIOIdGenerator, new ConnectionIOTimeProvider)
      val management = new IntegrationManagement[ConnectionIO](repository, secrets,
        new ConnectionIOIdGenerator, new ConnectionIOTimeProvider, cipher, audit,
        new PostgresIntegrationSyncStateRepository, new PostgresIntegrationActionRepository,
        new PostgresIntegrationInventoryRepository,
        new PostgresIntegrationConfigProfileRepository(integration.secret.RemnawaveConfigCipher.fromConfig(
          SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" ->
            Base64.getEncoder.encodeToString(Array.fill[Byte](32)(9)))).toOption.get)),
        new PostgresIntegrationConfigDeploymentRepository().hasActive)
      val actor = ActorContext(user, org)
      val setup: ConnectionIO[Unit] = for {
        _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'Integrations')".update.run
        _ <- sql"insert into organization (id, code, name) values ($foreign, ${foreign.toString}, 'Foreign')".update.run
        _ <- sql"""insert into user_account (id, email, password_hash, display_name, created_at, updated_at)
          values ($user, ${s"$user@example.test"}, 'x', 'Integration actor', now(), now())""".update.run
        _ <- sql"""insert into organization_membership (user_id, organization_id, role, created_at, updated_at)
          values ($user, $org, 'OWNER', now(), now())""".update.run
      } yield ()
      val cleanup: ConnectionIO[Unit] = for {
        _ <- sql"delete from audit_event where organization_id = $org".update.run
        _ <- sql"delete from integration where organization_id = $org".update.run
        _ <- sql"delete from integration_secret where organization_id = $org".update.run
        _ <- sql"delete from organization_membership where user_id = $user".update.run
        _ <- sql"delete from user_account where id = $user".update.run
        _ <- sql"delete from organization where id in ($org, $foreign)".update.run
      } yield ()
      val program = for {
        _ <- run.run(setup)
        schema <- run.run(sql"select version from flyway_schema_history where success order by installed_rank desc limit 1".query[String].unique)
        first <- run.run(management.create(actor, CreateIntegrationCommand(" Main ", IntegrationProviderType.Remnawave,
          "https://panel.example.test/", RemnawaveCredential(token, Some("caddy-secret")))))
        list <- run.run(management.list(org))
        hidden <- run.run(management.get(foreign, first.id))
        raw <- run.run(sql"select ciphertext from integration_secret where id = ${first.secretId}".query[Array[Byte]].unique)
        kept <- run.run(management.update(actor, first.id,
          UpdateIntegrationCommand("Renamed", "https://panel.example.test/prefix", None)))
        oldSecret <- run.run(secrets.find(org, first.secretId))
        replaced <- run.run(management.update(actor, first.id,
          UpdateIntegrationCommand("Renamed", "https://panel.example.test/prefix",
            Some(RemnawaveCredential("replacement-token", None)))))
        removedSecret <- run.run(secrets.find(org, first.secretId))
        nextSecret <- run.run(secrets.find(org, replaced.secretId))
        enabled <- run.run(management.setEnabled(actor, first.id, enabled = true))
        repeatedEnable <- run.run(management.setEnabled(actor, first.id, enabled = true))
        disabled <- run.run(management.setEnabled(actor, first.id, enabled = false))
        repeatedDisable <- run.run(management.setEnabled(actor, first.id, enabled = false))
        eventsBeforeDelete <- run.run(sql"select action from audit_event where organization_id = $org order by occurred_at, id".query[String].to[List])
        _ <- run.run(management.delete(actor, first.id))
        gone <- run.run(management.get(org, first.id))
        noSecret <- run.run(secrets.find(org, replaced.secretId))
        events <- run.run(sql"select action from audit_event where organization_id = $org".query[String].to[List])
      } yield {
        assertEquals(schema, "43")
        assert(!first.enabled)
        assertEquals(first.name, "Main")
        assertEquals(list.map(_.id), List(first.id))
        assertEquals(hidden, None)
        assert(!new String(raw, "UTF-8").contains(token))
        assertEquals(kept.secretId, first.secretId)
        assert(oldSecret.nonEmpty)
        assert(replaced.secretId != first.secretId)
        assert(!replaced.caddyApiKeyConfigured)
        assertEquals(removedSecret, None)
        assertEquals(nextSecret.map(cipher.decrypt), Some(RemnawaveCredential("replacement-token", None)))
        assert(enabled.enabled && repeatedEnable.enabled && !disabled.enabled && !repeatedDisable.enabled)
        assertEquals(eventsBeforeDelete.count(_ == "INTEGRATION_ENABLED"), 1)
        assertEquals(eventsBeforeDelete.count(_ == "INTEGRATION_DISABLED"), 1)
        assertEquals(eventsBeforeDelete.count(_ == "INTEGRATION_UPDATED"), 2)
        assertEquals(gone, None)
        assertEquals(noSecret, None)
        assertEquals(events.count(_ == "INTEGRATION_DELETED"), 1)
      }
      program.guarantee(run.run(cleanup))
    }.unsafeRunSync()
  }
}
