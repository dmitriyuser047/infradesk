package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.integration._
import application.port.{ClaimedIntegrationSync, InventoryFilter, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.integration._
import domain.configuration.CanonicalJson
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import infrastructure.runtime.SystemTimeProvider
import integration.secret.IntegrationCredentialCipher
import integration.secret.RemnawaveConfigCipher
import integration.ssh.SecretEncryptionConfig
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.log4cats.noop.NoOpLogger
import io.circe.Json
import io.circe.parser.parse

import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration._

final class IntegrationInventoryIntegrationSpec extends FunSuite with IntegrationDesiredStateTests {
  override val munitTimeout: Duration = 120.seconds
  private val NodeType = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ContainerType = UUID.fromString("10000000-0000-0000-0000-000000000002")
  private[postgres] val cipher = IntegrationCredentialCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(Map(
    "INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))).toOption.get)

  private[postgres] def node(uuid: String, name: String, disabled: Boolean = false, address: String = "203.0.113.1") =
    ObservedIntegrationObject(IntegrationObjectType.Node, uuid, name, RemnawaveNodeSummary(address, Some(2222),
      isConnected = !disabled, isConnecting = false, isDisabled = disabled, None, Some("25.1"), None, 0L,
      trafficTrackingActive = false, None, None, 0L, "DE", None, None, None, None, List("eu"), None, None))
  private[postgres] val host = ObservedIntegrationObject(IntegrationObjectType.Host, "host-1", "Main host",
    RemnawaveHostSummary("vpn.example.test", 443, isDisabled = false, isHidden = false, Some("profile-1"), None,
      List("node-a"), Nil, "TLS", None))
  private val profile = ObservedIntegrationObject(IntegrationObjectType.ConfigProfile, "profile-1", "Default",
    RemnawaveConfigProfileSummary(1, Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
      List("node-a"), List(RemnawaveInboundSummary("inbound-1", "VLESS", "vless", Some("tcp"), None, Some(443)))))
  private[postgres] def snapshot(objects: ObservedIntegrationObject*) =
    IntegrationObservation(objects.toList, IntegrationObjectType.All.toSet)

  private[postgres] final class World(val xa: org.typelevel.doobie.hikari.HikariTransactor[IO]) {
    val org = UUID.randomUUID(); val foreign = UUID.randomUUID(); val user = UUID.randomUUID()
    val project = UUID.randomUUID(); val environment = UUID.randomUUID()
    val foreignProject = UUID.randomUUID(); val foreignEnvironment = UUID.randomUUID()
    val nodeResource = UUID.randomUUID(); val otherNode = UUID.randomUUID(); val container = UUID.randomUUID()
    val archived = UUID.randomUUID(); val foreignNode = UUID.randomUUID()
    val run = new DoobieTransactionRunner(xa)
    val actor = ActorContext(user, org)
    val ids = new ConnectionIOIdGenerator; val time = new ConnectionIOTimeProvider
    val audit = new AuditRecorder[ConnectionIO](new PostgresAuditEventRepository, ids, time)
    val integrations = new PostgresIntegrationRepository
    val secrets = new PostgresIntegrationSecretRepository
    val sessions = new PostgresIntegrationSyncSessionRepository
    val inventory = new PostgresIntegrationInventoryRepository
    val states = new PostgresIntegrationSyncStateRepository
    val bindingRepository = new PostgresIntegrationBindingRepository
    val query = new PostgresIntegrationInventoryQuery
    val actionRepository = new PostgresIntegrationActionRepository
    val configRepository = new PostgresIntegrationConfigProfileRepository(RemnawaveConfigCipher.fromConfig(
      SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" ->
        Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))).toOption.get))
    val configDeployments = new PostgresIntegrationConfigDeploymentRepository
    val configRollouts = new PostgresIntegrationConfigRolloutRepository
    @volatile var remoteConfig: Json = parse("""{"privateKey":"SUPER-SECRET-PRIVATE-KEY","a":1}""").toOption.get
    @volatile var configPatchCalls = 0
    @volatile var configGetCalls = 0
    @volatile var configWriteOutcome: IntegrationActionRemoteOutcome = IntegrationActionRemoteOutcome.Succeeded
    @volatile var remoteDocumentId: Option[String] = None
    @volatile var beforeConfigFetch: IO[Unit] = IO.unit
    val configTransport = new IntegrationConfigProfileTransport[IO] {
      override def fetchConfigProfile(context: IntegrationRuntimeContext, externalId: String) =
        beforeConfigFetch *> IO {
          configGetCalls += 1
          IntegrationConfigProfileDocument(remoteDocumentId.getOrElse(externalId), remoteConfig, None)
        }
      override def updateConfigProfile(context: IntegrationRuntimeContext, externalId: String,
        config: Json, desiredSha256: String) = IO {
        configPatchCalls += 1
        if (configWriteOutcome == IntegrationActionRemoteOutcome.Succeeded) remoteConfig = config
        configWriteOutcome
      }
    }
    val management = new IntegrationManagement[ConnectionIO](integrations, secrets, ids, time, cipher, audit, states,
      actionRepository, inventory, configRepository, configRollouts)
    @volatile var observation: IO[IntegrationObservation] = IO.pure(snapshot())
    @volatile var remoteActionCalls = 0
    @volatile var remoteOutcome: IO[IntegrationActionRemoteOutcome] =
      IO.pure(IntegrationActionRemoteOutcome.Succeeded)
    val provider: IntegrationProvider[IO] = new IntegrationProvider[IO] {
      override val providerType = IntegrationProviderType.Remnawave
      override val displayName = "Remnawave"
      override val capabilities: Set[IntegrationCapability] =
        Set(IntegrationCapability.SafeActions, IntegrationCapability.DesiredState,
          IntegrationCapability.ConfigProfileManagement)
      override def configProfiles: Option[IntegrationConfigProfileTransport[IO]] = Some(configTransport)
      override def testConnection(context: IntegrationRuntimeContext) = IO.raiseError(new IllegalStateException)
      override def observe(context: IntegrationRuntimeContext) = observation
      override def executeAction(context: IntegrationRuntimeContext, externalId: String,
        action: IntegrationActionCode): IO[IntegrationActionRemoteOutcome] =
        IO { remoteActionCalls += 1 } *> remoteOutcome
    }
    val sync = new IntegrationSync[ConnectionIO](new IntegrationSyncTransactions[ConnectionIO](integrations, secrets,
      sessions, inventory, ids, time, audit, new PostgresIntegrationDesiredStateRepository), run, cipher,
      new IntegrationProviderRegistry[IO](List(provider)),
      NoOpLogger[IO], 10.seconds, 1000)
    val bindings = new IntegrationBindings[ConnectionIO](integrations, inventory, bindingRepository, ids, time, audit)
    val actionService = new IntegrationActions[ConnectionIO](integrations, inventory, actionRepository,
      secrets, new IntegrationProviderRegistry[IO](List(provider)), ids, time, audit,
      new PostgresIntegrationDesiredStateRepository)

    val desiredRepository = new PostgresIntegrationDesiredStateRepository
    val registry = new IntegrationProviderRegistry[IO](List(provider))
    val configProfiles = new IntegrationConfigProfiles[ConnectionIO](integrations, inventory, secrets, cipher,
      registry, new PostgresConfigurationProfileRepository, new PostgresConfigurationProfileQuery,
      configRepository, configDeployments, configRollouts, ids, time, audit, run, states, NoOpLogger[IO])
    val configWorker = new IntegrationConfigDeploymentWorker[ConnectionIO](configDeployments,
      configRepository, integrations, inventory, secrets, cipher, registry, states, run,
      new SystemTimeProvider, NoOpLogger[IO], 1.second, 20, 2, 2.seconds, UUID.randomUUID())
    val configRolloutService = new IntegrationConfigRollouts[ConnectionIO](integrations, inventory,
      new PostgresConfigurationProfileQuery, configRepository, configDeployments, configRollouts,
      ids, time, audit, states, run, operational = true)
    def configRolloutWorker(verifyTimeout: FiniteDuration = 1.second) =
      new IntegrationConfigRolloutWorker[ConnectionIO](configRollouts, integrations, states, run,
        new SystemTimeProvider, NoOpLogger[IO], 1.second, 20, 2, 30.seconds,
        verifyTimeout, UUID.randomUUID())
    def desiredStates(desiredStateOperational: Boolean = true) = new IntegrationDesiredStates[ConnectionIO](
      integrations, inventory, desiredRepository, registry, ids, time, audit, desiredStateOperational)
    /** Has the repository and a clock, and nothing that could reach a provider. */
    def desiredWorker(batch: Int = 50, concurrency: Int = 1) = new IntegrationDesiredStateWorker[ConnectionIO](
      desiredRepository, run, new SystemTimeProvider, NoOpLogger[IO],
      IntegrationDesiredStateSettings(1.second, batch, concurrency, 30.seconds, 1.hour), UUID.randomUUID())
    def actionWorker(batch: Int = 50) = new IntegrationActionWorker[ConnectionIO](actionRepository, integrations,
      secrets, cipher, registry, run, new SystemTimeProvider, NoOpLogger[IO], 1.second, batch = batch,
      concurrency = 4, requestTimeout = 2.seconds, owner = UUID.randomUUID())

    def create(name: String): Integration = run.run(management.create(actor, CreateIntegrationCommand(name,
      IntegrationProviderType.Remnawave, "https://panel.example.test", RemnawaveCredential("tok", None)))).unsafeRunSync()
    def manual(id: UUID): IntegrationSyncSession = sync.manual(actor, id).unsafeRunSync()
    def objects(id: UUID): List[(String, String, Boolean, Instant, UUID, UUID)] = run.run(
      sql"""select external_id, display_name, is_active, updated_at, id, last_seen_sync_session_id
            from integration_inventory_object where integration_id = $id order by external_id"""
        .query[(String, String, Boolean, Instant, UUID, UUID)].to[List]).unsafeRunSync()
    def actions: List[String] = run.run(sql"select action from audit_event where organization_id = $org"
      .query[String].to[List]).unsafeRunSync()
    def page(id: UUID, filter: InventoryFilter) =
      run.run(query.list(org, id, IntegrationObjectType.Node, filter)).unsafeRunSync()

    def setUp: IO[Unit] = run.run(for {
      _ <- List(org -> "inv", foreign -> "inv-foreign").traverse_ { case (id, prefix) =>
        sql"insert into organization (id, code, name) values ($id, ${s"$prefix-$id"}, 'Inventory')".update.run }
      _ <- sql"""insert into user_account (id, email, password_hash, display_name, created_at, updated_at)
                 values ($user, ${s"$user@example.test"}, 'x', 'Inventory actor', now(), now())""".update.run
      _ <- sql"insert into project (id, organization_id, code, name) values ($project, $org, 'p', 'Edge')".update.run
      _ <- sql"""insert into environment (id, organization_id, project_id, code, name, kind)
                 values ($environment, $org, $project, 'prod', 'Production', 'PROD')""".update.run
      _ <- sql"insert into project (id, organization_id, code, name) values ($foreignProject, $foreign, 'p', 'F')".update.run
      _ <- sql"""insert into environment (id, organization_id, project_id, code, name, kind)
                 values ($foreignEnvironment, $foreign, $foreignProject, 'prod', 'F', 'PROD')""".update.run
      _ <- List((nodeResource, org, environment, NodeType, "vps-frankfurt", true),
        (otherNode, org, environment, NodeType, "vps-amsterdam", true),
        (container, org, environment, ContainerType, "container", true),
        (archived, org, environment, NodeType, "vps-archived", false),
        (foreignNode, foreign, foreignEnvironment, NodeType, "vps-foreign", true)).traverse_ {
        case (id, owner, env, kind, code, active) =>
          sql"""insert into resource (id, organization_id, environment_id, resource_type_id, code, name, is_active)
                values ($id, $owner, $env, $kind, $code, $code, $active)""".update.run
      }
    } yield ())

    def cleanUp: IO[Unit] = run.run(List(org, foreign).traverse_ { id =>
      for {
        _ <- sql"set local session_replication_role='replica'".update.run
        _ <- sql"delete from audit_event where organization_id = $id".update.run
        _ <- sql"delete from integration where organization_id = $id".update.run
        _ <- sql"delete from configuration_revision_secure_payload where organization_id = $id".update.run
        _ <- sql"delete from configuration_revision where organization_id = $id".update.run
        _ <- sql"delete from configuration_profile where organization_id = $id".update.run
        _ <- sql"delete from integration_secret where organization_id = $id".update.run
        _ <- sql"delete from resource where organization_id = $id".update.run
        _ <- sql"delete from environment where organization_id = $id".update.run
        _ <- sql"delete from project where organization_id = $id".update.run
      } yield ()
    } *> sql"delete from user_account where id = $user".update.run *>
      sql"delete from organization where id in ($org, $foreign)".update.run.void)
  }

  private[postgres] def withWorld(body: World => Unit): Unit = {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"))
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val world = new World(xa)
      (world.setUp *> IO.blocking(body(world))).guarantee(world.cleanUp)
    }.unsafeRunSync()
  }

  test("managed config adoption, encrypted revisions and preflight-protected durable deployment") {
    withWorld { w =>
      val external = UUID.randomUUID().toString
      def observed(config: Json) = ObservedIntegrationObject(IntegrationObjectType.ConfigProfile,
        external, "Default", RemnawaveConfigProfileSummary(1, Instant.parse("2026-08-01T00:00:00Z"),
          Instant.parse("2026-09-01T00:00:00Z"), List("node-a"), Nil, Some(CanonicalJson.sha256(config))))
      val initial = w.remoteConfig
      val integration = w.create("Config management")
      w.observation = IO.pure(snapshot(observed(initial)))
      w.manual(integration.id)
      val objectId = w.objects(integration.id).head._5
      val adopted = w.configProfiles.adopt(w.actor, integration.id, objectId,
        AdoptIntegrationConfigProfile("remnawave-main", "Main Remnawave", None)).unsafeRunSync()
      assertEquals(adopted.kind.code, "REMNAWAVE_CONFIG")
      assertEquals(adopted.latestRevisionNumber, 1)
      val (template, ciphertext) = w.run.run(sql"""select r.template_text, s.ciphertext
        from configuration_revision r join configuration_revision_secure_payload s on s.revision_id = r.id
        where r.profile_id = ${adopted.id}""".query[(String, Array[Byte])].unique).unsafeRunSync()
      assertEquals(template, "")
      assert(!new String(ciphertext, "UTF-8").contains("SUPER-SECRET-PRIVATE-KEY"))
      val imported = w.configProfiles.revisionContent(w.org, integration.id, objectId, 1).unsafeRunSync()
      assertEquals(imported.contentSha256, CanonicalJson.sha256(initial))
      assert(imported.canonicalJson.contains("SUPER-SECRET-PRIVATE-KEY"))
      assertEquals(w.configProfiles.adopt(w.actor, integration.id, objectId,
        AdoptIntegrationConfigProfile("duplicate", "Duplicate", None)).attempt.unsafeRunSync()
        .left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_CONFIG_PROFILE_ALREADY_MANAGED"))
      assertEquals(w.configProfiles.managed(w.org, integration.id, objectId).unsafeRunSync().get.status,
        IntegrationConfigStatus.WaitingRefresh)
      w.manual(integration.id)
      assertEquals(w.configProfiles.managed(w.org, integration.id, objectId).unsafeRunSync().get.status,
        IntegrationConfigStatus.InSync)
      val profilePage = w.run.run(w.query.list(w.org, integration.id, IntegrationObjectType.ConfigProfile,
        InventoryFilter(None, None, None, 50, 0))).unsafeRunSync()
      assertEquals(profilePage.items.head.configManagement.map(_.configurationProfileId), Some(adopted.id))
      assertEquals(profilePage.items.head.configManagement.map(_.revisionNumber), Some(1))
      assertEquals(profilePage.items.head.configManagement.map(_.status), Some("IN_SYNC"))

      val next = parse("""{"a":2,"privateKey":"SUPER-SECRET-PRIVATE-KEY"}""").toOption.get
      assertEquals(w.configProfiles.appendRevision(w.actor, integration.id, objectId, next).unsafeRunSync(), 2)
      assertEquals(w.configProfiles.managed(w.org, integration.id, objectId).unsafeRunSync().get.status,
        IntegrationConfigStatus.LocalChanges)
      val preview = w.configProfiles.preview(w.org, integration.id, objectId, 2).unsafeRunSync()
      assert(preview.changed)
      assertEquals(preview.remoteSha256, CanonicalJson.sha256(initial))
      val requestId = UUID.randomUUID()
      val queued = w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 2, requestId).unsafeRunSync()
      assertEquals(queued.status, IntegrationConfigDeploymentStatus.Queued)
      assertEquals(w.configPatchCalls, 0)
      assertEquals(w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 2, requestId).unsafeRunSync().id,
        queued.id)
      assertEquals(w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 1, requestId).attempt.unsafeRunSync()
        .left.toOption.collect { case e: IntegrationError => e.code }, Some("CONFIG_DEPLOYMENT_REQUEST_ID_CONFLICT"))
      assertEquals(w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 2,
        UUID.randomUUID()).attempt.unsafeRunSync().left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_CONFIG_DEPLOYMENT_ALREADY_RUNNING"))
      val targetEdit = w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Config management", "https://other.example.test", None))).attempt.unsafeRunSync()
      val credentialEdit = w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Config management", "https://panel.example.test",
          Some(RemnawaveCredential("new-token", None))))).attempt.unsafeRunSync()
      val delete = w.run.run(w.management.delete(w.actor, integration.id)).attempt.unsafeRunSync()
      List(targetEdit, credentialEdit, delete).foreach(result =>
        assertEquals(result.left.toOption.collect { case e: IntegrationError => e.code },
          Some("INTEGRATION_ACTION_ALREADY_RUNNING")))
      assertEquals(w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Renamed", "https://panel.example.test", None))).unsafeRunSync().name, "Renamed")
      w.configWorker.tick.unsafeRunSync()
      assertEquals(w.configPatchCalls, 1)
      assertEquals(w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("After deploy", "https://panel.example.test", None))).unsafeRunSync().name,
        "After deploy")
      val succeeded = w.run.run(w.configDeployments.recent(w.org, integration.id, objectId, 1)).unsafeRunSync().head
      assertEquals(succeeded.status, IntegrationConfigDeploymentStatus.Succeeded)
      assertEquals(w.configProfiles.managed(w.org, integration.id, objectId).unsafeRunSync().get.status,
        IntegrationConfigStatus.WaitingRefresh)
      // PATCH never rewrites the inventory. Only the ordinary synchronization observes the result.
      assertEquals(w.run.run(w.inventory.findObject(w.org, integration.id, objectId, false)).unsafeRunSync()
        .get.summary.asInstanceOf[RemnawaveConfigProfileSummary].configSha256, Some(CanonicalJson.sha256(initial)))
      w.observation = IO.pure(snapshot(observed(next)))
      w.manual(integration.id)
      assertEquals(w.configProfiles.managed(w.org, integration.id, objectId).unsafeRunSync().get.status,
        IntegrationConfigStatus.InSync)

      // A remote edit after the last observation must stop the next queued write before PATCH.
      val conflict = w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 2,
        UUID.randomUUID()).unsafeRunSync()
      w.remoteConfig = parse("""{"a":3,"privateKey":"SUPER-SECRET-PRIVATE-KEY"}""").toOption.get
      w.configWorker.tick.unsafeRunSync()
      val failed = w.run.run(w.configDeployments.findByRequest(w.org, conflict.requestId)).unsafeRunSync().get
      assertEquals(failed.status, IntegrationConfigDeploymentStatus.Failed)
      assertEquals(failed.errorCode, Some("INTEGRATION_CONFIG_REMOTE_CHANGED"))
      assertEquals(w.configPatchCalls, 1)
      val externalEdit = w.remoteConfig
      w.observation = IO.pure(snapshot(observed(externalEdit)))
      w.manual(integration.id)
      val ambiguous = w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 2,
        UUID.randomUUID()).unsafeRunSync()
      w.configWriteOutcome = IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN")
      w.configWorker.tick.unsafeRunSync()
      assertEquals(w.run.run(w.configDeployments.findByRequest(w.org, ambiguous.requestId)).unsafeRunSync()
        .map(_.status), Some(IntegrationConfigDeploymentStatus.Unknown))
      assertEquals(w.configPatchCalls, 2)
      w.configWorker.tick.unsafeRunSync()
      assertEquals(w.configPatchCalls, 2, "an ambiguous PATCH is never automatically repeated")
      assertEquals(w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 2,
        UUID.randomUUID()).attempt.unsafeRunSync().left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_CONFIG_REQUIRES_REFRESH"))
    }
  }

  test("a stale config deployment becomes UNKNOWN and cannot be replayed or completed by its old claim") {
    withWorld { w =>
      val external = UUID.randomUUID().toString
      val observed = ObservedIntegrationObject(IntegrationObjectType.ConfigProfile, external, "Default",
        RemnawaveConfigProfileSummary(1, Instant.parse("2026-08-01T00:00:00Z"),
          Instant.parse("2026-09-01T00:00:00Z"), Nil, Nil, Some(CanonicalJson.sha256(w.remoteConfig))))
      val integration = w.create("Stale config")
      w.observation = IO.pure(snapshot(observed)); w.manual(integration.id)
      val objectId = w.objects(integration.id).head._5
      w.configProfiles.adopt(w.actor, integration.id, objectId,
        AdoptIntegrationConfigProfile("stale-config", "Stale", None)).unsafeRunSync()
      w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 1, UUID.randomUUID()).unsafeRunSync()
      val now = Instant.now()
      val token = UUID.randomUUID()
      val claimed = w.run.run(w.configDeployments.recoverAndClaim(UUID.randomUUID(), token,
        now, now.plusSeconds(1), 1)).unsafeRunSync()._2.head
      assertEquals(claimed.status, IntegrationConfigDeploymentStatus.Running)
      val recovered = w.run.run(w.configDeployments.recoverAndClaim(UUID.randomUUID(), UUID.randomUUID(),
        now.plusSeconds(2), now.plusSeconds(3), 1)).unsafeRunSync()
      assertEquals(recovered._1, List((w.org, integration.id)))
      assertEquals(recovered._2, Nil)
      assertEquals(w.run.run(w.configDeployments.complete(claimed, token, now.plusSeconds(3),
        "SUCCEEDED", None, None)).unsafeRunSync(), false)
      w.configWorker.tick.unsafeRunSync()
      assertEquals(w.configPatchCalls, 0)
      val history = w.run.run(w.configDeployments.recent(w.org, integration.id, objectId, 1)).unsafeRunSync()
      assertEquals(history.head.status, IntegrationConfigDeploymentStatus.Unknown)
      assertEquals(history.head.errorCode, Some("INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN"))
    }
  }

  test("guarded config rollout uses one profile PATCH, verifies a fresh snapshot and safely rolls back regression") {
    withWorld { w =>
      val external = UUID.randomUUID().toString
      val initial = w.remoteConfig
      val target = parse("""{"privateKey":"SUPER-SECRET-PRIVATE-KEY","a":24}""").toOption.get
      def profileFor(config: Json) = ObservedIntegrationObject(IntegrationObjectType.ConfigProfile, external,
        "Guarded", RemnawaveConfigProfileSummary(1, Instant.parse("2026-08-01T00:00:00Z"),
          Instant.now(), List("rollout-a", "rollout-b"), Nil, Some(CanonicalJson.sha256(config))))
      def rolloutNode(id: String, connected: Boolean) = ObservedIntegrationObject(IntegrationObjectType.Node,
        id, id, RemnawaveNodeSummary("203.0.113.1", Some(443), connected, isConnecting = false,
          isDisabled = false, None, None, None, 0L, false, None, None, 0L, "DE", None, None,
          None, Some(external), Nil, None, None))
      def observed(config: Json, firstConnected: Boolean = true, secondConnected: Boolean = true) =
        snapshot(profileFor(config), rolloutNode("rollout-a", firstConnected),
          rolloutNode("rollout-b", secondConnected))

      val integration = w.create("Guarded rollout")
      w.run.run(w.management.setEnabled(w.actor, integration.id, enabled = true)).unsafeRunSync()
      w.observation = IO.pure(observed(initial)); w.manual(integration.id)
      val objectId = w.objects(integration.id).find(_._1 == external).get._5
      w.configProfiles.adopt(w.actor, integration.id, objectId,
        AdoptIntegrationConfigProfile("guarded", "Guarded", None)).unsafeRunSync()
      Thread.sleep(10); w.manual(integration.id)
      w.configProfiles.appendRevision(w.actor, integration.id, objectId, target).unsafeRunSync()
      val preview = w.configRolloutService.preview(w.org, integration.id, objectId, 2).unsafeRunSync()
      assertEquals((preview.affectedNodes, preview.preexistingUnhealthyNodes), (2, 0))

      val cancelled = w.configRolloutService.start(w.actor, integration.id, objectId, 2,
        UUID.randomUUID(), automaticRollback = true).unsafeRunSync()
      assertEquals(w.configRolloutService.cancel(w.actor, integration.id, objectId, cancelled.id)
        .unsafeRunSync().status, IntegrationConfigRolloutStatus.Cancelled)
      assertEquals(w.configRolloutService.cancel(w.actor, integration.id, objectId, cancelled.id)
        .unsafeRunSync().status, IntegrationConfigRolloutStatus.Cancelled)
      assertEquals(w.configPatchCalls, 0)
      val requestId = UUID.randomUUID()
      val requested = w.configRolloutService.start(w.actor, integration.id, objectId, 2,
        requestId, automaticRollback = true).unsafeRunSync()
      assertEquals(requested.status, IntegrationConfigRolloutStatus.Preparing)
      assertEquals(w.configRolloutService.start(w.actor, integration.id, objectId, 2,
        requestId, automaticRollback = true).unsafeRunSync().id, requested.id)
      val disableDuringRollout = w.run.run(w.management.setEnabled(w.actor, integration.id, enabled = false))
        .attempt.unsafeRunSync()
      assertEquals(disableDuringRollout.left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_CONFIG_ROLLOUT_REQUIRES_SYNC"))
      assert(w.run.run(w.integrations.findById(w.org, integration.id)).unsafeRunSync().exists(_.enabled))
      assertEquals(w.configPatchCalls, 0, "the HTTP request creates durable intent only")
      assertEquals(w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 2, UUID.randomUUID())
        .attempt.unsafeRunSync().left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_CONFIG_ROLLOUT_ALREADY_RUNNING"))
      assertEquals(w.configRolloutService.start(w.actor, integration.id, objectId, 2, UUID.randomUUID(), true)
        .attempt.unsafeRunSync().left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_CONFIG_ROLLOUT_ALREADY_RUNNING"))
      assertEquals(w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Guarded rollout", "https://changed.example.test", None)))
        .attempt.unsafeRunSync().left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_ACTION_ALREADY_RUNNING"))
      val credentialEdit = w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Guarded rollout", integration.baseUrl.value,
          Some(RemnawaveCredential("new-token", None))))).attempt.unsafeRunSync()
      val delete = w.run.run(w.management.delete(w.actor, integration.id)).attempt.unsafeRunSync()
      List(credentialEdit, delete).foreach(result => assertEquals(
        result.left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_ACTION_ALREADY_RUNNING")))
      assertEquals(w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Guarded rollout renamed", integration.baseUrl.value, None)))
        .unsafeRunSync().name, "Guarded rollout renamed")
      val durableRow = w.run.run(sql"select row_to_json(r)::text from integration_config_rollout r where id=${requested.id}"
        .query[String].unique).unsafeRunSync()
      assert(!durableRow.contains("SUPER-SECRET-PRIVATE-KEY"))
      assert(!durableRow.contains("new-token"))
      Thread.sleep(10); w.manual(integration.id)
      val worker = w.configRolloutWorker(100.millis)
      val claimAt = Instant.now()
      val tokenA = UUID.randomUUID(); val tokenB = UUID.randomUUID()
      val claimedA = w.run.run(w.configRollouts.recoverAndClaim(UUID.randomUUID(), tokenA,
        claimAt, claimAt.plusMillis(10), 1)).unsafeRunSync().head
      val claimedB = w.run.run(w.configRollouts.recoverAndClaim(UUID.randomUUID(), tokenB,
        claimAt.plusMillis(20), claimAt.plusSeconds(30), 1)).unsafeRunSync().head
      assertEquals(w.run.run(w.configRollouts.prepare(claimedA, tokenA, UUID.randomUUID(),
        UUID.randomUUID(), claimAt.plusMillis(21))).unsafeRunSync(), false)
      assertEquals(w.run.run(w.configRollouts.prepare(claimedB, tokenB, UUID.randomUUID(),
        UUID.randomUUID(), claimAt.plusMillis(22))).unsafeRunSync(), true)
      assertEquals(w.run.run(w.configRollouts.find(w.org, requested.id)).unsafeRunSync().map(_.status),
        Some(IntegrationConfigRolloutStatus.Applying))
      assertEquals(w.run.run(sql"select count(*) from integration_config_deployment where rollout_id=${requested.id}"
        .query[Long].unique).unsafeRunSync(), 1L, "two workers create one target deployment")
      w.configWorker.tick.unsafeRunSync()
      assertEquals(w.configPatchCalls, 1)
      worker.tick.unsafeRunSync()
      assertEquals(w.run.run(w.configRollouts.find(w.org, requested.id)).unsafeRunSync().map(_.status),
        Some(IntegrationConfigRolloutStatus.Verifying))

      w.observation = IO.pure(observed(target, secondConnected = false))
      Thread.sleep(130); w.manual(integration.id); worker.tick.unsafeRunSync()
      val rollingBack = w.run.run(w.configRollouts.find(w.org, requested.id)).unsafeRunSync().get
      assertEquals(rollingBack.status, IntegrationConfigRolloutStatus.RollbackApplying)
      assert(rollingBack.rollbackDeploymentId.nonEmpty)
      w.configWorker.tick.unsafeRunSync()
      assertEquals(w.configPatchCalls, 2, "one target PATCH and one rollback PATCH")
      worker.tick.unsafeRunSync()
      w.observation = IO.pure(observed(initial)); Thread.sleep(10); w.manual(integration.id)
      worker.tick.unsafeRunSync()
      val finished = w.run.run(w.configRollouts.find(w.org, requested.id)).unsafeRunSync().get
      assertEquals(finished.status, IntegrationConfigRolloutStatus.RolledBack)
      assertEquals(w.remoteConfig, initial)
      val sources = w.run.run(sql"""select source from integration_config_deployment
        where rollout_id=${requested.id} order by created_at""".query[String].to[List]).unsafeRunSync()
      assertEquals(sources, List("ROLLOUT_TARGET", "ROLLOUT_ROLLBACK"))

      val recovery = w.configRolloutService.start(w.actor, integration.id, objectId, 2,
        UUID.randomUUID(), automaticRollback = true).unsafeRunSync()
      w.observation = IO.pure(observed(initial, secondConnected = false))
      Thread.sleep(10); w.manual(integration.id)
      val recoveryWorker = w.configRolloutWorker(2.seconds)
      recoveryWorker.tick.unsafeRunSync(); w.configWorker.tick.unsafeRunSync(); recoveryWorker.tick.unsafeRunSync()
      w.observation = IO.pure(observed(target, firstConnected = false, secondConnected = false))
      Thread.sleep(10); w.manual(integration.id); recoveryWorker.tick.unsafeRunSync()
      assertEquals(w.run.run(w.configRollouts.find(w.org, recovery.id)).unsafeRunSync().map(_.status),
        Some(IntegrationConfigRolloutStatus.Verifying), "a transient regression waits inside the window")
      assertEquals(w.configPatchCalls, 3, "a transient observation creates no rollback deployment")
      w.observation = IO.pure(observed(target, firstConnected = true, secondConnected = false))
      Thread.sleep(10); w.manual(integration.id); recoveryWorker.tick.unsafeRunSync()
      assertEquals(w.run.run(w.configRollouts.find(w.org, recovery.id)).unsafeRunSync().map(_.status),
        Some(IntegrationConfigRolloutStatus.Succeeded))
      val health = w.run.run(w.configRollouts.nodeHealth(w.org, recovery.id)).unsafeRunSync()
      assertEquals(health.count(_.result == "PREEXISTING_UNHEALTHY"), 1)
      assertEquals(health.count(_.result == "REGRESSION"), 0)
      assertEquals(w.run.run(w.management.setEnabled(w.actor, integration.id, enabled = false))
        .unsafeRunSync().enabled, false, "a terminal rollout no longer blocks disabling sync")
    }
  }

  test("endpoint or credential changes detach config bindings without deleting revision or deployment history") {
    List(false, true).foreach { replaceCredential => withWorld { w =>
      val external = UUID.randomUUID().toString
      val observed = ObservedIntegrationObject(IntegrationObjectType.ConfigProfile, external, "Default",
        RemnawaveConfigProfileSummary(1, Instant.parse("2026-08-01T00:00:00Z"),
          Instant.parse("2026-09-01T00:00:00Z"), Nil, Nil, Some(CanonicalJson.sha256(w.remoteConfig))))
      val integration = w.create("Original panel")
      w.observation = IO.pure(snapshot(observed)); w.manual(integration.id)
      val objectId = w.objects(integration.id).head._5
      val original = w.configProfiles.adopt(w.actor, integration.id, objectId,
        AdoptIntegrationConfigProfile("original-config", "Original", None)).unsafeRunSync()
      val deployment = w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 1,
        UUID.randomUUID()).unsafeRunSync()
      assertEquals(w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Original panel", "https://other.example.test", None)))
        .attempt.unsafeRunSync().left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_ACTION_ALREADY_RUNNING"))
      w.configWorker.tick.unsafeRunSync()
      val changed = UpdateIntegrationCommand("New panel",
        if (replaceCredential) "https://panel.example.test" else "https://other.example.test",
        if (replaceCredential) Some(RemnawaveCredential("replacement", None)) else None)
      w.run.run(w.management.update(w.actor, integration.id, changed)).unsafeRunSync()
      assertEquals(w.configProfiles.managed(w.org, integration.id, objectId).unsafeRunSync(), None)
      assertEquals(w.run.run(sql"select count(*) from configuration_revision_secure_payload where profile_id = ${original.id}"
        .query[Long].unique).unsafeRunSync(), 1L)
      assertEquals(w.run.run(w.configDeployments.findByRequest(w.org, deployment.requestId)).unsafeRunSync()
        .map(_.status), Some(IntegrationConfigDeploymentStatus.Succeeded))
      w.observation = IO.pure(snapshot(observed)); w.manual(integration.id)
      assertEquals(w.objects(integration.id).head._5, objectId)
      assertEquals(w.configProfiles.managed(w.org, integration.id, objectId).unsafeRunSync(), None)
      assertEquals(w.configProfiles.requestDeploy(w.actor, integration.id, objectId, 1,
        UUID.randomUUID()).attempt.unsafeRunSync().left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_CONFIG_PROFILE_NOT_FOUND"))
      val adopted = w.configProfiles.adopt(w.actor, integration.id, objectId,
        AdoptIntegrationConfigProfile("new-config", "New", None)).unsafeRunSync()
      assertNotEquals(adopted.id, original.id)
      assertEquals(w.configProfiles.managed(w.org, integration.id, objectId).unsafeRunSync()
        .map(_.profile.id), Some(adopted.id))
    }}
  }

  test("config adoption rejects foreign, wrong-type, inactive and changed remote targets") {
    withWorld { w =>
      val external = UUID.randomUUID().toString
      val observed = ObservedIntegrationObject(IntegrationObjectType.ConfigProfile, external, "Default",
        RemnawaveConfigProfileSummary(1, Instant.parse("2026-08-01T00:00:00Z"),
          Instant.parse("2026-09-01T00:00:00Z"), Nil, Nil, Some(CanonicalJson.sha256(w.remoteConfig))))
      val integration = w.create("Adoption checks")
      w.observation = IO.pure(snapshot(observed, node("node-a", "Node")))
      w.manual(integration.id)
      val objects = w.objects(integration.id)
      val profileId = objects.find(_._1 == external).get._5
      val nodeId = objects.find(_._1 == "node-a").get._5
      val command = AdoptIntegrationConfigProfile("adoption-check", "Adoption check", None)
      def code(result: Either[Throwable, domain.configuration.ConfigurationProfile]) =
        result.left.toOption.collect { case e: IntegrationError => e.code }
      assertEquals(code(w.configProfiles.adopt(ActorContext(w.user, w.foreign), integration.id,
        profileId, command).attempt.unsafeRunSync()), Some("INTEGRATION_NOT_FOUND"))
      assertEquals(code(w.configProfiles.adopt(w.actor, integration.id, nodeId, command)
        .attempt.unsafeRunSync()), Some("INTEGRATION_CONFIG_PROFILE_UNSUPPORTED"))
      w.remoteDocumentId = Some(UUID.randomUUID().toString)
      assertEquals(code(w.configProfiles.adopt(w.actor, integration.id, profileId, command)
        .attempt.unsafeRunSync()), Some("INTEGRATION_CONFIG_PROFILE_CHANGED"))
      w.remoteDocumentId = None
      w.beforeConfigFetch = w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Adoption checks", "https://changed.example.test", None))).void
      assertEquals(code(w.configProfiles.adopt(w.actor, integration.id, profileId, command)
        .attempt.unsafeRunSync()), Some("INTEGRATION_CONFIG_PROFILE_CHANGED"))
      w.beforeConfigFetch = IO.unit
      assertEquals(w.run.run(sql"select count(*) from configuration_profile where organization_id = ${w.org}"
        .query[Long].unique).unsafeRunSync(), 0L)
      w.observation = IO.pure(snapshot(observed)); w.manual(integration.id)
      w.observation = IO.pure(snapshot()); w.manual(integration.id)
      assertEquals(code(w.configProfiles.adopt(w.actor, integration.id, profileId, command)
        .attempt.unsafeRunSync()), Some("INTEGRATION_CONFIG_PROFILE_INACTIVE"))
    }
  }

  test("concurrent managed config revisions receive consecutive immutable numbers") {
    withWorld { w =>
      val external = UUID.randomUUID().toString
      val observed = ObservedIntegrationObject(IntegrationObjectType.ConfigProfile, external, "Default",
        RemnawaveConfigProfileSummary(1, Instant.parse("2026-08-01T00:00:00Z"),
          Instant.parse("2026-09-01T00:00:00Z"), Nil, Nil, Some(CanonicalJson.sha256(w.remoteConfig))))
      val integration = w.create("Revision race")
      w.observation = IO.pure(snapshot(observed)); w.manual(integration.id)
      val objectId = w.objects(integration.id).head._5
      w.configProfiles.adopt(w.actor, integration.id, objectId,
        AdoptIntegrationConfigProfile("revision-race", "Revision race", None)).unsafeRunSync()
      val inputs = List(2, 3).map(number => Json.obj("a" -> Json.fromInt(number)))
      val created = inputs.parTraverse(config => w.configProfiles.appendRevision(w.actor,
        integration.id, objectId, config)).unsafeRunSync()
      assertEquals(created.sorted, List(2, 3))
      assertEquals(w.configProfiles.revisionContent(w.org, integration.id, objectId, 1).unsafeRunSync()
        .contentSha256, CanonicalJson.sha256(w.remoteConfig))
      assertEquals(w.configProfiles.revisions(w.org, integration.id, objectId, None, 10).unsafeRunSync()
        .map(_.revisionNumber).sorted, List(1, 2, 3))
      val count = w.run.run(sql"select count(*) from configuration_revision_secure_payload where organization_id = ${w.org}"
        .query[Long].unique).unsafeRunSync()
      assertEquals(count, 3L)
    }
  }

  test("snapshots are reconciled: insert, keep unchanged rows, update, deactivate, reactivate with the same id") {
    withWorld { w =>
      val integration = w.create("Reconcile")
      w.observation = IO.pure(snapshot(node("node-a", "Frankfurt"), node("node-b", "Amsterdam"), host, profile))
      val first = w.manual(integration.id)
      assertEquals(first.status, IntegrationSyncStatus.Completed)
      assertEquals(first.counts, Some(IntegrationSyncCounts(2, 1, 1, 0)))
      val initial = w.objects(integration.id)
      assertEquals(initial.map(_._1), List("host-1", "node-a", "node-b", "profile-1"))
      Thread.sleep(20)
      w.observation = IO.pure(snapshot(node("node-a", "Frankfurt"), node("node-b", "Amsterdam 2"), host, profile))
      val second = w.manual(integration.id)
      val after = w.objects(integration.id)
      val a0 = initial.find(_._1 == "node-a").get; val a1 = after.find(_._1 == "node-a").get
      assertEquals(a1._4, a0._4, "an unchanged object keeps its update time")
      assertEquals(a1._6, second.id)
      assert(after.find(_._1 == "node-b").get._4.isAfter(initial.find(_._1 == "node-b").get._4))
      // A binding survives the node disappearing from the provider.
      w.run.run(w.bindings.bind(w.actor, integration.id, a0._5, w.nodeResource)).unsafeRunSync()
      w.observation = IO.pure(snapshot(node("node-b", "Amsterdam 2"), host, profile))
      val third = w.manual(integration.id)
      assertEquals(third.counts.map(_.deactivated), Some(1))
      assertEquals(w.objects(integration.id).find(_._1 == "node-a").map(_._3), Some(false))
      assertEquals(w.run.run(w.bindingRepository.find(w.org, a0._5)).unsafeRunSync().map(_.resourceId), Some(w.nodeResource))
      w.observation = IO.pure(snapshot(node("node-a", "Frankfurt"), node("node-b", "Amsterdam 2"), host, profile))
      w.manual(integration.id)
      val back = w.objects(integration.id).find(_._1 == "node-a").get
      assertEquals((back._3, back._5), (true, a0._5))
      // An empty but complete snapshot deactivates everything and deletes nothing.
      w.observation = IO.pure(snapshot())
      assertEquals(w.manual(integration.id).counts.map(_.deactivated), Some(4))
      assertEquals(w.objects(integration.id).size, 4)
      assert(w.objects(integration.id).forall(!_._3))
    }
  }

  test("a failed observation leaves the stored inventory exactly as it was") {
    withWorld { w =>
      val integration = w.create("Failure")
      w.observation = IO.pure(snapshot(node("node-a", "Frankfurt")))
      w.manual(integration.id)
      val before = w.objects(integration.id)
      w.observation = IO.raiseError(IntegrationError("INTEGRATION_REMOTE_UNAVAILABLE", "x"))
      val failed = w.manual(integration.id)
      assertEquals(failed.status, IntegrationSyncStatus.Failed)
      assertEquals(failed.errorCode, Some("INTEGRATION_REMOTE_UNAVAILABLE"))
      assertEquals(w.objects(integration.id), before)
      val stored = w.run.run(w.sessions.recent(w.org, integration.id, 20)).unsafeRunSync()
      assertEquals(stored.map(_.status), List(IntegrationSyncStatus.Failed, IntegrationSyncStatus.Completed))
      assertEquals(w.actions.count(_ == "INTEGRATION_SYNC_REQUESTED"), 2)
    }
  }

  test("a session retired while its observation was running rolls its snapshot back") {
    withWorld { w =>
      val integration = w.create("Stale")
      w.observation = w.run.run(sql"""update integration_sync_session set status = 'FAILED', finished_at = now(),
          error_code = 'INTEGRATION_SYNC_STALE' where integration_id = ${integration.id} and status = 'RUNNING'"""
        .update.run).as(snapshot(node("node-a", "Frankfurt")))
      val result = w.manual(integration.id)
      assertEquals(result.errorCode, Some(IntegrationSync.StaleCode))
      assertEquals(w.objects(integration.id), Nil)
    }
  }

  test("a provider error after the session was retired never overrides the durable stale outcome") {
    withWorld { w =>
      val integration = w.create("Late failure")
      w.observation = w.run.run(sql"""update integration_sync_session set status = 'FAILED', finished_at = now(),
          error_code = 'INTEGRATION_SYNC_STALE', error_message = 'Synchronization was abandoned and recovered'
          where integration_id = ${integration.id} and status = 'RUNNING'""".update.run) *>
        IO.raiseError(IntegrationError("INTEGRATION_TIMEOUT", "late"))
      val returned = w.manual(integration.id)
      val stored = w.run.run(w.sessions.recent(w.org, integration.id, 5)).unsafeRunSync()
      assertEquals(stored.map(_.errorCode), List(Some(IntegrationSync.StaleCode)))
      assertEquals(returned, stored.head)
      assertEquals(w.objects(integration.id), Nil)
    }
  }

  test("the database refuses a binding or a last-seen session from another integration") {
    withWorld { w =>
      val first = w.create("First"); val second = w.create("Second")
      w.observation = IO.pure(snapshot(node("node-a", "Frankfurt")))
      w.manual(first.id)
      val secondSession = w.manual(second.id).id
      val objectId = w.objects(first.id).head._5
      def refused(statement: ConnectionIO[Int]) = w.run.run(statement).attempt.unsafeRunSync().left.toOption
        .collect { case e: java.sql.SQLException => e.getSQLState }
      assertEquals(refused(sql"""insert into integration_resource_binding (id, organization_id, integration_id,
          inventory_object_id, resource_id, created_by_user_id, created_at, updated_at)
          values (${UUID.randomUUID()}, ${w.org}, ${second.id}, $objectId, ${w.nodeResource}, ${w.user}, now(), now())"""
        .update.run), Some("23503"))
      assertEquals(refused(sql"""update integration_inventory_object set last_seen_sync_session_id = $secondSession
          where id = $objectId""".update.run), Some("23503"))
    }
  }

  test("one RUNNING session per integration; an expired one is recovered by the next attempt") {
    withWorld { w =>
      val integration = w.create("Running")
      val running = UUID.randomUUID()
      w.run.run(sql"""insert into integration_sync_session (id, organization_id, integration_id, trigger, started_at,
          recover_after_at, status) values ($running, ${w.org}, ${integration.id}, 'SCHEDULED', now(),
          now() + interval '10 minutes', 'RUNNING')""".update.run).unsafeRunSync()
      val refused = w.sync.manual(w.actor, integration.id).attempt.unsafeRunSync()
      assertEquals(refused.left.toOption.collect { case e: IntegrationError => e.code }, Some(IntegrationSync.AlreadyRunningCode))
      assertEquals(w.actions.count(_ == "INTEGRATION_SYNC_REQUESTED"), 0)
      w.run.run(sql"update integration_sync_session set recover_after_at = now() - interval '1 second' where id = $running"
        .update.run).unsafeRunSync()
      assertEquals(w.manual(integration.id).status, IntegrationSyncStatus.Completed)
      val old = w.run.run(sql"select status, error_code from integration_sync_session where id = $running"
        .query[(String, Option[String])].unique).unsafeRunSync()
      assertEquals(old, ("FAILED", Some(IntegrationSync.StaleCode)))
      val duplicate = w.run.run(sql"""insert into integration_sync_session (id, organization_id, integration_id, trigger,
          started_at, recover_after_at, status) values (${UUID.randomUUID()}, ${w.org}, ${integration.id}, 'SCHEDULED',
          now(), now(), 'RUNNING'), (${UUID.randomUUID()}, ${w.org}, ${integration.id}, 'SCHEDULED', now(), now(),
          'RUNNING')""".update.run).attempt.unsafeRunSync()
      assert(duplicate.isLeft)
    }
  }

  test("claims: only enabled and due, disjoint under concurrency, leased and fenced by token") {
    withWorld { w =>
      val enabled = List("A", "B", "C").map(w.create).map(value =>
        w.run.run(w.management.setEnabled(w.actor, value.id, enabled = true)).unsafeRunSync())
      val disabled = w.create("Disabled")
      val now = w.run.run(sql"select now()".query[Instant].unique).unsafeRunSync().plusSeconds(1)
      val claimed = List.fill(3)(w.run.run(w.states.claimDue(UUID.randomUUID(), 1, 90, now)))
        .parSequence.unsafeRunSync().flatten.filter(claim => claim.organizationId == w.org)
      assertEquals(claimed.map(_.integrationId).toSet, enabled.map(_.id).toSet)
      assert(!claimed.exists(_.integrationId == disabled.id))
      // Leased rows are not claimed again until the lease ends.
      assertEquals(w.run.run(w.states.claimDue(UUID.randomUUID(), 10, 90, now)).unsafeRunSync()
        .filter(_.organizationId == w.org), Nil)
      val claim = claimed.head
      assert(!w.run.run(w.states.completeClaimedRun(claim.copy(token = UUID.randomUUID()), now.plusSeconds(60), 0, now))
        .unsafeRunSync())
      assert(w.run.run(w.states.completeClaimedRun(claim, now.plusSeconds(60), 2, now)).unsafeRunSync())
      assert(!w.run.run(w.states.completeClaimedRun(claim, now.plusSeconds(60), 2, now)).unsafeRunSync())
      val reclaimed = w.run.run(w.states.claimDue(UUID.randomUUID(), 10, 90, now.plusSeconds(200))).unsafeRunSync()
        .filter(_.organizationId == w.org)
      assertEquals(reclaimed.map(_.integrationId).toSet, enabled.map(_.id).toSet)
      assertEquals(reclaimed.find(_.integrationId == claim.integrationId).map(_.consecutiveFailures), Some(2L))
    }
  }

  test("bindings: NODE to active NODE of the same tenant only; no-op, replace, unbind, cascade on delete") {
    withWorld { w =>
      val integration = w.create("Bindings")
      w.observation = IO.pure(snapshot(node("node-a", "Frankfurt"), host))
      w.manual(integration.id)
      val objects = w.objects(integration.id)
      val nodeObject = objects.find(_._1 == "node-a").get._5
      val hostObject = objects.find(_._1 == "host-1").get._5
      def bind(objectId: UUID, resource: UUID) =
        w.run.run(w.bindings.bind(w.actor, integration.id, objectId, resource)).attempt.unsafeRunSync()
      def code(result: Either[Throwable, _]) = result.left.toOption.collect { case e: IntegrationError => e.code }
      List(w.container, w.archived, w.foreignNode, UUID.randomUUID()).foreach(resource =>
        assertEquals(code(bind(nodeObject, resource)), Some("INTEGRATION_BINDING_INVALID_RESOURCE"), resource.toString))
      assertEquals(code(bind(hostObject, w.nodeResource)), Some("INTEGRATION_OBJECT_NOT_FOUND"))
      bind(nodeObject, w.nodeResource).toOption.get
      val first = w.run.run(w.bindingRepository.find(w.org, nodeObject)).unsafeRunSync().get
      Thread.sleep(10)
      assertEquals(bind(nodeObject, w.nodeResource).toOption.get, first)
      assertEquals(w.run.run(w.bindingRepository.find(w.org, nodeObject)).unsafeRunSync(), Some(first))
      assertEquals(w.actions.count(_ == "INTEGRATION_RESOURCE_BOUND"), 1)
      val replaced = bind(nodeObject, w.otherNode).toOption.get
      assertEquals((replaced.id, replaced.resourceId), (first.id, w.otherNode))
      assertEquals(w.actions.count(_ == "INTEGRATION_RESOURCE_BOUND"), 2)
      val contexts = w.run.run(w.query.resourceContexts(w.org, w.otherNode)).unsafeRunSync()
      assertEquals(contexts.map(c => (c.integrationName, c.obj.externalId)), List(("Bindings", "node-a")))
      assertEquals(w.run.run(w.query.resourceContexts(w.foreign, w.otherNode)).unsafeRunSync(), Nil)
      val listed = w.page(integration.id, InventoryFilter(None, None, None, 100, 0)).items.head.binding.get
      assertEquals((listed.code, listed.environmentName, listed.projectName), ("vps-amsterdam", "Production", "Edge"))
      w.run.run(w.bindings.unbind(w.actor, integration.id, nodeObject)).unsafeRunSync()
      w.run.run(w.bindings.unbind(w.actor, integration.id, nodeObject)).unsafeRunSync()
      assertEquals(w.actions.count(_ == "INTEGRATION_RESOURCE_UNBOUND"), 1)
      bind(nodeObject, w.nodeResource)
      w.run.run(w.management.delete(w.actor, integration.id)).unsafeRunSync()
      val retained = w.run.run(sql"""select
          (select count(*) from integration_inventory_object where integration_id = ${integration.id}),
          (select count(*) from integration_resource_binding where integration_id = ${integration.id}),
          (select count(*) from integration_sync_session where integration_id = ${integration.id}),
          (select count(*) from integration_sync_state where integration_id = ${integration.id}),
          (select secret_id is null and deleted_at is not null from integration where id=${integration.id})"""
        .query[(Long, Long, Long, Long, Boolean)].unique)
        .unsafeRunSync()
      assert(retained._1 > 0, "inventory history remains attached to the tombstone")
      assert(retained._2 > 0, "bindings remain attached to the tombstone")
      assert(retained._3 > 0, "sync history remains attached to the tombstone")
      assert(retained._4 > 0, "sync schedule remains attached to the tombstone")
      assert(retained._5, "the tombstone has no credential reference")
      assertEquals(w.run.run(w.query.resourceContexts(w.org, w.nodeResource)).unsafeRunSync(), Nil,
        "tombstoned integrations are omitted from active resource contexts")
    }
  }

  test("inventory reads: filters, search, state, paging, overview and binding candidates") {
    withWorld { w =>
      val integration = w.create("Reads")
      val other = w.create("Other")
      w.observation = IO.pure(snapshot(node("node-a", "Frankfurt", address = "198.51.100.7"),
        node("node-b", "Amsterdam", disabled = true), node("node-c", "Berlin_50%"), host, profile))
      w.manual(integration.id)
      assertEquals(w.page(integration.id, InventoryFilter(None, None, None, 2, 0)).items.map(_.obj.displayName),
        List("Amsterdam", "Berlin_50%"))
      val second = w.page(integration.id, InventoryFilter(None, None, None, 2, 2))
      assertEquals((second.items.map(_.obj.displayName), second.total), (List("Frankfurt"), 3L))
      assertEquals(w.page(integration.id, InventoryFilter(None, Some("FRANK"), None, 10, 0)).total, 1L)
      assertEquals(w.page(integration.id, InventoryFilter(None, Some("198.51.100"), None, 10, 0)).total, 1L)
      assertEquals(w.page(integration.id, InventoryFilter(None, Some("_50%"), None, 10, 0)).total, 1L)
      assertEquals(w.page(integration.id, InventoryFilter(None, Some("%"), None, 10, 0)).total, 1L)
      assertEquals(w.page(integration.id, InventoryFilter(None, None, Some(RemnawaveNodeState.Disabled), 10, 0))
        .items.map(_.obj.externalId), List("node-b"))
      assertEquals(w.page(integration.id, InventoryFilter(Some(false), None, None, 10, 0)).total, 0L)
      assertEquals(w.page(other.id, InventoryFilter(None, None, None, 10, 0)).total, 0L)
      assertEquals(w.run.run(w.query.list(w.foreign, integration.id, IntegrationObjectType.Node,
        InventoryFilter(None, None, None, 10, 0))).unsafeRunSync().total, 0L)
      val overviews = w.run.run(w.query.overviews(w.org)).unsafeRunSync()
      assertEquals(overviews.keySet, Set(integration.id, other.id))
      val overview = overviews(integration.id)
      assertEquals(overview.inventory.nodes.active, 3L)
      assertEquals(overview.inventory.hosts.active, 1L)
      assertEquals(overview.inventory.configProfiles.active, 1L)
      assertEquals(overview.lastSync.map(_.status), Some(IntegrationSyncStatus.Completed))
      assertEquals(overview.lastSuccessfulSyncAt, overview.lastSync.flatMap(_.finishedAt))
      assert(overview.nextRunAt.nonEmpty)
      assertEquals(overviews(other.id).lastSync, None)
      val candidates = w.run.run(w.query.bindingCandidates(w.org, None, 50)).unsafeRunSync()
      assertEquals(candidates.map(_.code), List("vps-amsterdam", "vps-frankfurt"))
      assertEquals(candidates.map(_.projectName).distinct, List("Edge"))
      assertEquals(w.run.run(w.query.bindingCandidates(w.org, Some("frank"), 50)).unsafeRunSync().map(_.code),
        List("vps-frankfurt"))
    }
  }

  test("stored summaries hold only the typed projection") {
    withWorld { w =>
      val integration = w.create("Summary")
      w.observation = IO.pure(snapshot(node("node-a", "Frankfurt"), profile))
      w.manual(integration.id)
      val keys = w.run.run(sql"""select distinct jsonb_object_keys(summary) from integration_inventory_object
          where integration_id = ${integration.id}""".query[String].to[List]).unsafeRunSync().toSet
      List("proxyUrl", "config", "rawInbound", "xhttpExtraParams", "muxParams", "sockoptParams", "finalMask")
        .foreach(key => assert(!keys.contains(key), key))
      assertEquals(w.run.run(sql"""select distinct summary_version from integration_inventory_object
          where integration_id = ${integration.id}""".query[Int].to[List]).unsafeRunSync(), List(1))
    }
  }

  test("node actions are idempotent, exclusive, fenced and require observation after UNKNOWN") {
    withWorld { w =>
      val integration = w.create("Actions") // manual actions also work while observation is disabled
      w.observation = IO.pure(snapshot(node(UUID.randomUUID().toString, "Frankfurt"),
        node(UUID.randomUUID().toString, "Amsterdam", disabled = true), host))
      w.manual(integration.id)
      val objects = w.run.run(w.inventory.findObject(w.org, integration.id,
        w.objects(integration.id).find(_._2 == "Frankfurt").get._5, false)).unsafeRunSync().get
      val other = w.objects(integration.id).find(_._2 == "Amsterdam").get._5
      val hostId = w.objects(integration.id).find(_._1 == "host-1").get._5
      val requestId = UUID.randomUUID()
      def request(id: UUID, key: UUID, action: IntegrationActionCode) =
        w.run.run(w.actionService.request(w.actor, integration.id, id, key, action)).attempt.unsafeRunSync()
      def code(result: Either[Throwable, _]) = result.left.toOption.collect { case e: IntegrationError => e.code }
      val first = request(objects.id, requestId, IntegrationActionCode.NodeRestart).toOption.get
      assertEquals(request(objects.id, requestId, IntegrationActionCode.NodeRestart).toOption.get.id, first.id)
      assertEquals(code(request(objects.id, requestId, IntegrationActionCode.NodeDisable)),
        Some("INTEGRATION_ACTION_REQUEST_ID_CONFLICT"))
      assertEquals(code(request(objects.id, UUID.randomUUID(), IntegrationActionCode.NodeDisable)),
        Some("INTEGRATION_ACTION_ALREADY_RUNNING"))
      assert(request(other, UUID.randomUUID(), IntegrationActionCode.NodeEnable).isRight)
      assertEquals(code(request(hostId, UUID.randomUUID(), IntegrationActionCode.NodeRestart)),
        Some("INTEGRATION_ACTION_UNSUPPORTED"))
      assertEquals(code(request(UUID.randomUUID(), UUID.randomUUID(), IntegrationActionCode.NodeRestart)),
        Some("INTEGRATION_OBJECT_NOT_FOUND"))
      assertEquals(w.actions.count(_ == "INTEGRATION_ACTION_REQUESTED"), 2)

      val at = Instant.now()
      val claimed = w.run.run(w.actionRepository.recoverAndClaim(UUID.randomUUID(), UUID.randomUUID(), at,
        at.plusSeconds(80), 1)).unsafeRunSync()._2
      assertEquals(claimed.map(_.id), List(first.id))
      assert(!w.run.run(w.actionRepository.complete(first, UUID.randomUUID(), at.plusSeconds(1),
        "SUCCEEDED", None, None)).unsafeRunSync())
      assert(w.run.run(w.actionRepository.complete(claimed.head, claimed.head.claimToken.get, at,
        "UNKNOWN", Some("INTEGRATION_ACTION_RESULT_UNKNOWN"), Some("Remote result is unknown"))).unsafeRunSync())
      assertEquals(code(request(objects.id, UUID.randomUUID(), IntegrationActionCode.NodeRestart)),
        Some("INTEGRATION_ACTION_REQUIRES_REFRESH"))
      Thread.sleep(20)
      w.manual(integration.id)
      assert(request(objects.id, UUID.randomUUID(), IntegrationActionCode.NodeRestart).isRight)
      assertEquals(w.run.run(w.actionRepository.find(w.org, integration.id, first.id)).unsafeRunSync().get.status,
        IntegrationActionStatus.Unknown)
    }
  }

  test("a lost worker claim becomes UNKNOWN without replay and nudges enabled observation") {
    withWorld { w =>
      val integration = w.create("Lost action")
      val externalId = UUID.randomUUID().toString
      w.observation = IO.pure(snapshot(node(externalId, "Frankfurt")))
      w.manual(integration.id)
      w.run.run(w.management.setEnabled(w.actor, integration.id, enabled = true)).unsafeRunSync()
      val objectId = w.objects(integration.id).head._5
      val created = w.run.run(w.actionService.request(w.actor, integration.id, objectId,
        UUID.randomUUID(), IntegrationActionCode.NodeRestart)).unsafeRunSync()
      val at = Instant.now()
      w.run.run(sql"update integration_sync_state set next_run_at = ${at.plusSeconds(3600)} where integration_id = ${integration.id}"
        .update.run).unsafeRunSync()
      val first = w.run.run(w.actionRepository.recoverAndClaim(UUID.randomUUID(), UUID.randomUUID(), at,
        at.plusSeconds(70), 1)).unsafeRunSync()._2
      assertEquals(first.map(_.id), List(created.id))
      w.provider.executeAction(IntegrationRuntimeContext(integration.id, w.org, integration.baseUrl,
        RemnawaveCredential("tok", None)), externalId, IntegrationActionCode.NodeRestart).unsafeRunSync()
      assertEquals(w.remoteActionCalls, 1)
      val later = at.plusSeconds(71)
      val second = w.run.run(w.actionRepository.recoverAndClaim(UUID.randomUUID(), UUID.randomUUID(), later,
        later.plusSeconds(70), 1)).unsafeRunSync()
      assertEquals(second._1, 1)
      assertEquals(second._2, Nil)
      assert(!w.run.run(w.actionRepository.complete(first.head, first.head.claimToken.get, later,
        "SUCCEEDED", None, None)).unsafeRunSync())
      assertEquals(w.run.run(w.actionRepository.find(w.org, integration.id, created.id)).unsafeRunSync().get.status,
        IntegrationActionStatus.Unknown)
      val nextRun = w.run.run(sql"select next_run_at from integration_sync_state where integration_id = ${integration.id}"
        .query[Instant].unique).unsafeRunSync()
      assertEquals(nextRun.toEpochMilli, later.toEpochMilli)
      assertEquals(w.remoteActionCalls, 1)
    }
  }

  test("action worker processes bounded batches and leaves observed inventory untouched") {
    withWorld { w =>
      val integration = w.create("Worker")
      w.observation = IO.pure(snapshot(node(UUID.randomUUID().toString, "Frankfurt"),
        node(UUID.randomUUID().toString, "Berlin")))
      w.manual(integration.id)
      w.run.run(w.management.setEnabled(w.actor, integration.id, enabled = true)).unsafeRunSync()
      val scheduleClaim = ClaimedIntegrationSync(w.org, integration.id, 0L, UUID.randomUUID())
      w.run.run(sql"""update integration_sync_state set claim_token = ${scheduleClaim.token},
        claimed_by = ${UUID.randomUUID()}, claim_until = ${Instant.now().plusSeconds(90)}
        where organization_id = ${w.org} and integration_id = ${integration.id}""".update.run).unsafeRunSync()
      val objects = w.objects(integration.id).map(_._5)
      objects.foreach(id => w.run.run(w.actionService.request(w.actor, integration.id, id,
        UUID.randomUUID(), IntegrationActionCode.NodeDisable)).unsafeRunSync())
      val worker = new IntegrationActionWorker[ConnectionIO](w.actionRepository, w.integrations, w.secrets,
        cipher, new IntegrationProviderRegistry[IO](List(w.provider)), w.run, new SystemTimeProvider,
        NoOpLogger[IO], 1.second, batch = 1, concurrency = 1, requestTimeout = 2.seconds,
        owner = UUID.randomUUID())
      worker.tick.unsafeRunSync()
      assertEquals(w.remoteActionCalls, 1)
      assert(w.run.run(w.states.completeClaimedRun(scheduleClaim, Instant.now().plusSeconds(60), 0L,
        Instant.now())).unsafeRunSync())
      val nextRun = w.run.run(sql"select next_run_at from integration_sync_state where integration_id = ${integration.id}"
        .query[Instant].unique).unsafeRunSync()
      assert(nextRun.isBefore(Instant.now().plusSeconds(2)))
      assertEquals(w.run.run(w.actionRepository.recent(w.org, integration.id, 10)).unsafeRunSync()
        .map(_.status).toSet, Set[IntegrationActionStatus](IntegrationActionStatus.Queued,
          IntegrationActionStatus.Succeeded))
      worker.tick.unsafeRunSync()
      assertEquals(w.remoteActionCalls, 2)
      assertEquals(w.run.run(w.actionRepository.recent(w.org, integration.id, 10)).unsafeRunSync()
        .map(_.status).toSet, Set[IntegrationActionStatus](IntegrationActionStatus.Succeeded))
      assert(w.page(integration.id, InventoryFilter(None, None, None, 10, 0)).items
        .forall(item => !item.obj.summary.asInstanceOf[RemnawaveNodeSummary].isDisabled))
    }
  }

  test("concurrent action requests on one node admit one intent and one audit") {
    withWorld { w =>
      val integration = w.create("Concurrent actions")
      w.observation = IO.pure(snapshot(node(UUID.randomUUID().toString, "Frankfurt")))
      w.manual(integration.id)
      val objectId = w.objects(integration.id).head._5
      def request(key: UUID) = w.run.run(w.actionService.request(w.actor, integration.id, objectId,
        key, IntegrationActionCode.NodeRestart)).attempt
      val (a, b) = (request(UUID.randomUUID()), request(UUID.randomUUID())).parTupled.unsafeRunSync()
      assertEquals(List(a, b).count(_.isRight), 1)
      assertEquals(List(a, b).flatMap(_.left.toOption.collect { case e: IntegrationError => e.code }),
        List("INTEGRATION_ACTION_ALREADY_RUNNING"))
      assertEquals(w.actions.count(_ == "INTEGRATION_ACTION_REQUESTED"), 1)
    }
  }

  test("an active action freezes destination and credential; an endpoint edit invalidates observed nodes") {
    withWorld { w =>
      val integration = w.create("Endpoint lifecycle")
      val externalId = UUID.randomUUID().toString
      w.observation = IO.pure(snapshot(node(externalId, "Frankfurt")))
      w.manual(integration.id)
      val objectId = w.objects(integration.id).head._5
      w.run.run(w.bindings.bind(w.actor, integration.id, objectId, w.nodeResource)).unsafeRunSync()
      val execution = w.run.run(w.actionService.request(w.actor, integration.id, objectId,
        UUID.randomUUID(), IntegrationActionCode.NodeRestart)).unsafeRunSync()
      val renamed = w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Renamed", integration.baseUrl.value, None))).unsafeRunSync()
      assertEquals(renamed.name, "Renamed")
      def rejected(command: UpdateIntegrationCommand): String = w.run.run(
        w.management.update(w.actor, integration.id, command)).attempt.unsafeRunSync()
        .left.toOption.collect { case e: IntegrationError => e.code }.get
      assertEquals(rejected(UpdateIntegrationCommand("Other", "https://panel-b.example.test", None)),
        "INTEGRATION_ACTION_ALREADY_RUNNING")
      assertEquals(rejected(UpdateIntegrationCommand("Other", integration.baseUrl.value,
        Some(RemnawaveCredential("token-b", None)))), "INTEGRATION_ACTION_ALREADY_RUNNING")
      val deleted = w.run.run(w.management.delete(w.actor, integration.id)).attempt.unsafeRunSync()
      assertEquals(deleted.left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_ACTION_ALREADY_RUNNING"))
      assertEquals(w.run.run(w.integrations.findById(w.org, integration.id)).unsafeRunSync().get.baseUrl,
        integration.baseUrl)
      assert(w.run.run(w.actionRepository.find(w.org, integration.id, execution.id)).unsafeRunSync().nonEmpty)

      val worker = new IntegrationActionWorker[ConnectionIO](w.actionRepository, w.integrations, w.secrets,
        cipher, new IntegrationProviderRegistry[IO](List(w.provider)), w.run, new SystemTimeProvider,
        NoOpLogger[IO], 1.second, batch = 1, concurrency = 1, requestTimeout = 2.seconds,
        owner = UUID.randomUUID())
      worker.tick.unsafeRunSync()
      assertEquals(w.remoteActionCalls, 1)
      assertEquals(w.run.run(w.actionRepository.find(w.org, integration.id, execution.id))
        .unsafeRunSync().get.status, IntegrationActionStatus.Succeeded)

      w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("Panel B", "https://panel-b.example.test",
          Some(RemnawaveCredential("token-b", None))))).unsafeRunSync()
      assertEquals(w.objects(integration.id).head._3, false)
      assert(w.run.run(w.bindingRepository.find(w.org, objectId)).unsafeRunSync().nonEmpty)
      val blocked = w.run.run(w.actionService.request(w.actor, integration.id, objectId,
        UUID.randomUUID(), IntegrationActionCode.NodeRestart)).attempt.unsafeRunSync()
      assertEquals(blocked.left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_OBJECT_INACTIVE"))
      w.manual(integration.id)
      assert(w.objects(integration.id).head._3)
      assert(w.run.run(w.actionService.request(w.actor, integration.id, objectId,
        UUID.randomUUID(), IntegrationActionCode.NodeRestart)).attempt.unsafeRunSync().isRight)
    }
  }

  test("an observation started before endpoint edit cannot reactivate old inventory") {
    withWorld { w =>
      val integration = w.create("Sync endpoint race")
      w.observation = IO.pure(snapshot(node(UUID.randomUUID().toString, "Old node")))
      w.manual(integration.id)
      val objectId = w.objects(integration.id).head._5
      val gate = cats.effect.Deferred.unsafe[IO, Unit]
      w.observation = gate.get.as(snapshot(node(UUID.randomUUID().toString, "Late old node")))
      val pending = w.sync.manual(w.actor, integration.id).start.unsafeRunSync()
      val deadline = System.nanoTime() + 5.seconds.toNanos
      while (w.run.run(sql"select count(*) from integration_sync_session where integration_id = ${integration.id} and status = 'RUNNING'"
        .query[Int].unique).unsafeRunSync() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
      w.run.run(w.management.update(w.actor, integration.id,
        UpdateIntegrationCommand("New endpoint", "https://panel-b.example.test", None))).unsafeRunSync()
      gate.complete(()).unsafeRunSync()
      val completed = pending.joinWithNever.unsafeRunSync()
      assertEquals(completed.status, IntegrationSyncStatus.Failed)
      assertEquals(completed.errorCode, Some(IntegrationSync.ConfigurationChangedCode))
      assertEquals(w.objects(integration.id).map(_._3), List(false))
      assertEquals(w.objects(integration.id).head._5, objectId)
      w.observation = IO.pure(snapshot(node(UUID.randomUUID().toString, "New node")))
      assertEquals(w.manual(integration.id).status, IntegrationSyncStatus.Completed)
      assertEquals(w.objects(integration.id).count(_._3), 1)
    }
  }

  test("delete cannot remove a running action while its remote write is in flight") {
    withWorld { w =>
      val integration = w.create("Running delete")
      w.observation = IO.pure(snapshot(node(UUID.randomUUID().toString, "Frankfurt")))
      w.manual(integration.id)
      val objectId = w.objects(integration.id).head._5
      val execution = w.run.run(w.actionService.request(w.actor, integration.id, objectId,
        UUID.randomUUID(), IntegrationActionCode.NodeRestart)).unsafeRunSync()
      val gate = cats.effect.Deferred.unsafe[IO, Unit]
      w.remoteOutcome = gate.get.as(IntegrationActionRemoteOutcome.Succeeded)
      val worker = new IntegrationActionWorker[ConnectionIO](w.actionRepository, w.integrations, w.secrets,
        cipher, new IntegrationProviderRegistry[IO](List(w.provider)), w.run, new SystemTimeProvider,
        NoOpLogger[IO], 1.second, batch = 1, concurrency = 1, requestTimeout = 2.seconds,
        owner = UUID.randomUUID())
      val pending = worker.tick.start.unsafeRunSync()
      val deadline = System.nanoTime() + 5.seconds.toNanos
      while (w.remoteActionCalls == 0 && System.nanoTime() < deadline) Thread.sleep(5)
      assertEquals(w.remoteActionCalls, 1)
      val deleted = w.run.run(w.management.delete(w.actor, integration.id)).attempt.unsafeRunSync()
      assertEquals(deleted.left.toOption.collect { case e: IntegrationError => e.code },
        Some("INTEGRATION_ACTION_ALREADY_RUNNING"))
      gate.complete(()).unsafeRunSync()
      pending.joinWithNever.unsafeRunSync()
      assertEquals(w.run.run(w.actionRepository.find(w.org, integration.id, execution.id))
        .unsafeRunSync().get.status, IntegrationActionStatus.Succeeded)
    }
  }
}
