package ru.bitec.app.ops
package infrastructure.http

import application.integration.{IntegrationBindings, IntegrationError, IntegrationManagement, IntegrationProvider,
  IntegrationProviderRegistry, IntegrationRuntimeContext, IntegrationSync, IntegrationSyncTransactions,
  IntegrationTestResult, TestIntegration}
import application.port.{BindableResource, IntegrationActionRepository, IntegrationConfigProfileRepository,
  IntegrationRepository, IntegrationSecureRevision, IntegrationSecret,
  IntegrationSecretRepository, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.integration._
import infrastructure.runtime.{SystemIdGenerator, SystemTimeProvider}
import integration.secret.IntegrationCredentialCipher
import integration.ssh.SecretEncryptionConfig
import io.circe.Json
import munit.FunSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import org.typelevel.ci.CIString
import org.typelevel.log4cats.noop.NoOpLogger
import support.{AuthorizationFixtures, InMemoryIntegrationInventory, RecordingAuditEventRepository, TestAuditRecorder}
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration._

final class IntegrationRoutesSpec extends FunSuite {
  private val org = UUID.randomUUID()
  private val root = s"/api/v1/organizations/$org/integrations"
  private val token = "stage24a-route-private-token"
  private val key = Base64.getEncoder.encodeToString(Array.fill[Byte](32)(4))

  private class MemoryIntegrations extends IntegrationRepository[IO] {
    private var values = Map.empty[UUID, Integration]
    var listReads = 0
    override def listByOrganization(organizationId: UUID): IO[List[Integration]] = IO {
      listReads += 1; values.values.filter(_.organizationId == organizationId).toList
    }
    override def findById(organizationId: UUID, id: UUID): IO[Option[Integration]] =
      IO.pure(values.get(id).filter(_.organizationId == organizationId))
    override def findByIdForUpdate(organizationId: UUID, id: UUID): IO[Option[Integration]] = findById(organizationId, id)
    override def save(value: Integration): IO[Unit] = IO { values += value.id -> value }
    override def delete(organizationId: UUID, id: UUID): IO[Unit] = IO { values -= id }
  }
  private class MemorySecrets extends IntegrationSecretRepository[IO] {
    private var values = Map.empty[UUID, IntegrationSecret]
    override def save(value: IntegrationSecret): IO[Unit] = IO { values += value.id -> value }
    override def find(org: UUID, id: UUID): IO[Option[IntegrationSecret]] =
      IO.pure(values.get(id).filter(_.organizationId == org))
    override def delete(org: UUID, id: UUID): IO[Unit] = IO { values -= id }
  }

  private def node(uuid: String, name: String) = ObservedIntegrationObject(IntegrationObjectType.Node, uuid, name,
    RemnawaveNodeSummary("10.0.0.1", Some(2222), isConnected = true, isConnecting = false, isDisabled = false, None,
      Some("25.1.1"), Some("2.0.0"), 10L, trafficTrackingActive = false, None, Some(5L), 1L, "DE",
      Some(2), None, None, None, Nil, None, None))

  private final class World {
    val integrations = new MemoryIntegrations
    val secrets = new MemorySecrets
    val journal = new RecordingAuditEventRepository
    val memory = new InMemoryIntegrationInventory
    val cipher = IntegrationCredentialCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(
      Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key)).toOption.get)
    val audit = TestAuditRecorder(journal)
    @volatile var activeAction = false
    val actionRepository = new IntegrationActionRepository[IO] {
      override def hasActive(organizationId: UUID, integrationId: UUID): IO[Boolean] = IO(activeAction)
      override def insertOrFind(value: IntegrationActionExecution): IO[(IntegrationActionExecution, Boolean)] =
        IO.raiseError(new UnsupportedOperationException)
      override def findByRequest(organizationId: UUID, requestId: UUID): IO[Option[IntegrationActionExecution]] =
        IO.pure(None)
      override def find(organizationId: UUID, integrationId: UUID, id: UUID): IO[Option[IntegrationActionExecution]] =
        IO.pure(None)
      override def recent(organizationId: UUID, integrationId: UUID, limit: Int): IO[List[IntegrationActionExecution]] =
        IO.pure(Nil)
      override def latestUnknownFinishedAt(organizationId: UUID, integrationId: UUID,
        inventoryObjectId: UUID): IO[Option[Instant]] = IO.pure(None)
      override def recoverAndClaim(owner: UUID, token: UUID, at: Instant, recoverAfter: Instant,
        limit: Int): IO[(Int, List[IntegrationActionExecution])] = IO.pure((0, Nil))
      override def complete(value: IntegrationActionExecution, token: UUID, at: Instant,
        status: String, errorCode: Option[String], errorMessage: Option[String]): IO[Boolean] = IO.pure(false)
    }
    val configProfiles = new IntegrationConfigProfileRepository[IO] {
      override def binding(organizationId: UUID, integrationId: UUID, objectId: UUID):
        IO[Option[IntegrationConfigProfileBinding]] = IO.pure(None)
      override def detachAll(organizationId: UUID, integrationId: UUID, at: Instant): IO[Unit] = IO.unit
      override def insertBinding(value: IntegrationConfigProfileBinding): IO[Boolean] = IO.pure(false)
      override def insertSecureRevision(revisionId: UUID, organizationId: UUID, profileId: UUID,
        canonicalJson: String, at: Instant): IO[Unit] = IO.unit
      override def secureRevision(organizationId: UUID, profileId: UUID,
        revisionNumber: Int): IO[Option[IntegrationSecureRevision]] = IO.pure(None)
      override def revisionHash(organizationId: UUID, profileId: UUID, revisionNumber: Int): IO[Option[String]] =
        IO.pure(None)
    }
    val management = new IntegrationManagement[IO](integrations, secrets, new SystemIdGenerator,
      new SystemTimeProvider, cipher, audit, memory.syncState, actionRepository, memory.inventory,
      configProfiles, (_, _) => IO.pure(false))
    var auditedBeforeProbe = false
    var auditedBeforeObserve = false
    var observations = 0
    @volatile var observation: IO[IntegrationObservation] = IO.pure(IntegrationObservation(
      List(node("node-a", "Alpha"), node("node-b", "Beta")), IntegrationObjectType.All.toSet))
    val provider = new IntegrationProvider[IO] {
      override val providerType = IntegrationProviderType.Remnawave
      override val displayName = "Remnawave"
      override val capabilities: Set[IntegrationCapability] =
        Set(IntegrationCapability.ConnectivityTest, IntegrationCapability.DesiredState)
      override def executeAction(context: IntegrationRuntimeContext, externalId: String,
        action: domain.integration.IntegrationActionCode): IO[domain.integration.IntegrationActionRemoteOutcome] =
        IO.raiseError(new IllegalStateException)
      override def testConnection(context: IntegrationRuntimeContext): IO[IntegrationTestResult] = IO {
        auditedBeforeProbe = journal.recorded.exists(_.action.code == "INTEGRATION_TEST_REQUESTED")
        IntegrationTestResult(ok = true, providerType, 3)
      }
      override def observe(context: IntegrationRuntimeContext): IO[IntegrationObservation] = IO {
        observations += 1
        auditedBeforeObserve = journal.recorded.exists(_.action.code == "INTEGRATION_SYNC_REQUESTED")
      } *> observation
    }
    val registry = new IntegrationProviderRegistry[IO](List(provider))
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]): IO[A] = program }
    val sync = new IntegrationSync[IO](new IntegrationSyncTransactions[IO](integrations, secrets, memory.sessions,
      memory.inventory, new SystemIdGenerator, new SystemTimeProvider, audit, support.NoDesiredStates), runner, cipher,
      registry,
      NoOpLogger[IO], 5.seconds, 100)
    val bindings = new IntegrationBindings[IO](integrations, memory.inventory, memory.bindings,
      new SystemIdGenerator, new SystemTimeProvider, audit)
    val desiredStates = new application.integration.IntegrationDesiredStates[IO](integrations, memory.inventory,
      support.NoDesiredStates, registry, new SystemIdGenerator, new SystemTimeProvider, audit, desiredStateOperational = true)
    val routes = cats.syntax.semigroupk.toSemigroupKOps(new IntegrationRoutes[IO](management,
      new TestIntegration[IO](management, runner, cipher, registry), registry, runner,
      AuthorizationFixtures.authorization, sync, bindings, memory.query, memory.sessions).routes)
      .combineK(new IntegrationDesiredStateRoutes[IO](desiredStates, runner, AuthorizationFixtures.authorization,
        NoOpLogger[IO]).routes).orNotFound

    def request(method: Method, path: String, body: Option[String] = None,
      role: OrganizationRole = OrganizationRole.Owner) = {
      val base = Request[IO](method, Uri.unsafeFromString(path))
      AuthorizationFixtures.as(body.fold(base)(value => base.withEntity(value).putHeaders(
        Header.Raw(CIString("Content-Type"), "application/json"))), org, role)
    }
    def call(method: Method, path: String, body: Option[String] = None, role: OrganizationRole = OrganizationRole.Owner) =
      routes.run(request(method, path, body, role)).unsafeRunSync()
    def created(): String = {
      val response = call(Method.POST, root, Some(s"""{"name":"Main","providerType":"REMNAWAVE",
        "baseUrl":"https://panel.example.test","credentials":{"apiToken":"$token","caddyApiKey":null}}"""))
      assertEquals(response.status, Status.Created)
      response.as[Json].unsafeRunSync().hcursor.get[String]("id").toOption.get
    }
    def actions: List[String] = journal.recorded.map(_.action.code)
  }

  test("owner can create, list and request a test; responses and audit never contain token") {
    val f = new World
    val create = f.call(Method.POST, root, Some(s"""{"name":"Main","providerType":"REMNAWAVE",
      "baseUrl":"https://panel.example.test","credentials":{"apiToken":"$token","caddyApiKey":null}}"""))
    assertEquals(create.status, Status.Created)
    val createdJson = create.as[Json].unsafeRunSync()
    val id = createdJson.hcursor.get[String]("id").toOption.get
    assertEquals(createdJson.hcursor.get[Boolean]("enabled"), Right(false))
    assertEquals(createdJson.hcursor.downField("credential").get[Boolean]("apiTokenConfigured"), Right(true))
    assert(!createdJson.noSpaces.contains(token))
    // Every integration gets its automatic schedule when it is created.
    assert(f.memory.states.contains((org, UUID.fromString(id))))
    val member = f.call(Method.GET, root, role = OrganizationRole.Member)
    assertEquals(member.status, Status.Forbidden)
    assertEquals(f.integrations.listReads, 0)
    val list = f.call(Method.GET, root)
    assertEquals(list.status, Status.Ok)
    val listed = list.as[Json].unsafeRunSync()
    assert(!listed.noSpaces.contains(token))
    assertEquals(listed.hcursor.downN(0).downField("overview").downField("inventory").downField("nodes")
      .get[Long]("active"), Right(0L))
    val tested = f.call(Method.POST, s"$root/$id/test")
    assertEquals(tested.status, Status.Ok)
    assertEquals(tested.as[Json].unsafeRunSync().hcursor.get[Boolean]("ok"), Right(true))
    assert(f.auditedBeforeProbe)
    assert(!f.journal.recorded.toString.contains(token))
    val invalid = f.call(Method.POST, root, Some(s"""{"name":"Bad","providerType":"REMNAWAVE",
      "baseUrl":"http://127.0.0.1/?x=1","credentials":{"apiToken":"$token"}}"""))
    assertEquals(invalid.status, Status.BadRequest)
    val unsupported = f.call(Method.POST, root, Some(s"""{"name":"Bad","providerType":"UNKNOWN",
      "baseUrl":"https://panel.example.test","credentials":{"apiToken":"$token"}}"""))
    assertEquals(unsupported.status, Status.BadRequest)
  }

  test("manual sync works while disabled, audits before contacting the provider and lists nodes") {
    val f = new World
    val id = f.created()
    val synced = f.call(Method.POST, s"$root/$id/sync")
    assertEquals(synced.status, Status.Ok)
    val session = synced.as[Json].unsafeRunSync().hcursor
    assertEquals(session.get[String]("status"), Right("COMPLETED"))
    assertEquals(session.get[String]("trigger"), Right("MANUAL"))
    assertEquals(session.downField("counts").get[Int]("nodes"), Right(2))
    assert(f.auditedBeforeObserve)
    assertEquals(f.actions.count(_ == "INTEGRATION_SYNC_REQUESTED"), 1)
    val nodes = f.call(Method.GET, s"$root/$id/inventory/nodes?limit=1").as[Json].unsafeRunSync().hcursor
    assertEquals(nodes.get[Long]("total"), Right(2L))
    assertEquals(nodes.downField("items").downN(0).get[String]("displayName"), Right("Alpha"))
    assertEquals(nodes.downField("items").downN(0).downField("summary").get[String]("state"), Right("CONNECTED"))
    val history = f.call(Method.GET, s"$root/$id/sync-sessions").as[Json].unsafeRunSync()
    assertEquals(history.hcursor.downField("items").values.map(_.size), Some(1))
    assertEquals(f.call(Method.GET, s"$root/$id/inventory/nodes?limit=500").status, Status.BadRequest)
    assertEquals(f.call(Method.GET, s"$root/$id/inventory/hosts?state=CONNECTED").status, Status.BadRequest)
    assertEquals(f.call(Method.GET, s"$root/$id/inventory/other").status, Status.NotFound)
    assertEquals(f.call(Method.GET, s"$root/${UUID.randomUUID()}/inventory/nodes").status, Status.NotFound)
  }

  test("a failed observation fails the session with a code only and leaves the inventory unchanged") {
    val f = new World
    val id = f.created()
    assertEquals(f.call(Method.POST, s"$root/$id/sync").status, Status.Ok)
    f.observation = IO.raiseError(IntegrationError("INTEGRATION_AUTH_FAILED", s"leaked $token"))
    val failed = f.call(Method.POST, s"$root/$id/sync")
    assertEquals(failed.status, Status.Ok)
    val body = failed.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.get[String]("status"), Right("FAILED"))
    assertEquals(body.hcursor.get[String]("errorCode"), Right("INTEGRATION_AUTH_FAILED"))
    assert(!body.noSpaces.contains(token))
    assertEquals(f.memory.snapshotWrites, 1)
    assert(f.memory.objects.forall(_.isActive))
  }

  test("a session retired as stale during observation keeps its durable outcome over a later provider error") {
    val f = new World
    val id = f.created()
    // While the provider is being read, another worker retires the session as stale; then the
    // provider itself fails. The stale outcome is already durable and must be what is answered.
    f.observation = IO {
      f.memory.sessionRows = f.memory.sessionRows.map(value =>
        if (value.status == IntegrationSyncStatus.Running) value.copy(status = IntegrationSyncStatus.Failed,
          finishedAt = Some(java.time.Instant.now()), errorCode = Some(IntegrationSync.StaleCode),
          errorMessage = Some(IntegrationSync.StaleMessage)) else value)
    } *> IO.raiseError(IntegrationError("INTEGRATION_TIMEOUT", "late"))
    val body = f.call(Method.POST, s"$root/$id/sync").as[Json].unsafeRunSync().hcursor
    val stored = f.memory.sessionRows.head
    assertEquals(stored.errorCode, Some(IntegrationSync.StaleCode))
    assertEquals(body.get[String]("status"), Right("FAILED"))
    assertEquals(body.get[String]("errorCode"), Right(IntegrationSync.StaleCode))
    assertEquals(body.get[String]("id"), Right(stored.id.toString))
    assertEquals(f.memory.snapshotWrites, 0)
  }

  test("an active action freezes endpoint, credential and deletion; a name edit stays allowed") {
    val f = new World
    val id = f.created()
    f.call(Method.POST, s"$root/$id/sync")
    f.activeAction = true
    def put(body: String) = f.call(Method.PUT, s"$root/$id", Some(body))
    assertEquals(put("""{"name":"Renamed","baseUrl":"https://panel.example.test"}""").status, Status.Ok)
    val moved = put("""{"name":"Renamed","baseUrl":"https://other.example.test"}""")
    assertEquals(moved.status, Status.Conflict)
    assertEquals(moved.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("INTEGRATION_ACTION_ALREADY_RUNNING"))
    assertEquals(put("""{"name":"Renamed","baseUrl":"https://panel.example.test","credentials":{"apiToken":"t2"}}""").status,
      Status.Conflict)
    assertEquals(f.call(Method.DELETE, s"$root/$id").status, Status.Conflict)
    assert(f.memory.objects.forall(_.isActive), "a refused change touches nothing")
    // Once nothing is in flight the endpoint may change, and what was observed at the old one is no longer current.
    f.activeAction = false
    assertEquals(put("""{"name":"Renamed","baseUrl":"https://other.example.test"}""").status, Status.Ok)
    assert(f.memory.objects.nonEmpty && f.memory.objects.forall(!_.isActive))
    assertEquals(f.call(Method.DELETE, s"$root/$id").status, Status.NoContent)
  }

  test("management mode: explicit, needs automatic sync, and freezes endpoint, credential and observation") {
    val f = new World
    val id = f.created()
    val modePath = s"$root/$id/management-mode"
    def mode(value: String, role: OrganizationRole = OrganizationRole.Owner) =
      f.call(Method.PUT, modePath, Some(s"""{"mode":"$value"}"""), role)
    def codeOf(response: _root_.org.http4s.Response[IO]) = response.as[Json].unsafeRunSync().hcursor.get[String]("code")
    assertEquals(f.call(Method.GET, s"$root/$id").as[Json].unsafeRunSync().hcursor.get[String]("managementMode"),
      Right("OBSERVE"))
    // Intent leads to remote writes: a member can neither switch the mode nor set or remove a desired state.
    val objectPath = s"$root/$id/inventory/objects/${UUID.randomUUID()}/desired-state"
    assertEquals(mode("MANAGED_SELECTED", OrganizationRole.Member).status, Status.Forbidden)
    assertEquals(f.call(Method.PUT, objectPath, Some("""{"state":"ENABLED"}"""), OrganizationRole.Member).status,
      Status.Forbidden)
    assertEquals(f.call(Method.DELETE, objectPath, role = OrganizationRole.Member).status, Status.Forbidden)
    assertEquals(mode("FULL_MANAGED").status, Status.BadRequest)
    assertEquals(f.call(Method.PUT, objectPath, Some("""{"state":"RESTARTED"}""")).status, Status.BadRequest)
    // A disabled integration has no fresh observations to manage against.
    val early = mode("MANAGED_SELECTED")
    assertEquals(early.status, Status.Conflict)
    assertEquals(codeOf(early), Right("INTEGRATION_MANAGEMENT_REQUIRES_SYNC"))
    assertEquals(f.call(Method.POST, s"$root/$id/enable").status, Status.Ok)
    val managed = mode("MANAGED_SELECTED")
    assertEquals(managed.status, Status.Ok)
    assertEquals(managed.as[Json].unsafeRunSync().hcursor.get[String]("managementMode"), Right("MANAGED_SELECTED"))
    assertEquals(f.actions.count(_ == "INTEGRATION_MANAGEMENT_MODE_CHANGED"), 1)
    assertEquals(mode("MANAGED_SELECTED").status, Status.Ok)
    assertEquals(f.actions.count(_ == "INTEGRATION_MANAGEMENT_MODE_CHANGED"), 1)
    // Setting a desired state on something that is not in the inventory.
    assertEquals(f.call(Method.PUT, objectPath, Some("""{"state":"ENABLED"}""")).status, Status.NotFound)
    val disable = f.call(Method.POST, s"$root/$id/disable")
    assertEquals((disable.status, codeOf(disable)), (Status.Conflict, Right("INTEGRATION_MANAGEMENT_REQUIRES_SYNC")))
    val moved = f.call(Method.PUT, s"$root/$id", Some("""{"name":"Main","baseUrl":"https://other.example.test"}"""))
    assertEquals((moved.status, codeOf(moved)), (Status.Conflict, Right("INTEGRATION_MANAGEMENT_ACTIVE")))
    assertEquals(f.call(Method.PUT, s"$root/$id", Some("""{"name":"Renamed","baseUrl":"https://panel.example.test"}""")).status,
      Status.Ok)
    assertEquals(mode("OBSERVE").status, Status.Ok)
    assertEquals(f.call(Method.POST, s"$root/$id/disable").status, Status.Ok)
    assertEquals(f.observations, 0)
  }

  test("a snapshot naming one object twice is rejected as a whole") {
    val f = new World
    val id = f.created()
    f.observation = IO.pure(IntegrationObservation(List(node("same", "A"), node("same", "B")),
      IntegrationObjectType.All.toSet))
    val body = f.call(Method.POST, s"$root/$id/sync").as[Json].unsafeRunSync().hcursor
    assertEquals(body.get[String]("errorCode"), Right("INTEGRATION_INVALID_RESPONSE"))
    assertEquals(f.memory.snapshotWrites, 0)
  }

  test("a second manual sync while one is running is refused with 409") {
    val f = new World
    val id = f.created()
    val gate = cats.effect.Deferred.unsafe[IO, Unit]
    f.observation = gate.get.as(IntegrationObservation(Nil, IntegrationObjectType.All.toSet))
    val first = f.routes.run(f.request(Method.POST, s"$root/$id/sync")).start.unsafeRunSync()
    while (!f.memory.sessionRows.exists(_.status == IntegrationSyncStatus.Running)) Thread.sleep(5)
    val second = f.call(Method.POST, s"$root/$id/sync")
    assertEquals(second.status, Status.Conflict)
    assertEquals(second.as[Json].unsafeRunSync().hcursor.get[String]("code"), Right("INTEGRATION_SYNC_ALREADY_RUNNING"))
    gate.complete(()).unsafeRunSync()
    assertEquals(first.joinWithNever.unsafeRunSync().status, Status.Ok)
    assertEquals(f.actions.count(_ == "INTEGRATION_SYNC_REQUESTED"), 1)
  }

  test("bindings: NODE only, same resource is a no-op, replace and unbind are audited") {
    val f = new World
    val id = f.created()
    f.call(Method.POST, s"$root/$id/sync")
    val objectId = f.memory.objects.find(_.externalId == "node-a").get.id
    val nodeResource = UUID.randomUUID(); val otherNode = UUID.randomUUID()
    val container = UUID.randomUUID(); val archived = UUID.randomUUID()
    f.memory.resources = Map(
      nodeResource -> (org -> BindableResource(nodeResource, "NODE", active = true)),
      otherNode -> (org -> BindableResource(otherNode, "NODE", active = true)),
      container -> (org -> BindableResource(container, "DOCKER_CONTAINER", active = true)),
      archived -> (org -> BindableResource(archived, "NODE", active = false)))
    val bindPath = s"$root/$id/inventory/objects/$objectId/binding"
    def bind(resource: UUID) = f.call(Method.PUT, bindPath, Some(s"""{"resourceId":"$resource"}"""))
    assertEquals(bind(container).status, Status.UnprocessableEntity)
    assertEquals(bind(archived).status, Status.UnprocessableEntity)
    assertEquals(bind(UUID.randomUUID()).status, Status.UnprocessableEntity)
    assertEquals(bind(nodeResource).status, Status.Ok)
    val firstBinding = f.memory.bindingRows.head
    assertEquals(bind(nodeResource).status, Status.Ok)
    assertEquals(f.memory.bindingRows, Vector(firstBinding))
    assertEquals(f.actions.count(_ == "INTEGRATION_RESOURCE_BOUND"), 1)
    assertEquals(bind(otherNode).status, Status.Ok)
    assertEquals(f.memory.bindingRows.map(_.resourceId), Vector(otherNode))
    assertEquals(f.memory.bindingRows.head.id, firstBinding.id)
    assertEquals(f.actions.count(_ == "INTEGRATION_RESOURCE_BOUND"), 2)
    val listed = f.call(Method.GET, s"$root/$id/inventory/nodes?search=alpha").as[Json].unsafeRunSync().hcursor
    assertEquals(listed.downField("items").downN(0).downField("binding").downField("resource").get[String]("id"),
      Right(otherNode.toString))
    assertEquals(f.call(Method.DELETE, bindPath).status, Status.NoContent)
    assertEquals(f.call(Method.DELETE, bindPath).status, Status.NoContent)
    assertEquals(f.actions.count(_ == "INTEGRATION_RESOURCE_UNBOUND"), 1)
    assertEquals(f.call(Method.PUT, s"$root/$id/inventory/objects/${UUID.randomUUID()}/binding",
      Some(s"""{"resourceId":"$nodeResource"}""")).status, Status.NotFound)
    val candidates = f.call(Method.GET, s"$root/$id/binding-candidates").as[Json].unsafeRunSync()
    assertEquals(candidates.hcursor.downField("items").values.map(_.size), Some(2))
  }

  test("members without ManageIntegrations never reach inventory, sync or bindings") {
    val f = new World
    val id = f.created()
    val paths = List(Method.POST -> s"$root/$id/sync", Method.GET -> s"$root/$id/sync-sessions",
      Method.GET -> s"$root/$id/inventory/summary", Method.GET -> s"$root/$id/inventory/nodes",
      Method.GET -> s"$root/$id/binding-candidates",
      Method.DELETE -> s"$root/$id/inventory/objects/${UUID.randomUUID()}/binding",
      Method.GET -> s"/api/v1/organizations/$org/resources/${UUID.randomUUID()}/integration-bindings")
    paths.foreach { case (method, path) =>
      assertEquals(f.call(method, path, role = OrganizationRole.Member).status, Status.Forbidden, path)
    }
    assertEquals(f.observations, 0)
  }
}
