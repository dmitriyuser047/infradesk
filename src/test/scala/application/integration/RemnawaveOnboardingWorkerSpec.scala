package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.port._
import application.provisioning.ProvisioningSettings
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.integration._
import domain.provisioning._
import integration.secret.NodeInstallationCipher
import integration.ssh.SecretEncryptionConfig
import java.time.Instant
import java.util.{Base64, UUID}
import munit.FunSuite
import org.typelevel.log4cats.noop.NoOpLogger
import scala.collection.mutable
import scala.concurrent.duration._
import support.{RecordingAuditEventRepository, TestAuditRecorder}

final class RemnawaveOnboardingWorkerSpec extends FunSuite {
  private val now = Instant.parse("2026-10-01T12:00:00Z")
  private def uid = UUID.randomUUID()
  private val org = uid
  private val integrationId = uid
  private val resource = uid
  private val inbound = uid
  private val nodeId = uid
  private val secretText = "installation-secret-do-not-serialize"
  private val api = NodeApiCompatibility(Some("2.8.0"), Some("PROFILE_PUBKEY"), Some("reviewed-commit"),
    Set(NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData,
      NodeProvisioningCapability.Status, NodeProvisioningCapability.ConfigProfile,
      NodeProvisioningCapability.CreateReconciliation, NodeProvisioningCapability.CreateIdempotency), None)
  private val input = OnboardingInput(resource, "edge-01", "192.0.2.20", 30222, uid, List(inbound), List("198.51.100.0/24"))
  private val snap = OnboardingSnapshot(input, now, uid, uid, now, uid, uid, 3, uid, 2L, "hash", uid,
    false, api, "remnawave/node:2.8.0", uid, "server", "baseline", "profile", List("inbound"), Nil, Nil, Nil)
  private val baselineSnap = snap.copy(baselineNeeded = true)
  private val connection = Connection(uid, org, ConnectionScope.Organization, "SSH", "edge", "Edge",
    ConnectionConfig(Map.empty), None, true, now, now)
  private val localGood = RemnawaveNodeLocalEvidence(true, true, true, true, true, true)
  private val noMaster = SecretEncryptionConfig.fromEnvironment(Map(
    "INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))).toOption.get
  private val cipher = NodeInstallationCipher.fromConfig(noMaster)

  private def node(id: UUID = nodeId, connected: Boolean = true, disabled: Boolean = false,
    wrongName: Boolean = false): ProvisionedNode = ProvisionedNode(id,
    if (wrongName) "other-node" else input.nodeName, input.address, Some(input.nodePort), connected, !connected,
    disabled, Some(input.configProfileId), input.activeInboundIds,
    List("ID:" + snap.correlationId.toString.replace("-", "").toUpperCase(java.util.Locale.ROOT)))

  private final class MemoryRepo(initialPhase: OnboardingPhase, oldStartedAt: Instant = now) extends RemnawaveOnboardingRepository[IO] {
    val runId = uid
    val token = uid
    var record = RemnawaveNodeOnboardingRun(runId, org, integrationId, resource, None, uid,
      ProvisioningRunState.Running, initialPhase, snap, now, now, externalNodeId =
        if (initialPhase == OnboardingPhase.CreateNode) None else Some(nodeId),
      startedAt = Some(now), claimToken = Some(token), claimDeadline = Some(now.plusSeconds(900)))
    var inFlight = true // seed phase as already RUNNING, which also exercises crash recovery
    var phases = List(OnboardingPhaseRecord(initialPhase, "RUNNING", Some(oldStartedAt), None, None))
    var storedSecret: Option[IntegrationSecret] = None
    var savedSecrets = List.empty[IntegrationSecret]
    var completed = List.empty[OnboardingPhase]
    var staleRejected = false
    override def insertPlan(r: RemnawaveNodeOnboardingRun) = IO.unit
    override def lockResource(o: UUID, r: UUID) = IO.unit
    override def active(o: UUID, r: UUID) = IO.pure(false)
    override def start(o: UUID, i: UUID, p: UUID, q: UUID, a: UUID, at: Instant) = IO.pure(record)
    override def find(o: UUID, i: UUID, id: UUID) = IO.pure(Some(record -> phases))
    override def history(o: UUID, i: UUID, limit: Int) = IO.pure(List(record))
    override def claim(owner: UUID, claimToken: UUID, at: Instant, until: Instant, limit: Int) = IO {
      if (record.state.terminal) Nil else { record = record.copy(claimToken = Some(claimToken), claimDeadline = Some(until)); List(record) }
    }
    override def renew(r: RemnawaveNodeOnboardingRun, claimToken: UUID, at: Instant, until: Instant) = IO {
      val ok = record.id == r.id && record.claimToken.contains(claimToken)
      if (ok) record = record.copy(claimDeadline = Some(until)); ok
    }
    override def beginPhase(r: RemnawaveNodeOnboardingRun, claimToken: UUID, at: Instant) = IO {
      if (!record.claimToken.contains(claimToken)) false else if (inFlight) { false } else { inFlight = true; true }
    }
    override def persist(r: RemnawaveNodeOnboardingRun, claimToken: UUID, next: RemnawaveNodeOnboardingRun,
      at: Instant, completePhase: Boolean) = IO {
      if (!record.claimToken.contains(claimToken) || record.id != r.id) { staleRejected = true; false }
      else {
        record = next.copy(updatedAt = at)
        if (completePhase) {
          completed :+= r.phase
          phases = phases.map(p => if (p.phase == r.phase && p.finishedAt.isEmpty) p.copy(state = "SUCCEEDED", finishedAt = Some(at)) else p)
          inFlight = false
          if (!next.state.terminal && next.phase != r.phase) phases :+= OnboardingPhaseRecord(next.phase, "RUNNING", Some(at), None, None)
        } else if (next.state.terminal) {
          phases = phases.map(p => if (p.phase == r.phase && p.finishedAt.isEmpty) p.copy(state = next.state.code, finishedAt = Some(at), failureCode = next.failureCode) else p)
          inFlight = false
        }
        true
      }
    }
    override def attachBaseline(r: RemnawaveNodeOnboardingRun, claimToken: UUID, at: Instant) = IO.pure(uid)
    override def saveSecret(r: RemnawaveNodeOnboardingRun, claimToken: UUID, s: IntegrationSecret, at: Instant) = IO {
      storedSecret = Some(s); savedSecrets :+= s.copy(nonce = s.nonce.clone(), ciphertext = s.ciphertext.clone())
    }
    override def secret(r: RemnawaveNodeOnboardingRun) = IO.pure(storedSecret)
    override def deleteSecret(r: RemnawaveNodeOnboardingRun, claimToken: UUID, at: Instant) = IO { storedSecret = None }
    override def cleanupPlans(before: Instant, limit: Int) = IO.pure(0)
  }

  private final class Harness(start: OnboardingPhase, initialPanelNode: ProvisionedNode = node(),
    reconciliation: NodeCreateReconciliation = NodeCreateReconciliation.Confirmed(node()),
    createOutcome: NodeCreateOutcome = NodeCreateOutcome.Created(node()), startedAt: Instant = now) {
    val repo = new MemoryRepo(start, startedAt)
    val events = mutable.ListBuffer.empty[String]
    var panel: ProvisionedNode = initialPanelNode
    var recon = reconciliation
    var create = createOutcome
    var local = localGood
    var syncStatus: IntegrationSyncStatus = IntegrationSyncStatus.Completed
    var bindingVerified = true
    var bindConflict = false
    var failPanelApi = false
    var inspectedApi = api
    var childStartCount = 0
    var validateCompliant = true
    var childSequence = List.empty[ProvisioningRunState]
    val remote = new RemnawaveNodeRemote[IO] {
      override def installationPrerequisites(c: Connection) = IO.pure(ProvisioningStepResult(Map.empty, None, Some(true)))
      override def preflight(c: Connection, r: UUID, p: Int) = IO { events += "preflight"; ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def configureFirewall(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "firewall"; ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def install(c: Connection, s: RemnawaveNodeRemoteSpec, d: NodeInstallationData) = IO {
        events += "install"; assertEquals(d.secretKey, secretText); ProvisioningStepResult(Map.empty, None, Some(true))
      }
      override def start(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "start"; ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def observe(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "local-observe"; local }
      override def installationPresent(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "install-present"; true }
      override def firewallPresent(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "firewall-present"; true }
    }
    val operations = new RemnawaveOnboardingOperations[IO] {
      override def runtime(r: RemnawaveNodeOnboardingRun) = IO { events += "runtime"; IntegrationRuntimeContext(integrationId, org,
        IntegrationBaseUrl.parse("https://panel.example.test").toOption.get, RemnawaveCredential("token", None)) -> connection }
      override def validate(r: RemnawaveNodeOnboardingRun, requireCompliant: Boolean) = IO {
        events += (if (requireCompliant) "validate-compliant" else "validate");
        if (requireCompliant && !validateCompliant) throw IntegrationError("REMNAWAVE_ONBOARDING_PROFILE_NOT_COMPLIANT", "drift")
      }
      override def startBaseline(r: RemnawaveNodeOnboardingRun, token: UUID) = IO { childStartCount += 1; events += "baseline-start"; uid }
      override def baseline(r: RemnawaveNodeOnboardingRun, id: UUID) = IO {
        events += "baseline-poll"
        val state = childSequence.headOption.getOrElse(ProvisioningRunState.Succeeded)
        if (childSequence.nonEmpty) childSequence = childSequence.tail
        ProvisioningRun(id, org, resource, None, None,
          ProvisioningInputSnapshot(1, ProvisioningRunKind.ServerBaselineCheck, org, resource, "SERVER", "LINUX",
            connection.id, connection.updatedAt, Nil), state, now, now,
          failureCode = if (state == ProvisioningRunState.Failed || state == ProvisioningRunState.Unknown) Some("CHILD_FAILED") else None)
      }
      override def sync(r: RemnawaveNodeOnboardingRun) = IO {
        events += "sync"; IntegrationSyncSession(uid, org, integrationId, IntegrationSyncTrigger.Manual, None,
          now, now.plusSeconds(10), Some(now), syncStatus, if (syncStatus == IntegrationSyncStatus.Completed) None else Some("SYNC_FAILED"), None, None)
      }
      override def bind(r: RemnawaveNodeOnboardingRun, token: UUID) = IO {
        events += "bind"; if (bindConflict) throw IntegrationError("REMNAWAVE_ONBOARDING_BINDING_CONFLICT", "conflict")
      }
      override def setDesiredState(r: RemnawaveNodeOnboardingRun, token: UUID) = IO { events += "desired" }
      override def verifyInventoryBindingDesired(r: RemnawaveNodeOnboardingRun) = IO { events += "verify-binding"; bindingVerified }
    }
    val provisioning = new NodeProvisioningTransport[IO] {
      override def inspect(c: IntegrationRuntimeContext) = IO.pure(inspectedApi)
      override def findNodes(c: IntegrationRuntimeContext) = IO.pure(List(panel))
      override def getNode(c: IntegrationRuntimeContext, id: UUID) = IO {
        events += "get-node"; if (failPanelApi) throw IntegrationError("INTEGRATION_REMOTE_UNAVAILABLE", "offline") else panel
      }
      override def createNode(c: IntegrationRuntimeContext, i: NodeCreateIntent, reviewed: NodeApiCompatibility) = IO { events += "create"; create }
      override def installationData(c: IntegrationRuntimeContext, reviewed: NodeApiCompatibility) = IO {
        events += "installation-data"; NodeInstallationData.fromSecretKey(secretText)
      }
      override def reconcileCreate(c: IntegrationRuntimeContext, i: NodeCreateIntent, reviewed: NodeApiCompatibility) = IO { events += "reconcile"; recon }
    }
    val provider = new IntegrationProvider[IO] {
      override val providerType = IntegrationProviderType.Remnawave
      override val displayName = "Remnawave"
      override val capabilities = Set.empty[IntegrationCapability]
      override def testConnection(c: IntegrationRuntimeContext) = IO.pure(IntegrationTestResult(true, providerType, 1L))
      override def observe(c: IntegrationRuntimeContext) = IO {
        events += "inventory-observe"
        IntegrationObservation(List(ObservedIntegrationObject(IntegrationObjectType.ConfigProfile,
          input.configProfileId.toString, "profile", RemnawaveConfigProfileSummary(0, now, now, Nil,
            List(RemnawaveInboundSummary(inbound.toString, "in", "VLESS", None, None, None))))), Set(IntegrationObjectType.ConfigProfile))
      }
      override def executeAction(c: IntegrationRuntimeContext, externalId: String, action: IntegrationActionCode) =
        IO.pure(IntegrationActionRemoteOutcome.Succeeded)
      override def nodeProvisioning = Some(provisioning)
    }
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]) = program }
    val auditPair: (RecordingAuditEventRepository, AuditRecorder[IO]) = TestAuditRecorder.recording
    val settings = ProvisioningSettings(enabled = true, pollInterval = 1.second, batchSize = 1, maxConcurrency = 1,
      leaseDuration = 60.seconds, stepTimeout = 30.seconds)
    val worker = new RemnawaveOnboardingWorker[IO](repo, operations,
      new IntegrationProviderRegistry[IO](List(provider)), remote, cipher, runner, auditPair._2, settings, NoOpLogger[IO],
      RemnawaveOnboardingSettings(1.second, 6.seconds, 6.seconds), clock = IO.pure(now))
  }

  test("runs all thirteen durable phases and only succeeds with panel, local, and inventory evidence") {
    val h = new Harness(OnboardingPhase.Validate)
    h.repo.inFlight = false
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
    assertEquals(h.repo.completed, OnboardingPhase.all)
    assertEquals(h.repo.record.syncSessionId.nonEmpty, true)
    assertEquals(h.repo.storedSecret, None)
    assert(h.events.contains("install"))
    assert(h.events.contains("verify-binding"))
  }

  test("fake repository rejects persistence from a stale fencing token") {
    val repo = new MemoryRepo(OnboardingPhase.CreateNode)
    val original = repo.record
    val attempted = original.copy(externalNodeId = Some(nodeId))
    val accepted = repo.persist(original, uid, attempted, now, completePhase = true).unsafeRunSync()
    assertEquals(accepted, false)
    assertEquals(repo.record, original)
    assertEquals(repo.staleRejected, true)
  }

  test("baseline child is attached once, waited on, and must succeed compliantly") {
    val waited = new Harness(OnboardingPhase.PrepareServer)
    waited.repo.record = waited.repo.record.copy(snapshot = baselineSnap)
    waited.repo.inFlight = false
    waited.childSequence = List(ProvisioningRunState.Running, ProvisioningRunState.Succeeded)
    waited.worker.tick.unsafeRunSync()
    assertEquals(waited.childStartCount, 1)
    assertEquals(waited.repo.record.state, ProvisioningRunState.Succeeded)
    assert(waited.events.count(_ == "baseline-poll") >= 2)

    List(ProvisioningRunState.Failed -> ProvisioningRunState.Failed,
      ProvisioningRunState.Unknown -> ProvisioningRunState.Unknown).foreach { case (childState, expected) =>
      val h = new Harness(OnboardingPhase.PrepareServer)
      h.repo.record = h.repo.record.copy(snapshot = baselineSnap)
      h.repo.inFlight = false
      h.childSequence = List(childState)
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.state, expected)
    }

    val drifted = new Harness(OnboardingPhase.PrepareServer)
    drifted.repo.record = drifted.repo.record.copy(snapshot = baselineSnap)
    drifted.repo.inFlight = false
    drifted.validateCompliant = false
    drifted.worker.tick.unsafeRunSync()
    assertEquals(drifted.repo.record.state, ProvisioningRunState.Failed)
    assertEquals(drifted.repo.record.failureCode, Some("REMNAWAVE_ONBOARDING_PROFILE_NOT_COMPLIANT"))
  }

  test("recovers an in-flight create by reconciliation without posting create again") {
    val h = new Harness(OnboardingPhase.CreateNode, reconciliation = NodeCreateReconciliation.Confirmed(node()))
    h.worker.tick.unsafeRunSync()
    assertEquals(h.events.count(_ == "create"), 0)
    assertEquals(h.events.count(_ == "reconcile"), 1)
    assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
  }

  test("unproven reconciliation leaves create outcome UNKNOWN and performs no second create") {
    val h = new Harness(OnboardingPhase.CreateNode, reconciliation = NodeCreateReconciliation.NotProven)
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state, ProvisioningRunState.Unknown)
    assertEquals(h.repo.record.failureCode, Some("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN"))
    assertEquals(h.events.count(_ == "create"), 0)
  }

  test("a rejected create is FAILED while an unknown create is reconciled") {
    val rejected = new Harness(OnboardingPhase.CreateNode, createOutcome = NodeCreateOutcome.Rejected("NODE_REJECTED"))
    rejected.repo.inFlight = false
    rejected.worker.tick.unsafeRunSync()
    assertEquals(rejected.repo.record.state, ProvisioningRunState.Failed)
    assertEquals(rejected.repo.record.failureCode, Some("NODE_REJECTED"))
    val uncertain = new Harness(OnboardingPhase.CreateNode, createOutcome = NodeCreateOutcome.Unknown("lost-reply"),
      reconciliation = NodeCreateReconciliation.Confirmed(node()))
    uncertain.repo.inFlight = false
    uncertain.worker.tick.unsafeRunSync()
    assertEquals(uncertain.repo.record.state, ProvisioningRunState.Succeeded)
    assertEquals(uncertain.events.count(_ == "create"), 1)
    assertEquals(uncertain.events.count(_ == "reconcile"), 1)
  }

  test("installation credential is encrypted in storage, removed after verification, and never serialized") {
    val h = new Harness(OnboardingPhase.GetInstallationData)
    h.repo.inFlight = false
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
    assertEquals(h.repo.storedSecret, None)
    val saved = h.repo.savedSecrets.last
    assert(!new String(saved.ciphertext, java.nio.charset.StandardCharsets.UTF_8).contains(secretText))
    assertEquals(cipher.decrypt(saved).secretKey, secretText)
    assert(!h.repo.record.toString.contains(secretText))
    assert(!OnboardingSnapshotCodec.encode(snap).noSpaces.contains(secretText))
  }

  test("a running local container without the remaining evidence cannot produce SUCCEEDED") {
    val h = new Harness(OnboardingPhase.FinalVerify)
    h.repo.inFlight = false
    h.bindingVerified = false
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state, ProvisioningRunState.Failed)
    assertEquals(h.repo.record.failureCode, Some("REMNAWAVE_ONBOARDING_FINAL_VERIFICATION_FAILED"))
    assert(h.events.contains("local-observe"))
    val locallyBad = new Harness(OnboardingPhase.FinalVerify)
    locallyBad.repo.inFlight = false
    locallyBad.local = localGood.copy(stable = false)
    locallyBad.worker.tick.unsafeRunSync()
    assertEquals(locallyBad.repo.record.state, ProvisioningRunState.Failed)
  }

  test("disconnected panel times out as FAILED, while an unavailable panel API is UNKNOWN") {
    val old = now.minusSeconds(20)
    val timeout = new Harness(OnboardingPhase.WaitForPanel, initialPanelNode = node(connected = false), startedAt = old)
    timeout.worker.tick.unsafeRunSync()
    assertEquals(timeout.repo.record.state, ProvisioningRunState.Failed)
    assertEquals(timeout.repo.record.failureCode, Some("REMNAWAVE_NODE_CONNECTION_TIMEOUT"))
    val unavailable = new Harness(OnboardingPhase.WaitForPanel)
    unavailable.failPanelApi = true
    unavailable.worker.tick.unsafeRunSync()
    assertEquals(unavailable.repo.record.state, ProvisioningRunState.Unknown)
  }

  test("sync failure keeps the external node ID and never deletes the external node") {
    val h = new Harness(OnboardingPhase.SyncInventory)
    h.repo.inFlight = false
    h.syncStatus = IntegrationSyncStatus.Failed
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state, ProvisioningRunState.Failed)
    assertEquals(h.repo.record.externalNodeId, Some(nodeId))
    assert(h.repo.record.syncSessionId.nonEmpty)
    assert(!h.events.exists(_.contains("delete")))
  }

  test("final node mismatch and binding conflict each prevent success") {
    val changed = new Harness(OnboardingPhase.FinalVerify)
    changed.inspectedApi = api.copy(sourceCommit = Some("changed"))
    changed.worker.tick.unsafeRunSync()
    assertEquals(changed.repo.record.state, ProvisioningRunState.Unknown)
    assertEquals(changed.repo.record.failureCode, Some("INTEGRATION_API_CONTRACT_CHANGED"))
    val mismatch = new Harness(OnboardingPhase.FinalVerify, initialPanelNode = node(wrongName = true))
    mismatch.repo.inFlight = false
    mismatch.worker.tick.unsafeRunSync()
    assertEquals(mismatch.repo.record.state, ProvisioningRunState.Failed)
    assertEquals(mismatch.repo.record.failureCode, Some("REMNAWAVE_ONBOARDING_FINAL_VERIFICATION_FAILED"))
    val conflict = new Harness(OnboardingPhase.BindResource)
    conflict.repo.inFlight = false
    conflict.bindConflict = true
    conflict.worker.tick.unsafeRunSync()
    assertEquals(conflict.repo.record.state, ProvisioningRunState.Failed)
    assertEquals(conflict.repo.record.failureCode, Some("REMNAWAVE_ONBOARDING_BINDING_CONFLICT"))
    assert(!conflict.events.contains("desired"))
  }
}
