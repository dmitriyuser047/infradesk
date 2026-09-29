package ru.bitec.app.ops
package application.integration

import application.port.{IntegrationRepository, IntegrationSecret, IntegrationSecretRepository, TimeProvider,
  TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.integration._
import infrastructure.runtime.SystemIdGenerator
import integration.secret.IntegrationCredentialCipher
import integration.ssh.SecretEncryptionConfig
import munit.FunSuite
import org.typelevel.log4cats.noop.NoOpLogger
import support.{InMemoryIntegrationInventory, RecordingAuditEventRepository, TestAuditRecorder}

import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration._

final class IntegrationSyncSchedulerSpec extends FunSuite {
  private val org = UUID.randomUUID()
  private val start = Instant.parse("2026-09-29T10:00:00Z")
  private val cipher = IntegrationCredentialCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(Map(
    "INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(Array.fill[Byte](32)(3)))).toOption.get)

  private final class World(behaviour: Map[String, IO[IntegrationObservation]]) {
    @volatile var now: Instant = start
    val time: TimeProvider[IO] = new TimeProvider[IO] { override def now: IO[Instant] = IO(World.this.now) }
    val memory = new InMemoryIntegrationInventory
    val journal = new RecordingAuditEventRepository
    @volatile var integrations = Map.empty[UUID, Integration]
    @volatile var secrets = Map.empty[UUID, IntegrationSecret]
    @volatile var observed = List.empty[String]
    val repository: IntegrationRepository[IO] = new IntegrationRepository[IO] {
      override def listByOrganization(o: UUID) = IO(integrations.values.toList)
      override def findById(o: UUID, id: UUID) = IO(integrations.get(id).filter(_.organizationId == o))
      override def findByIdForUpdate(o: UUID, id: UUID) = findById(o, id)
      override def save(value: Integration) = IO { integrations += value.id -> value }
      override def delete(o: UUID, id: UUID) = IO { integrations -= id }
    }
    val secretRepository: IntegrationSecretRepository[IO] = new IntegrationSecretRepository[IO] {
      override def save(value: IntegrationSecret) = IO { secrets += value.id -> value }
      override def find(o: UUID, id: UUID) = IO(secrets.get(id))
      override def delete(o: UUID, id: UUID) = IO { secrets -= id }
    }
    val provider: IntegrationProvider[IO] = new IntegrationProvider[IO] {
      override val providerType = IntegrationProviderType.Remnawave
      override val displayName = "Remnawave"
      override val capabilities: Set[IntegrationCapability] = Set.empty
      override def testConnection(context: IntegrationRuntimeContext) = IO.raiseError(new IllegalStateException)
      override def observe(context: IntegrationRuntimeContext) = {
        val name = integrations(context.id).name
        IO { observed :+= name } *> behaviour.getOrElse(name, IO.pure(IntegrationObservation(Nil, IntegrationObjectType.All.toSet)))
      }
    }
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]): IO[A] = program }
    val txTime: TimeProvider[IO] = time
    val sync = new IntegrationSync[IO](new IntegrationSyncTransactions[IO](repository, secretRepository,
      memory.sessions, memory.inventory, new SystemIdGenerator, txTime, TestAuditRecorder(journal)), runner, cipher,
      new IntegrationProviderRegistry[IO](List(provider)), NoOpLogger[IO], 2.seconds, 100)
    val settings = IntegrationSyncSchedulerSettings(1.second, 60.seconds, batchSize = 10, maxConcurrency = 2,
      claimLease = 90.seconds)
    val scheduler = new IntegrationSyncScheduler[IO](sync, memory.syncState, runner, time, NoOpLogger[IO], settings,
      UUID.randomUUID())

    def add(name: String, enabled: Boolean): UUID = {
      val id = UUID.randomUUID(); val secretId = UUID.randomUUID()
      integrations += id -> Integration(id, org, name, IntegrationProviderType.Remnawave,
        IntegrationBaseUrl.parse("https://panel.example.test").toOption.get, enabled, secretId, caddyApiKeyConfigured = false,
        start, start)
      secrets += secretId -> cipher.encrypt(secretId, org, RemnawaveCredential("token", None))
      if (enabled) memory.enabled += id
      memory.syncState.ensure(org, id, start).unsafeRunSync()
      id
    }
    def state(id: UUID) = memory.states((org, id))
  }

  test("only enabled integrations are claimed; success schedules the next run one interval later") {
    val world = new World(Map.empty)
    val enabled = world.add("enabled", enabled = true)
    val disabled = world.add("disabled", enabled = false)
    world.scheduler.tick.unsafeRunSync()
    assertEquals(world.observed, List("enabled"))
    assertEquals(world.state(enabled), (start.plusSeconds(60), 0L, None))
    assertEquals(world.state(disabled), (start, 0L, None))
    // Nothing is due before the next run.
    world.scheduler.tick.unsafeRunSync()
    assertEquals(world.observed, List("enabled"))
    // Automatic runs are not audited: nobody asked for them.
    assertEquals(world.journal.recorded, Nil)
    assertEquals(world.memory.sessionRows.map(_.trigger), Vector(IntegrationSyncTrigger.Scheduled))
  }

  test("failures back off, one failing integration never stops the others, success resets the count") {
    val world = new World(Map(
      "failing" -> IO.raiseError(IntegrationError("INTEGRATION_REMOTE_UNAVAILABLE", "x")),
      "broken" -> IO.raiseError(new IllegalStateException("unexpected"))))
    val failing = world.add("failing", enabled = true)
    val broken = world.add("broken", enabled = true)
    val healthy = world.add("healthy", enabled = true)
    world.scheduler.tick.unsafeRunSync()
    assertEquals(world.observed.sorted, List("broken", "failing", "healthy"))
    assertEquals(world.state(failing), (start.plusSeconds(300), 1L, None))
    assertEquals(world.state(broken), (start.plusSeconds(300), 1L, None))
    assertEquals(world.state(healthy), (start.plusSeconds(60), 0L, None))
    world.now = start.plusSeconds(300)
    world.scheduler.tick.unsafeRunSync()
    assertEquals(world.state(failing), (start.plusSeconds(1200), 2L, None))
    assert(world.memory.sessionRows.filter(_.integrationId == failing).forall(_.status == IntegrationSyncStatus.Failed))
  }

  test("an integration disabled after its claim is skipped without a session") {
    val world = new World(Map.empty)
    val id = world.add("flip", enabled = true)
    world.integrations += id -> world.integrations(id).copy(enabled = false)
    world.scheduler.tick.unsafeRunSync()
    assertEquals(world.observed, Nil)
    assertEquals(world.memory.sessionRows, Vector.empty)
    assertEquals(world.state(id)._3, None)
  }

  test("a stale RUNNING session is recovered by the next attempt; a live one makes the run skip") {
    val world = new World(Map.empty)
    val id = world.add("stale", enabled = true)
    val live = IntegrationSyncSession(UUID.randomUUID(), org, id, IntegrationSyncTrigger.Manual, None, start,
      start.plusSeconds(120), None, IntegrationSyncStatus.Running, None, None, None)
    world.memory.sessionRows = Vector(live)
    world.scheduler.tick.unsafeRunSync()
    assertEquals(world.observed, Nil)
    // Skipped runs keep their failure count and try again one interval later.
    assertEquals(world.state(id), (start.plusSeconds(60), 0L, None))
    world.now = start.plusSeconds(180)
    world.memory.states += (org, id) -> ((world.now, 0L, None))
    world.scheduler.tick.unsafeRunSync()
    assertEquals(world.observed, List("stale"))
    val recovered = world.memory.sessionRows.find(_.id == live.id).get
    assertEquals(recovered.status, IntegrationSyncStatus.Failed)
    assertEquals(recovered.errorCode, Some(IntegrationSync.StaleCode))
  }

  test("a lost claim does not overwrite the schedule another worker now holds") {
    val world = new World(Map.empty)
    val id = world.add("contested", enabled = true)
    val claim = world.memory.syncState.claimDue(UUID.randomUUID(), 10, 90, start).unsafeRunSync().head
    world.memory.states += (org, id) -> ((start, 0L, Some(UUID.randomUUID())))
    assert(!world.memory.syncState.completeClaimedRun(claim, start.plusSeconds(60), 0, start).unsafeRunSync())
    assert(world.state(id)._3.nonEmpty)
  }

  test("an observation that outlives the attempt timeout fails the session as a timeout") {
    val world = new World(Map("slow" -> IO.never[IntegrationObservation]))
    world.add("slow", enabled = true)
    world.scheduler.tick.timeout(20.seconds).unsafeRunSync()
    val session = world.memory.sessionRows.head
    assertEquals(session.status, IntegrationSyncStatus.Failed)
    assertEquals(session.errorCode, Some("INTEGRATION_TIMEOUT"))
  }
}
