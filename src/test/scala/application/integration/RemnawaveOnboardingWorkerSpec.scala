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
  private val localGood = RemnawaveNodeLocalEvidence(true, true, true, true, true, true, LocalInstallationState.OwnedComplete)
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
        if (OnboardingPhase.all.indexOf(initialPhase) <= OnboardingPhase.all.indexOf(OnboardingPhase.CreateNode)) None else Some(nodeId),
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
    override def start(o: UUID, i: UUID, p: UUID, q: UUID, a: UUID, at: Instant, confirmRecreate: Boolean) = IO.pure(record)
    override def startResult(o: UUID, i: UUID, p: UUID, q: UUID, a: UUID, at: Instant, confirmRecreate: Boolean) =
      IO.pure(OnboardingStartResult(record,false))
    override def find(o: UUID, i: UUID, id: UUID) = IO.pure(Some(record -> phases))
    override def history(o: UUID, i: UUID, limit: Int) = IO.pure(List(record))
    override def createdNodes(o: UUID, r: UUID) = IO.pure(List(record).filter(_.externalNodeId.nonEmpty))
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
    var attachedSyncSessions = List.empty[UUID]
    override def attachSync(r: RemnawaveNodeOnboardingRun, claimToken: UUID, sessionId: UUID, at: Instant) = IO {
      if (!record.claimToken.contains(claimToken)) { staleRejected = true; throw IntegrationError("REMNAWAVE_ONBOARDING_LEASE_LOST", "stale") }
      attachedSyncSessions :+= sessionId
      record = record.copy(syncSessionId = Some(sessionId), updatedAt = at)
    }
    override def saveSecret(r: RemnawaveNodeOnboardingRun, claimToken: UUID, s: IntegrationSecret, at: Instant) = IO {
      storedSecret = Some(s); savedSecrets :+= s.copy(nonce = s.nonce.clone(), ciphertext = s.ciphertext.clone())
    }
    override def secret(r: RemnawaveNodeOnboardingRun) = IO.pure(storedSecret)
    override def deleteSecret(r: RemnawaveNodeOnboardingRun, claimToken: UUID, at: Instant) = IO { storedSecret = None }
    override def replaceFleetMembership(r: RemnawaveNodeOnboardingRun, token: UUID, node: UUID, now: Instant) = IO.unit
    override def cleanupPlans(before: Instant, limit: Int) = IO.pure(0)
  }

  private final class Harness(start: OnboardingPhase, initialPanelNode: ProvisionedNode = node(),
    reconciliation: NodeCreateReconciliation = NodeCreateReconciliation.Confirmed(node()),
    createOutcome: NodeCreateOutcome = NodeCreateOutcome.Created(node()), startedAt: Instant = now) {
    val repo = new MemoryRepo(start, startedAt)
    val events = mutable.ListBuffer.empty[String]
    var panel: ProvisionedNode = initialPanelNode
    var lookup: Option[NodeLookupOutcome] = None
    var candidateNodes: Option[List[ProvisionedNode]] = None
    var deletion: NodeDeleteOutcome = NodeDeleteOutcome.Deleted
    var installationExists = true
    var installationState: Option[LocalInstallationState] = None
    var repairResult = ProvisioningStepResult(Map.empty,None,Some(true))
    var observedOwner: Option[UUID] = None
    var observedImage: Option[String] = None
    var retiredImage: Option[String] = None
    var installedImage: Option[String] = None
    var recon = reconciliation
    var create = createOutcome
    var local = localGood
    var clockNow = now
    var candidateSources = List("185.10.20.30/32")
    var connectivitySources = input.panelCidrs
    var connectOnAdd = true
    var connectivityMode = false
    var connectivityChanges = List.empty[List[String]]
    var observedSource=PanelSourceObservation(PanelSourceObservationStatus.Observed,List("185.10.20.41/32"))
    var connectOnObserved=true
    var addressUpdateOutcome: IntegrationActionRemoteOutcome=IntegrationActionRemoteOutcome.Succeeded
    var retiredFirewallResult = ProvisioningStepResult(Map.empty,None,Some(true))
    var retiredCidrs = List.empty[String]
    var firewallResult = ProvisioningStepResult(Map.empty, None, Some(true))
    var syncStatus: IntegrationSyncStatus = IntegrationSyncStatus.Completed
    var bindingVerified = true
    var bindConflict = false
    var failPanelApi = false
    var inspectedApi = api
    var generatedProfile: Option[NodeProtocolBinding] = None
    var presetConfig = io.circe.Json.obj()
    var protocolFailure: Option[Throwable] = None
    var protocolOutcome: Option[ProtocolProfileOutcome] = None
    var protocolRuntime = ProvisioningStepResult(Map.empty,None,Some(true))
    var retiredProtocol: Option[RemnawaveProtocol] = None
    var protocolPortState: ProtocolPortState = ProtocolPortState.Free
    var enableOutcome: IntegrationActionRemoteOutcome = IntegrationActionRemoteOutcome.Succeeded
    var childStartCount = 0
    var validateCompliant = true
    var childSequence = List.empty[ProvisioningRunState]
    var baselineIdentityProven = true
    /** The durable approved child, exactly as the snapshot pinned it. */
    var childPlan: ProvisioningRun = ProvisioningRun(baselineSnap.baselinePlanId, org, resource, None, None,
      ProvisioningInputSnapshot(1, ProvisioningRunKind.ServerProfileApply, org, resource, "SERVER", "LINUX",
        connection.id, connection.updatedAt, Nil), ProvisioningRunState.Planned, now, now)
    val sessions = mutable.Map.empty[UUID, IntegrationSyncSession]
    /** A synchronization of the same integration that already holds the single RUNNING slot. */
    var slotHolder: Option[IntegrationSyncSession] = None
    var ownedSyncs = 0
    var nodeInInventory = true
    var syncObservesNode = true
    var sessionProgress = List.empty[IntegrationSyncStatus]
    def session(id: UUID, status: IntegrationSyncStatus, recoverAfter: Instant = now.plusSeconds(60)) =
      IntegrationSyncSession(id, org, integrationId, IntegrationSyncTrigger.Manual, None, now, recoverAfter,
        Option.when(status != IntegrationSyncStatus.Running)(now), status,
        Option.when(status == IntegrationSyncStatus.Failed)("INTEGRATION_TIMEOUT"), None, None)
    val remote = new RemnawaveNodeRemote[IO] {
      override def protocolPreflight(c: Connection,resource: UUID,p: RemnawaveProtocol,owner: Option[RemnawaveNodeRemoteSpec],previousProtocol: Option[RemnawaveProtocol] = None)=IO {
        events += "protocol-preflight"
        (if(p.transport=="UDP") List("udp") else List("tcp","udp")).map(t =>
          ProtocolPortObservation(p.port,t,protocolPortState))
      }
      override def configureClientFirewall(c: Connection,s: RemnawaveNodeRemoteSpec,p: RemnawaveProtocol)=IO {
        events += "client-firewall"; assert(generatedProfile.nonEmpty); ProvisioningStepResult(Map.empty,None,Some(true))
      }
      override def verifyProtocol(c: Connection,s: RemnawaveNodeRemoteSpec,p: RemnawaveProtocol)=IO { events += "verify-protocol"; protocolRuntime }
      override def publicNodeAddresses(c: Connection)=IO.pure(List("185.10.20.20"))
      override def observePanelSynSource(c: Connection,p: PanelSynProbeSpec)=IO {
        events += "passive-syn"
        assertEquals(p.runId,repo.record.id)
        assertEquals(p.deadline,repo.phases.find(_.phase==OnboardingPhase.ObservePanelSource).get.startedAt.get.plusSeconds(60))
        observedSource
      }
      override def localInstallationState(c: Connection,s: RemnawaveNodeRemoteSpec) = IO { events += "installation-state"; installationState.getOrElse(if(repo.record.snapshot.recovery.exists(_.reusesNode) && installationExists) LocalInstallationState.OwnedComplete else LocalInstallationState.Absent) }
      override def recoveryPreflight(c: Connection,s: RemnawaveNodeRemoteSpec) = IO { events += "recovery-preflight"; observedOwner=Some(s.onboardingId); ProvisioningStepResult(Map.empty,None,Some(true)) }
      override def repair(c: Connection,s: RemnawaveNodeRemoteSpec,d: NodeInstallationData) = IO { events += "repair"; observedOwner=Some(s.onboardingId); observedImage=Some(s.imageReference); if(repairResult.failureCode.isEmpty) local=localGood; repairResult }
      override def retireFirewall(c: Connection,s: RemnawaveNodeRemoteSpec) = IO {
        events += "retire-firewall"; retiredCidrs=s.panelCidrs
        if(connectivityMode && retiredFirewallResult.failureCode.isEmpty) { Harness.this.connectivitySources=Nil; connectivityChanges :+= Nil }; retiredFirewallResult
      }
      override def clientFirewallRetired(c: Connection,s: RemnawaveNodeRemoteSpec,p: RemnawaveProtocol)=IO.pure(true)
      override def retireClientFirewall(c: Connection,s: RemnawaveNodeRemoteSpec,p: RemnawaveProtocol)=IO {
        events += "retire-client"; retiredProtocol=Some(p); ProvisioningStepResult(Map.empty,None,Some(true))
      }
      override def retireInstallation(c: Connection,s: RemnawaveNodeRemoteSpec) = IO { events += "retire"; retiredImage=Some(s.imageReference); if(repo.record.snapshot.replacement) installationState=Some(LocalInstallationState.Absent); ProvisioningStepResult(Map.empty,None,Some(true)) }
      override def installationPrerequisites(c: Connection) = IO.pure(ProvisioningStepResult(Map.empty, None, Some(true)))
      override def preflight(c: Connection, r: UUID, p: Int) = IO { events += "preflight"; ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def configureFirewall(c: Connection, s: RemnawaveNodeRemoteSpec) = IO {
        events += "firewall"; if(connectivityMode && firewallResult.failureCode.isEmpty) Harness.this.connectivitySources=s.panelCidrs; firewallResult
      }
      override def configureOnboardingFirewall(c: Connection, s: RemnawaveNodeRemoteSpec) = configureFirewall(c,s)
      override def install(c: Connection, s: RemnawaveNodeRemoteSpec, d: NodeInstallationData) = IO {
        events += "install"; installedImage=Some(s.imageReference); assertEquals(d.secretKey, secretText); ProvisioningStepResult(Map.empty, None, Some(true))
      }
      override def start(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "start"; ProvisioningStepResult(Map.empty, None, Some(true)) }
      override def observe(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "local-observe";
        if(connectivityMode) local.copy(firewallMatches=s.panelCidrs.sorted==Harness.this.connectivitySources.sorted) else local }
      override def installationPresent(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "install-present"; installationExists }
      override def firewallPresent(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { events += "firewall-present"; true }
      override def managedPanelCidrs(c: Connection, s: RemnawaveNodeRemoteSpec) = IO.pure(s.panelCidrs)
      override def connectivitySources(c: Connection,s: RemnawaveNodeRemoteSpec,reviewed: List[String]) = IO.pure(Harness.this.connectivitySources)
      override def reconcilePanelSources(c: Connection,s: RemnawaveNodeRemoteSpec,previous: List[String],target: List[String]) = IO {
        events += "connectivity-firewall"; connectivityChanges :+= target; Harness.this.connectivitySources=target
        if(target.contains("185.10.20.41/32")) {
          assert(repo.record.observedPanelSource.contains(observedSource),"Observed candidate must commit before its firewall allow")
          if(connectOnObserved) panel=panel.copy(connected=true,connecting=false)
        }
        if(connectOnAdd) panel=panel.copy(connected=true,connecting=false)
        ProvisioningStepResult(Map.empty,None,Some(true))
      }
    }
    val operations = new RemnawaveOnboardingOperations[IO] {
      override def runtime(r: RemnawaveNodeOnboardingRun) = IO { events += "runtime"; IntegrationRuntimeContext(integrationId, org,
        IntegrationBaseUrl.parse("https://panel.example.test").toOption.get, RemnawaveCredential("token", None)) -> connection }
      override def validate(r: RemnawaveNodeOnboardingRun, requireCompliant: Boolean) = IO {
        events += (if (requireCompliant) "validate-compliant" else "validate");
        if (requireCompliant && !validateCompliant) throw IntegrationError("REMNAWAVE_ONBOARDING_PROFILE_NOT_COMPLIANT", "drift")
      }
      override def ensureBaselineStarted(r: RemnawaveNodeOnboardingRun, token: UUID) = IO {
        events += "baseline-ensure"
        if (!baselineIdentityProven) throw IntegrationError("REMNAWAVE_ONBOARDING_BASELINE_CHANGED", "another child")
        // Only a PLANNED child is started, and always the one the snapshot approved.
        if (childPlan.state == ProvisioningRunState.Planned) {
          childStartCount += 1
          events += "baseline-start"
          childPlan = childPlan.copy(state = ProvisioningRunState.Queued)
        }
        childPlan = childPlan.copy(state = childSequence.headOption.getOrElse(ProvisioningRunState.Succeeded),
          failureCode = childSequence.headOption.collect {
            case ProvisioningRunState.Failed | ProvisioningRunState.Unknown => "CHILD_FAILED" })
        if (childSequence.nonEmpty) childSequence = childSequence.tail
        childPlan
      }
      override def baseline(r: RemnawaveNodeOnboardingRun, id: UUID) = IO { events += "baseline-poll"; childPlan }
      override def claimSync(r: RemnawaveNodeOnboardingRun, token: UUID): IO[OnboardingSyncClaim[IO]] =
        IO(events += "sync-claim") *> (slotHolder match {
        // The single RUNNING slot is held elsewhere; the onboarding records that session, not a new one.
        case Some(holder) => repo.attachSync(r, token, holder.id, now).as(OnboardingSyncClaim.Foreign[IO](holder))
        case None =>
          val claimed = session(uid, IntegrationSyncStatus.Running)
          IO(sessions += claimed.id -> claimed) *> repo.attachSync(r, token, claimed.id, now).as(
            OnboardingSyncClaim.Owned[IO](claimed, IO {
              events += "sync-observe"
              ownedSyncs += 1
              nodeInInventory = nodeInInventory || syncStatus == IntegrationSyncStatus.Completed && syncObservesNode
              val done = claimed.copy(status = syncStatus, finishedAt = Some(now),
                errorCode = Option.when(syncStatus == IntegrationSyncStatus.Failed)("INTEGRATION_TIMEOUT"))
              sessions += done.id -> done
              done
            }))
      })
      override def syncSession(r: RemnawaveNodeOnboardingRun, id: UUID) = IO {
        events += "sync-read"
        // Statuses the stored session reports on successive reads, as a background attempt would.
        sessionProgress.headOption.foreach { status =>
          sessionProgress = sessionProgress.tail
          sessions.get(id).foreach(s => sessions += id -> session(id, status, s.recoverAfterAt))
        }
        sessions.get(id)
      }
      override def inventoryHasNode(r: RemnawaveNodeOnboardingRun) = IO { events += "sync-evidence"; nodeInInventory }
      override def unbindPrevious(r: RemnawaveNodeOnboardingRun, token: UUID)=IO { events += "unbind-previous"; () }
      override def bind(r: RemnawaveNodeOnboardingRun, token: UUID) = IO {
        events += "bind"; if (bindConflict) throw IntegrationError("REMNAWAVE_ONBOARDING_BINDING_CONFLICT", "conflict")
      }
      override def setDesiredState(r: RemnawaveNodeOnboardingRun, token: UUID) = IO { events += "desired" }
      override def verifyInventoryBindingDesired(r: RemnawaveNodeOnboardingRun) = IO { events += "verify-binding"; bindingVerified }
    }
    val provisioning = new NodeProvisioningTransport[IO] {
      override def ensureProtocolProfile(c: IntegrationRuntimeContext,name: String,tag: String,config: io.circe.Json,
        reviewed: NodeApiCompatibility,fresh: Boolean)=IO {
        events += "create-profile"; protocolFailure.foreach(throw _); assertEquals(config,presetConfig)
        protocolOutcome.getOrElse(ProtocolProfileOutcome.Confirmed(generatedProfile.get))
      }
      override def updateNodeAddress(c: IntegrationRuntimeContext,id: UUID,old: NodeCreateIntent,desired: NodeCreateIntent,reviewed: NodeApiCompatibility)=IO {
        events += "update-address"; assertEquals(id,nodeId); assertEquals(panel.address,old.address)
        if(addressUpdateOutcome==IntegrationActionRemoteOutcome.Succeeded) panel=panel.copy(address=desired.address)
        addressUpdateOutcome
      }
      override def inspect(c: IntegrationRuntimeContext) = IO.pure(inspectedApi)
      override def findNodes(c: IntegrationRuntimeContext) = IO.pure(candidateNodes.getOrElse(List(panel)))
      override def getNode(c: IntegrationRuntimeContext, id: UUID) = IO {
        events += "get-node"; if (failPanelApi) throw IntegrationError("INTEGRATION_REMOTE_UNAVAILABLE", "offline") else panel
      }
      override def lookupNode(c: IntegrationRuntimeContext,id: UUID) = IO { events += "lookup"; if(repo.record.snapshot.replacement && repo.record.externalNodeId.contains(id)) NodeLookupOutcome.Found(panel) else lookup.getOrElse(if(failPanelApi) NodeLookupOutcome.Unknown("INTEGRATION_REMOTE_UNAVAILABLE") else NodeLookupOutcome.Found(panel)) }
      override def deleteNode(c: IntegrationRuntimeContext,id: UUID,reviewed: NodeApiCompatibility) = IO { events += "delete"; if(deletion==NodeDeleteOutcome.Deleted) { lookup=Some(NodeLookupOutcome.ConfirmedNotFound); candidateNodes=Some(Nil) }; deletion }
      override def createNode(c: IntegrationRuntimeContext, i: NodeCreateIntent, reviewed: NodeApiCompatibility) = IO { events += "create"
        if(generatedProfile.nonEmpty) { assertEquals(repo.record.protocolBinding,generatedProfile); assertEquals(i.configProfileId,generatedProfile.get.profileId) }
        create match { case NodeCreateOutcome.Created(n) if OnboardingRecovery.matches(n,i,None) => panel=n; candidateNodes=Some(List(n)); case _ => () }
        create }
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
          generatedProfile.fold(input.configProfileId)(_.profileId).toString, "profile", RemnawaveConfigProfileSummary(0, now, now, Nil,
            List(RemnawaveInboundSummary(generatedProfile.fold(inbound)(_.inboundIds.head).toString, "in", "VLESS", None, None, None)),
            generatedProfile.map(_.configSha256)))), Set(IntegrationObjectType.ConfigProfile))
      }
      override def executeAction(c: IntegrationRuntimeContext, externalId: String, action: IntegrationActionCode) =
        IO { events += "enable"; if(enableOutcome==IntegrationActionRemoteOutcome.Succeeded) panel=panel.copy(disabled=false); enableOutcome }
      override def nodeProvisioning = Some(provisioning)
      override def configProfiles = Some(new IntegrationConfigProfileTransport[IO] {
        def fetchConfigProfile(c: IntegrationRuntimeContext,id: String)=IO.pure(IntegrationConfigProfileDocument(id,presetConfig,None))
        def updateConfigProfile(c: IntegrationRuntimeContext,id: String,config: io.circe.Json,sha: String)=
          IO.raiseError[IntegrationActionRemoteOutcome](new AssertionError("Existing profiles must never be patched by onboarding"))
      })
    }
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]) = program }
    val auditPair: (RecordingAuditEventRepository, AuditRecorder[IO]) = TestAuditRecorder.recording
    val settings = ProvisioningSettings(enabled = true, pollInterval = 1.second, batchSize = 1, maxConcurrency = 1,
      leaseDuration = 60.seconds, stepTimeout = 30.seconds)
    val worker = new RemnawaveOnboardingWorker[IO](repo, operations,
      new IntegrationProviderRegistry[IO](List(provider)), remote, cipher, runner, auditPair._2, settings, NoOpLogger[IO],
      RemnawaveOnboardingSettings(1.second, 6.seconds, 6.seconds, 6.seconds), clock = IO {
        val at=clockNow; if(connectivityMode && !connectOnAdd) clockNow=clockNow.plusSeconds(10); at
      },
      panelSources=new RemnawavePanelSourceResolver[IO] {
        def resolve(endpoint: IntegrationBaseUrl,mode: PanelSourceMode,manual: List[String],managed: Option[String]) = IO.pure(
          PanelSourceEvidence(mode,if(mode==PanelSourceMode.Auto) candidateSources else manual,
            if(mode==PanelSourceMode.Auto) "DNS_BASE_URL" else "MANUAL",if(mode==PanelSourceMode.Auto) "AUTO_CANDIDATE" else "MANUAL",
            PanelSourceEvidence.fingerprint(endpoint)))
      })
  }

  private def replacementHarness(phase: OnboardingPhase): Harness = {
    val h=new Harness(phase)
    val protocol=RemnawaveProtocol.Shadowsocks(443,"chacha20-ietf-poly1305")
    val config=RemnawaveProtocolPreset.render(protocol,RemnawaveProtocol.inboundTag(org,resource,protocol))
    val receipt=NodeProtocolBinding(uid,List(uid),domain.configuration.CanonicalJson.sha256(config))
    h.generatedProfile=Some(receipt); h.presetConfig=config
    val old=PreviousNodeInstallation("previous-node",input.address,2222,Some(RemnawaveProtocol.Shadowsocks(1234,"aes-256-gcm")),None,Some(uid))
    val proof=OnboardingRecovery(uid,Some(UUID.fromString("58065b61-0cd1-4312-92c8-e443cb739172")),uid,uid,"CONFIRMED_NOT_FOUND",
      "RECREATE_WITH_NEW_CONFIG",Some(input.panelCidrs),Some(snap.imageReference),Some(LocalInstallationObservation.fromState(LocalInstallationState.OwnedComplete)),
      previousNodeAddress=Some(old.address),previousInstallation=Some(old))
    val evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.test").toOption.get))
    h.repo.record=h.repo.record.copy(externalNodeId=None,snapshot=snap.copy(input=input.copy(configProfileId=OnboardingInput.GeneratedProfileId,
      activeInboundIds=Nil,protocol=Some(protocol)),recovery=Some(proof),lifecycleVersion=7,panelSource=Some(evidence)))
    h.installationState=Some(LocalInstallationState.OwnedComplete); h.lookup=Some(NodeLookupOutcome.ConfirmedNotFound);h.candidateNodes=Some(Nil)
    h.panel=node().copy(configProfileId=Some(receipt.profileId),activeInboundIds=receipt.inboundIds)
    h.create=NodeCreateOutcome.Created(h.panel);h.repo.inFlight=false
    h
  }
  test("replacement retires previous protocol with old parameters before one fresh create and preserves new receipt") {
    val h=replacementHarness(OnboardingPhase.ConfirmPreviousAbsent)
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded,s"failure=${h.repo.record.failureCode} phase=${h.repo.record.phase} events=${h.events}")
    assertEquals(h.retiredProtocol,Some(RemnawaveProtocol.Shadowsocks(1234,"aes-256-gcm")))
    assert(h.events.indexOf("unbind-previous")<h.events.indexOf("retire-client"))
    assert(h.events.indexOf("retire-client")<h.events.indexOf("retire-firewall"))
    assert(h.events.indexOf("retire")<h.events.indexOf("create-profile"))
    assertEquals(h.events.count(_=="create"),1)
    assert(!h.events.contains("delete"))
    assert(h.repo.record.externalNodeId!=h.repo.record.snapshot.recovery.flatMap(_.previousExternalNodeId))
  }
  test("unexpected pre-write protocol preparation failures remain INTERNAL_ERROR with fresh versus recovered uncertainty") {
    for(fresh <- List(true,false)) {
      val h=replacementHarness(OnboardingPhase.CreateProtocolProfile)
      h.repo.inFlight= !fresh
      val original=new IllegalStateException("private-api-token private-registration-key")
      val failure=new ProtocolProfilePreparationFailed(original)
      assertEquals(failure.getCause,null)
      assert(!failure.getMessage.contains("private"))
      h.protocolFailure=Some(failure)
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.failureCode,Some("REMNAWAVE_ONBOARDING_INTERNAL_ERROR"))
      assertEquals(h.repo.record.state,if(fresh) ProvisioningRunState.Failed else ProvisioningRunState.Unknown)
      assert(!h.events.contains("create"))
      assert(!OnboardingJson.run(h.repo.record).noSpaces.contains("private-api-token"))
    }
  }
  test("replacement connectivity discovery uses the new initial sources after retiring the old policy") {
    val h=replacementHarness(OnboardingPhase.ConfirmPreviousAbsent)
    val initial=h.candidateSources
    val evidence=PanelSourceEvidence(PanelSourceMode.Auto,initial,"DNS_BASE_URL","AUTO_CANDIDATE",
      PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.test").toOption.get))
    val snapshot=h.repo.record.snapshot
    h.repo.record=h.repo.record.copy(snapshot=snapshot.copy(input=snapshot.input.copy(panelSourceMode=PanelSourceMode.Auto,panelCidrs=initial),panelSource=Some(evidence)))
    h.connectivityMode=true;h.connectOnAdd=false;h.connectOnObserved=true;h.connectivitySources=input.panelCidrs
    h.panel=h.panel.copy(connected=false,connecting=true);h.create=NodeCreateOutcome.Created(h.panel)
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded,s"phase=${h.repo.record.phase} failure=${h.repo.record.failureCode}")
    assertEquals(h.connectivitySources,h.observedSource.sources)
    assertEquals(h.repo.record.connectivityCompletion,Some(PanelConnectivityCompletion.Promoted))
  }
  test("post-retirement foreign port and reappearing Panel UUID block every new create") {
    val port=replacementHarness(OnboardingPhase.VerifyPreviousRetired)
    port.installationState=Some(LocalInstallationState.Absent);port.protocolPortState=ProtocolPortState.ForeignListener
    port.worker.tick.unsafeRunSync()
    assertEquals(port.repo.record.failureCode,Some("REMNAWAVE_PROTOCOL_PORT_OCCUPIED"))
    assert(!port.events.exists(Set("create-profile","create","install","delete")))
    val present=replacementHarness(OnboardingPhase.ConfirmPreviousAbsent)
    present.lookup=Some(NodeLookupOutcome.Found(present.panel))
    present.worker.tick.unsafeRunSync()
    assert(!present.events.exists(Set("retire","retire-client","retire-firewall","unbind-previous","create")))
    val contradictory=replacementHarness(OnboardingPhase.ConfirmPreviousAbsent)
    val previousId=contradictory.repo.record.snapshot.recovery.flatMap(_.previousExternalNodeId).get
    contradictory.candidateNodes=Some(List(node(id=previousId,wrongName=true).copy(address="203.0.113.9",correlationTags=Nil)))
    contradictory.worker.tick.unsafeRunSync()
    assertEquals(contradictory.repo.record.failureCode,Some("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW"))
    assert(!contradictory.events.exists(Set("retire","retire-client","retire-firewall","unbind-previous","create")))
  }
  test("foreign listener appearing after preview blocks before any protocol or provider mutation") {
    for(state <- List(ProtocolPortState.ForeignListener,ProtocolPortState.ObservationUnknown)) {
      val h=new Harness(OnboardingPhase.ProtocolPreflight)
      val protocol=RemnawaveProtocol.Shadowsocks(443,"aes-256-gcm")
      val evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.test").toOption.get))
      h.repo.record=h.repo.record.copy(snapshot=snap.copy(input=input.copy(configProfileId=OnboardingInput.GeneratedProfileId,
        activeInboundIds=Nil,protocol=Some(protocol)),lifecycleVersion=7,panelSource=Some(evidence)))
      h.protocolPortState=state; h.repo.inFlight=false
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.failureCode,ProtocolPortObservation(443,"tcp",state).blocker)
      assert(!h.events.exists(Set("create-profile","create","client-firewall","install","sync","bind","desired")))
    }
  }
  test("generated protocol persists its profile receipt before Node creation and cannot succeed without its runtime probe") {
    for(healthy <- List(true,false); version <- List(6,7)) {
      val h=new Harness(OnboardingPhase.CreateProtocolProfile)
      val protocol=RemnawaveProtocol.Shadowsocks(443,"chacha20-ietf-poly1305")
      val config=RemnawaveProtocolPreset.render(protocol,RemnawaveProtocol.inboundTag(org,resource,protocol))
      val receipt=NodeProtocolBinding(uid,List(uid),domain.configuration.CanonicalJson.sha256(config))
      h.generatedProfile=Some(receipt); h.presetConfig=config
      val evidence=PanelSourceEvidence(PanelSourceMode.Manual,input.panelCidrs,"MANUAL","MANUAL",
        PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.test").toOption.get))
      h.repo.record=h.repo.record.copy(snapshot=snap.copy(input=input.copy(configProfileId=OnboardingInput.GeneratedProfileId,
        activeInboundIds=Nil,protocol=Some(protocol)),lifecycleVersion=version,panelSource=Some(evidence)))
      h.repo.inFlight=false
      h.panel=node().copy(configProfileId=Some(receipt.profileId),activeInboundIds=receipt.inboundIds)
      h.create=NodeCreateOutcome.Created(h.panel)
      if(!healthy) h.protocolRuntime=ProvisioningStepResult(Map.empty,Some("REMNAWAVE_PROTOCOL_LISTENER_MISSING"),Some(false))
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.protocolBinding,Some(receipt))
      assert(h.events.indexOf("create-profile")<h.events.indexOf("create"))
      assert(h.events.indexOf("client-firewall")<h.events.indexOf("install"))
      assertEquals(h.repo.record.state,if(healthy) ProvisioningRunState.Succeeded else ProvisioningRunState.Failed)
      assertEquals(h.repo.completed.contains(OnboardingPhase.FinalVerify),healthy)
      assertEquals(h.events.count(_=="create-profile"),1)
      if(version==7 && !healthy) {
        assert(!h.repo.completed.contains(OnboardingPhase.SyncInventory))
        assert(!h.repo.completed.contains(OnboardingPhase.BindResource))
        assert(!h.repo.completed.contains(OnboardingPhase.SetDesiredState))
      }
    }
  }

  private def connectivityHarness(phase: OnboardingPhase = OnboardingPhase.Validate): Harness = {
    val h=new Harness(phase,initialPanelNode=node(connected=false),startedAt=now.minusSeconds(20))
    val previous=List("2.27.26.18/32")
    val source=PanelSourceEvidence(PanelSourceMode.Auto,h.candidateSources,"DNS_BASE_URL","AUTO_CANDIDATE",
      PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.test").toOption.get))
    val proof=OnboardingRecovery(uid,Some(nodeId),snap.correlationId,uid,"PRESENT_UNHEALTHY","REPAIR_PANEL_CONNECTIVITY",
      Some(previous),Some(RemnawaveNodeReleaseCatalog.managedReferences.head),Some(LocalInstallationObservation.fromState(LocalInstallationState.OwnedComplete)),true)
    h.repo.record=h.repo.record.copy(externalNodeId=Some(nodeId),snapshot=snap.copy(
      input=input.copy(panelCidrs=h.candidateSources,panelSourceMode=PanelSourceMode.Auto),recovery=Some(proof),lifecycleVersion=4,panelSource=Some(source)))
    h.connectivityMode=true; h.connectivitySources=previous; h.repo.inFlight=false; h
  }

  test("standard onboarding ignores a missing resource hostname and selects authenticated public IP without DNS") {
    val h=new Harness(OnboardingPhase.Validate)
    val dns=new RemnawaveNodeAddressResolver[IO] {
      def verifyDomain(domain: String,ips: List[String])=IO.raiseError[NodeAddressEvidence](new AssertionError("IP mode must not query resource DNS"))
    }
    val evidence=OnboardingNodeAddresses.resolve(input.copy(address="no-dns.example.test",nodeAddressMode=NodeAddressMode.PublicIp),connection,h.remote,dns).unsafeRunSync()
    assertEquals(evidence.address,"185.10.20.20")
    assertEquals(evidence.mode,NodeAddressMode.PublicIp)
  }
  test("V5 observes actual source after DNS timeout, commits exact candidate before allow and promotes only connected enabled UUID") {
    val h=connectivityHarness(); h.connectOnAdd=false; h.clockNow=now.plusSeconds(100)
    h.repo.record=h.repo.record.copy(snapshot=h.repo.record.snapshot.copy(lifecycleVersion=5))
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded)
    assertEquals(h.events.count(_=="passive-syn"),1)
    assertEquals(h.connectivityChanges.last,List("185.10.20.41/32"))
    assertEquals(h.repo.record.connectivityFinding.map(_.sourceEvidence),Some("AUTO_OBSERVED"))
    assertEquals(h.repo.record.connectivityCompletion,Some(PanelConnectivityCompletion.Promoted))
    assert(!h.events.exists(Set("create","delete","install","repair","start")))
    val detail=OnboardingJson.detail(h.repo.record,h.repo.phases)
    val wait=detail.hcursor.get[List[io.circe.Json]]("phases").toOption.get.find(_.hcursor.get[String]("phase").contains("WAIT_FOR_PANEL")).get
    assertEquals(wait.hcursor.get[String]("outcome"),Right("NOT_CONNECTED"))
  }
  test("V5 unavailable, ambiguous and silent passive windows compensate without allowing a source or requesting a broad rule") {
    for(status <- List(PanelSourceObservationStatus.Unavailable,PanelSourceObservationStatus.Ambiguous,PanelSourceObservationStatus.NoTraffic)) {
      val h=connectivityHarness(); h.connectOnAdd=false; h.clockNow=now.plusSeconds(100)
      h.observedSource=PanelSourceObservation(status,Nil)
      h.repo.record=h.repo.record.copy(snapshot=h.repo.record.snapshot.copy(lifecycleVersion=5))
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.state,ProvisioningRunState.Failed)
      assertEquals(h.connectivityChanges.last,List("2.27.26.18/32"))
      assertEquals(h.repo.record.connectivityCompletion,Some(PanelConnectivityCompletion.RolledBack))
      assert(!h.connectivityChanges.flatten.contains("185.10.20.41/32"))
      val phases=OnboardingJson.detail(h.repo.record,h.repo.phases).hcursor.get[List[io.circe.Json]]("phases").toOption.get
      assertEquals(phases.find(_.hcursor.get[String]("phase").contains("FINALIZE_PANEL_SOURCES")).get.hcursor.get[String]("outcome"),Right("ROLLED_BACK"))
    }
  }
  test("V5 failed observed candidate retains previous sources; a healthy DNS candidate avoids passive instrumentation") {
    val failed=connectivityHarness(); failed.connectOnAdd=false; failed.connectOnObserved=false; failed.clockNow=now.plusSeconds(100)
    failed.repo.record=failed.repo.record.copy(snapshot=failed.repo.record.snapshot.copy(lifecycleVersion=5))
    failed.worker.tick.unsafeRunSync()
    assertEquals(failed.repo.record.state,ProvisioningRunState.Failed)
    assertEquals(failed.connectivityChanges.last,List("2.27.26.18/32"))
    val good=connectivityHarness(); good.repo.record=good.repo.record.copy(snapshot=good.repo.record.snapshot.copy(lifecycleVersion=5))
    good.worker.tick.unsafeRunSync()
    assertEquals(good.repo.record.state,ProvisioningRunState.Succeeded)
    assert(!good.events.contains("passive-syn"))
    assertEquals(good.repo.record.observedPanelSource.map(_.status),Some(PanelSourceObservationStatus.NotRequired))
  }
  test("V5 reviewed address update preserves UUID; an in-flight old address cannot repeat PATCH") {
    for(fresh <- List(true,false)) {
      val h=connectivityHarness(OnboardingPhase.UpdateNodeAddress)
      val snapshot=h.repo.record.snapshot
      val address=NodeAddressEvidence(NodeAddressMode.PublicIp,"185.10.20.20",List("185.10.20.20"))
      h.repo.record=h.repo.record.copy(snapshot=snapshot.copy(lifecycleVersion=5,input=snapshot.input.copy(address=address.address,nodeAddressMode=address.mode),
        nodeAddress=Some(address),recovery=snapshot.recovery.map(_.copy(previousNodeAddress=Some(h.panel.address)))))
      h.repo.inFlight = !fresh
      h.worker.tick.unsafeRunSync()
      assertEquals(h.events.count(_=="update-address"),if(fresh) 1 else 0)
      assertEquals(h.repo.record.state,if(fresh) ProvisioningRunState.Succeeded else ProvisioningRunState.Unknown)
      assertEquals(h.repo.record.externalNodeId,Some(nodeId))
      assert(!h.events.exists(Set("create","delete","install","repair","start")))
    }
  }
  test("V4 repairs the existing healthy UUID: add exact source, confirm Panel, then remove old source; no create/install") {
    val h=connectivityHarness(); h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded)
    assertEquals(h.repo.record.externalNodeId,Some(nodeId))
    assertEquals(h.connectivityChanges,List(List("185.10.20.30/32","2.27.26.18/32"),List("185.10.20.30/32")))
    assertEquals(h.repo.record.connectivityFinding.map(_.connected),Some(true))
    assert(!h.events.exists(Set("create","delete","install","repair","start")))
    assertEquals(h.repo.completed,OnboardingPhase.forSnapshot(h.repo.record.snapshot))
  }
  test("unconfirmed new sources are compensated durably and the previous rule is retained") {
    val h=connectivityHarness(); h.connectOnAdd=false; h.clockNow=now.plusSeconds(100)
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Failed)
    assertEquals(h.repo.record.failureCode,Some("REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT"))
    assertEquals(h.connectivityChanges.last,List("2.27.26.18/32"))
    assertEquals(h.repo.record.connectivityFinding.map(_.connected),Some(false))
    assert(!h.events.exists(Set("create","delete","install","repair","start")))
  }
  test("changed AUTO evidence blocks before connectivity mutation") {
    val h=connectivityHarness(); h.candidateSources=List("185.10.20.31/32")
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.failureCode,Some("REMNAWAVE_ONBOARDING_SOURCE_CHANGED"))
    assertEquals(h.connectivityChanges,Nil)
  }
  test("crash after adding candidate observes the reviewed union and reuses the same node") {
    val h=connectivityHarness(OnboardingPhase.AddPanelSources)
    h.repo.inFlight=true; h.connectivitySources=List("185.10.20.30/32","2.27.26.18/32")
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded)
    assertEquals(h.repo.record.externalNodeId,Some(nodeId))
    assert(!h.events.exists(Set("create","delete","install")))
  }
  test("new V4 onboarding retires unconfirmed candidate rules while retaining the created UUID and installation") {
    val h=new Harness(OnboardingPhase.Validate)
    val evidence=PanelSourceEvidence(PanelSourceMode.Auto,h.candidateSources,"DNS_BASE_URL","AUTO_CANDIDATE",
      PanelSourceEvidence.fingerprint(IntegrationBaseUrl.parse("https://panel.example.test").toOption.get))
    h.repo.record=h.repo.record.copy(snapshot=snap.copy(input=input.copy(panelCidrs=evidence.sources,panelSourceMode=PanelSourceMode.Auto),
      lifecycleVersion=4,panelSource=Some(evidence)))
    h.repo.inFlight=false; h.connectivityMode=true; h.connectOnAdd=false; h.create=NodeCreateOutcome.Created(node(connected=false))
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.failureCode,Some("REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT"))
    assertEquals(h.repo.record.externalNodeId,Some(nodeId))
    assertEquals(h.connectivitySources,Nil)
    assert(h.events.contains("retire-firewall")); assert(!h.events.contains("retire")); assert(!h.events.contains("delete"))
  }
  test("lost Panel observation rolls back added candidates without claiming a disconnected Panel as known") {
    val h=connectivityHarness(OnboardingPhase.WaitForPanel)
    h.connectivitySources=List("185.10.20.30/32","2.27.26.18/32"); h.failPanelApi=true; h.connectOnAdd=false
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Unknown)
    assertEquals(h.repo.record.failureCode,Some("REMNAWAVE_PANEL_OBSERVATION_UNKNOWN"))
    assertEquals(h.connectivitySources,List("2.27.26.18/32"))
    assertEquals(h.repo.record.connectivityFinding,None)
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
    assertEquals(waited.repo.record.baselineRunId, Some(baselineSnap.baselinePlanId))
    assert(waited.events.count(_ == "baseline-ensure") >= 2)

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

  test("a baseline attached before a crash is started from PLANNED instead of being polled to timeout") {
    val h = new Harness(OnboardingPhase.PrepareServer)
    // The crash window: attachBaseline committed, so the parent already names the child, but the
    // child itself was never started.
    h.repo.record = h.repo.record.copy(snapshot = baselineSnap, baselineRunId = Some(baselineSnap.baselinePlanId))
    h.childPlan = h.childPlan.copy(state = ProvisioningRunState.Planned)
    h.childSequence = List(ProvisioningRunState.Queued, ProvisioningRunState.Succeeded)
    h.worker.tick.unsafeRunSync()
    assertEquals(h.childStartCount, 1)
    assertEquals(h.childPlan.id, baselineSnap.baselinePlanId)
    assertEquals(h.repo.record.baselineRunId, Some(baselineSnap.baselinePlanId))
    assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
    assertEquals(h.repo.completed, OnboardingPhase.all.drop(1))
  }

  test("the baseline request ID is derived from the onboarding, so repeating the start is one request") {
    val id = uid
    assertEquals(RemnawaveNodeOnboardingRun.baselineRequestId(id), RemnawaveNodeOnboardingRun.baselineRequestId(id))
    assertNotEquals(RemnawaveNodeOnboardingRun.baselineRequestId(id), RemnawaveNodeOnboardingRun.baselineRequestId(uid))
  }

  test("a QUEUED or RUNNING baseline child is waited on and never started a second time") {
    List(ProvisioningRunState.Queued, ProvisioningRunState.Running).foreach { inFlightState =>
      val h = new Harness(OnboardingPhase.PrepareServer)
      h.repo.record = h.repo.record.copy(snapshot = baselineSnap, baselineRunId = Some(baselineSnap.baselinePlanId))
      h.childPlan = h.childPlan.copy(state = inFlightState)
      h.childSequence = List(ProvisioningRunState.Running, ProvisioningRunState.Succeeded)
      h.worker.tick.unsafeRunSync()
      assertEquals(h.childStartCount, 0)
      assert(!h.events.contains("baseline-start"))
      assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
    }
  }

  test("a terminal baseline child is read as it stands and never restarted") {
    List(ProvisioningRunState.Failed -> ProvisioningRunState.Failed,
      ProvisioningRunState.Unknown -> ProvisioningRunState.Unknown,
      ProvisioningRunState.Succeeded -> ProvisioningRunState.Succeeded).foreach { case (childState, expected) =>
      val h = new Harness(OnboardingPhase.PrepareServer)
      h.repo.record = h.repo.record.copy(snapshot = baselineSnap, baselineRunId = Some(baselineSnap.baselinePlanId))
      h.childPlan = h.childPlan.copy(state = childState,
        failureCode = Option.when(childState != ProvisioningRunState.Succeeded)("CHILD_FAILED"))
      h.childSequence = List(childState)
      h.worker.tick.unsafeRunSync()
      assertEquals(h.childStartCount, 0)
      assertEquals(h.repo.record.state, expected)
    }
  }

  test("a PLANNED child whose identity does not match the approved plan is not started") {
    val h = new Harness(OnboardingPhase.PrepareServer)
    h.repo.record = h.repo.record.copy(snapshot = baselineSnap, baselineRunId = Some(baselineSnap.baselinePlanId))
    h.childPlan = h.childPlan.copy(state = ProvisioningRunState.Planned)
    h.baselineIdentityProven = false
    h.worker.tick.unsafeRunSync()
    assertEquals(h.childStartCount, 0)
    assert(!h.events.contains("baseline-start"))
    assertEquals(h.repo.record.state, ProvisioningRunState.Unknown)
    assertEquals(h.repo.record.failureCode, Some("REMNAWAVE_ONBOARDING_BASELINE_CHANGED"))
  }

  test("a synchronization already running for the integration is awaited, not treated as a failure") {
    val h = new Harness(OnboardingPhase.SyncInventory)
    h.repo.inFlight = false
    val holder = h.session(uid, IntegrationSyncStatus.Running)
    h.slotHolder = Some(holder)
    h.sessions += holder.id -> holder
    // The observation under way elsewhere completes before the onboarding looks again.
    h.sessionProgress = List(IntegrationSyncStatus.Completed)
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.syncSessionId, Some(holder.id))
    assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
    assertEquals(h.ownedSyncs, 0)
    assertEquals(h.repo.attachedSyncSessions, List(holder.id))
    assert(h.events.contains("bind"))
    assert(!h.repo.record.failureCode.contains("INTEGRATION_SYNC_ALREADY_RUNNING"))
  }

  test("waiting for a synchronization that never finishes is bounded and starts no second one") {
    val h = new Harness(OnboardingPhase.SyncInventory, startedAt = now.minusSeconds(20))
    h.repo.inFlight = false
    val holder = h.session(uid, IntegrationSyncStatus.Running)
    h.slotHolder = Some(holder)
    h.sessions += holder.id -> holder
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.syncSessionId, Some(holder.id))
    assertEquals(h.repo.record.state, ProvisioningRunState.Unknown)
    assertEquals(h.repo.record.failureCode, Some("REMNAWAVE_ONBOARDING_SYNC_TIMEOUT"))
    assertEquals(h.ownedSyncs, 0)
    assert(!h.events.contains("bind"))
  }

  test("a recovered worker reads the stored session and starts no second synchronization") {
    val h = new Harness(OnboardingPhase.SyncInventory)
    val stored = h.session(uid, IntegrationSyncStatus.Running)
    h.sessions += stored.id -> stored
    h.repo.record = h.repo.record.copy(syncSessionId = Some(stored.id))
    h.sessionProgress = List(IntegrationSyncStatus.Running, IntegrationSyncStatus.Completed)
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
    assertEquals(h.repo.record.syncSessionId, Some(stored.id))
    assertEquals(h.ownedSyncs, 0)
    assertEquals(h.repo.attachedSyncSessions, Nil)
    assertEquals(h.sessions.size, 1)
    assert(h.events.count(_ == "sync-read") >= 2)
  }

  test("a stored session that failed ends the onboarding without deleting the external node") {
    val h = new Harness(OnboardingPhase.SyncInventory)
    val stored = h.session(uid, IntegrationSyncStatus.Failed)
    h.sessions += stored.id -> stored
    h.repo.record = h.repo.record.copy(syncSessionId = Some(stored.id))
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state, ProvisioningRunState.Failed)
    assertEquals(h.repo.record.failureCode, Some("INTEGRATION_TIMEOUT"))
    assertEquals(h.repo.record.externalNodeId, Some(nodeId))
    assertEquals(h.repo.record.syncSessionId, Some(stored.id))
    assertEquals(h.ownedSyncs, 0)
    assert(!h.events.exists(_.contains("delete")))
  }

  test("a completed session without the new node is not evidence and a fresh one is observed") {
    val h = new Harness(OnboardingPhase.SyncInventory)
    // Somebody else's snapshot finished before this node existed.
    val older = h.session(uid, IntegrationSyncStatus.Completed)
    h.sessions += older.id -> older
    h.repo.record = h.repo.record.copy(syncSessionId = Some(older.id))
    h.nodeInInventory = false
    h.syncObservesNode = true
    h.worker.tick.unsafeRunSync()
    assertEquals(h.ownedSyncs, 1)
    assert(!h.repo.record.syncSessionId.contains(older.id))
    assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
    assertEquals(h.nodeInInventory, true)

    val blind = new Harness(OnboardingPhase.SyncInventory, startedAt = now.minusSeconds(20))
    val stale = blind.session(uid, IntegrationSyncStatus.Completed)
    blind.sessions += stale.id -> stale
    blind.repo.record = blind.repo.record.copy(syncSessionId = Some(stale.id))
    blind.nodeInInventory = false
    blind.syncObservesNode = false
    blind.worker.tick.unsafeRunSync()
    // Binding is never reached without inventory evidence of the node.
    assert(!blind.events.contains("bind"))
    assertEquals(blind.repo.record.state, ProvisioningRunState.Unknown)
    assertEquals(blind.repo.record.failureCode, Some("REMNAWAVE_ONBOARDING_SYNC_TIMEOUT"))
  }

  test("an abandoned running session is retired by the shared recovery instead of waiting forever") {
    val h = new Harness(OnboardingPhase.SyncInventory)
    // Its own deadline has passed, so this attempt died with the process that started it.
    val abandoned = h.session(uid, IntegrationSyncStatus.Running, recoverAfter = now.minusSeconds(1))
    h.sessions += abandoned.id -> abandoned
    h.repo.record = h.repo.record.copy(syncSessionId = Some(abandoned.id))
    h.worker.tick.unsafeRunSync()
    assertEquals(h.ownedSyncs, 1)
    assert(!h.repo.record.syncSessionId.contains(abandoned.id))
    assertEquals(h.repo.record.state, ProvisioningRunState.Succeeded)
  }

  test("a session the storage no longer holds leaves the phase UNKNOWN without a new observation") {
    val h = new Harness(OnboardingPhase.SyncInventory)
    h.repo.record = h.repo.record.copy(syncSessionId = Some(uid))
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state, ProvisioningRunState.Unknown)
    assertEquals(h.repo.record.failureCode, Some("REMNAWAVE_ONBOARDING_SYNC_UNKNOWN"))
    assertEquals(h.ownedSyncs, 0)
  }

  test("a new reviewed run after a known firewall failure reconciles the existing node without another create") {
    val failed = new Harness(OnboardingPhase.Validate)
    failed.repo.inFlight = false
    failed.firewallResult = ProvisioningStepResult(Map.empty,Some("FIREWALL_RULE_UNSUPPORTED"),None)
    failed.worker.tick.unsafeRunSync()
    val terminal = failed.repo.record
    assertEquals(terminal.state,ProvisioningRunState.Failed)
    assertEquals(terminal.phase,OnboardingPhase.ConfigureFirewall)
    assertEquals(terminal.externalNodeId,Some(nodeId))
    assert(failed.repo.completed.contains(OnboardingPhase.CreateNode))
    assert(failed.repo.completed.contains(OnboardingPhase.GetInstallationData))
    assertEquals(failed.repo.storedSecret,None)
    val candidate = RemnawaveNodeOnboardingRun.recoveryCandidate(List(terminal),integrationId,input,snap.imageReference,snap.connectionId).toOption.flatten.get
    val recovery = new Harness(OnboardingPhase.Validate)
    recovery.repo.inFlight = false
    recovery.repo.record = recovery.repo.record.copy(externalNodeId=candidate.externalNodeId,
      snapshot=recovery.repo.record.snapshot.copy(correlationId=candidate.snapshot.correlationId,
        recovery=Some(OnboardingRecovery(terminal.id,terminal.externalNodeId,terminal.snapshot.correlationId,terminal.id,"PRESENT_UNHEALTHY","RECOVER"))))
    recovery.worker.tick.unsafeRunSync()
    assertEquals(recovery.repo.record.state,ProvisioningRunState.Succeeded)
    assertEquals(recovery.repo.record.externalNodeId,terminal.externalNodeId)
    assertEquals(recovery.events.count(_ == "create"),0)
    assertEquals(recovery.events.count(_ == "reconcile"),0)
    assert(recovery.events.contains("lookup"))
    assertEquals(recovery.observedOwner,Some(terminal.id))
    failed.worker.tick.unsafeRunSync()
    assertEquals(failed.repo.record,terminal)
    assertEquals(failed.events.count(_ == "create"),1)
  }

  test("a reviewed existing-node recovery never creates when reconciliation is missing or mismatched") {
    val h = new Harness(OnboardingPhase.CreateNode,reconciliation=NodeCreateReconciliation.Confirmed(node(wrongName=true)))
    h.repo.inFlight = false
    h.repo.record = h.repo.record.copy(externalNodeId=Some(nodeId))
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Unknown)
    assertEquals(h.events.count(_ == "create"),0)
    assertEquals(h.events.count(_ == "firewall"),0)
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
    assertEquals(timeout.repo.record.failureCode, Some("REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT"))
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
  private def reviewedRecovery(h: Harness, state: String = "PRESENT_UNHEALTHY", action: String = "RECOVER"): UUID = {
    val owner=uid
    h.repo.inFlight=false
    h.repo.record=h.repo.record.copy(externalNodeId=if(action=="RECOVER") Some(nodeId) else None,
      snapshot=snap.copy(correlationId=if(action=="RECOVER") snap.correlationId else uid,
        recovery=Some(OnboardingRecovery(uid,Some(nodeId),snap.correlationId,owner,state,action))))
    owner
  }

  test("recovered INSTALL_NODE classifies before safe continuation and never creates a Panel node") {
    List(LocalInstallationState.Absent,LocalInstallationState.OwnedPartial,LocalInstallationState.OwnedDamaged,
      LocalInstallationState.OwnedComplete,LocalInstallationState.Foreign,LocalInstallationState.PortConflict,
      LocalInstallationState.Unknown).foreach { state =>
      val h=new Harness(OnboardingPhase.InstallNode)
      h.repo.inFlight=true
      h.installationState=Some(state)
      h.repo.storedSecret=Some(cipher.encrypt(h.repo.record.id,org,NodeInstallationData.fromSecretKey(secretText)))
      h.worker.tick.unsafeRunSync()
      val expected=if(state==LocalInstallationState.Unknown) ProvisioningRunState.Unknown
        else if(!state.repairable) ProvisioningRunState.Failed else ProvisioningRunState.Succeeded
      assertEquals(h.repo.record.state,expected,state.code)
      assertEquals(h.events.count(_=="create"),0)
      assertEquals(h.events.count(_=="install"),if(state==LocalInstallationState.Absent) 1 else 0,state.code)
      assertEquals(h.events.count(_=="repair"),if(Set[LocalInstallationState](LocalInstallationState.OwnedPartial,LocalInstallationState.OwnedDamaged)(state)) 1 else 0,state.code)
      val terminal=h.repo.record
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record,terminal)
    }
  }

  test("an exact existing installation is reused and repeated recovery performs no create or install") {
    val h=new Harness(OnboardingPhase.Validate)
    reviewedRecovery(h,"PRESENT_EXACT")
    h.worker.tick.unsafeRunSync()
    val terminal=h.repo.record
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record,terminal)
    assertEquals(terminal.state,ProvisioningRunState.Succeeded)
    assertEquals(h.events.count(_=="create"),0)
    assertEquals(h.events.count(_=="install"),0)
    assertEquals(h.events.count(_=="repair"),0)
  }

  test("disabled exact node with damaged local installation is repaired using its original owner and enabled") {
    val h=new Harness(OnboardingPhase.Validate,initialPanelNode=node(disabled=true))
    val owner=reviewedRecovery(h)
    h.installationExists=false
    h.installationState=Some(LocalInstallationState.OwnedDamaged)
    h.local=localGood.copy(managedFiles=false,containerRunning=false)
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded)
    assertEquals(h.observedOwner,Some(owner))
    assertEquals(h.events.count(_=="repair"),1)
    assertEquals(h.events.count(_=="enable"),1)
    assertEquals(h.events.count(_=="create"),0)
    assert(h.repo.completed.contains(OnboardingPhase.FinalVerify))
  }

  test("recovery after reviewed upgrade or rollback keeps the original installation identity") {
    List("remnawave/node:3.4.1", RemnawaveNodeReleaseCatalog.releases.find(_.nodeVersion=="3.4.0").get.imageReference).foreach { target =>
      val h=new Harness(OnboardingPhase.Validate)
      val owner=reviewedRecovery(h)
      val original="remnawave/node:3.4.1"
      val reviewed=h.repo.record.snapshot
      h.repo.record=h.repo.record.copy(snapshot=reviewed.copy(imageReference=target,
        recovery=reviewed.recovery.map(_.copy(previousImageReference=Some(original)))))
      h.installationState=Some(LocalInstallationState.OwnedDamaged)
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded)
      assertEquals(h.repo.record.externalNodeId,Some(nodeId))
      assertEquals(h.observedOwner,Some(owner))
      assertEquals(h.observedImage,Some(original))
      assertEquals(h.events.count(_=="create"),0)
    }
  }

  test("known repair failure is FAILED while an uncertain repair remains UNKNOWN") {
    List(false,true).foreach { uncertain =>
      val h=new Harness(OnboardingPhase.InstallNode)
      reviewedRecovery(h)
      h.installationState=Some(LocalInstallationState.OwnedPartial)
      h.repo.storedSecret=Some(cipher.encrypt(h.repo.record.id,org,NodeInstallationData.fromSecretKey(secretText)))
      h.repairResult=ProvisioningStepResult(Map("localInstallationState"->"OWNED_PARTIAL"),Some("PROVISIONING_NODE_COMPOSE_INVALID"),Some(false),uncertain=uncertain)
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.state,if(uncertain) ProvisioningRunState.Unknown else ProvisioningRunState.Failed)
      assertEquals(h.events.count(_=="create"),0)
      assert(!h.events.contains("start"))
    }
  }

  test("fresh identity conflict and uncertain exact lookup prevent all recovery mutations") {
    List(NodeLookupOutcome.Found(node(wrongName=true)),NodeLookupOutcome.Unknown("INTEGRATION_REMOTE_UNAVAILABLE")).foreach { outcome =>
      val h=new Harness(OnboardingPhase.CreateNode)
      reviewedRecovery(h)
      h.lookup=Some(outcome)
      h.worker.tick.unsafeRunSync()
      assert(h.repo.record.state.terminal)
      assertEquals(h.events.count(_=="create"),0)
      assertEquals(h.events.count(_=="firewall"),0)
      assertEquals(h.events.count(_=="repair"),0)
      assertEquals(h.events.count(_=="delete"),0)
    }
  }

  test("uncertain remote delete ends UNKNOWN and never creates or retires a local installation") {
    val h=new Harness(OnboardingPhase.DeleteNode)
    reviewedRecovery(h,action="DELETE_RECREATE")
    h.deletion=NodeDeleteOutcome.Unknown("REMNAWAVE_ONBOARDING_DELETE_UNKNOWN")
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Unknown)
    assertEquals(h.events.count(_=="delete"),1)
    assertEquals(h.events.count(_=="create"),0)
    assertEquals(h.events.count(_=="retire"),0)
    assert(!h.repo.completed.contains(OnboardingPhase.ConfirmNodeDeleted))
  }

  test("delete-recreate verifies the previous address identity when the replacement uses a public IP") {
    val h=new Harness(OnboardingPhase.DeleteNode)
    reviewedRecovery(h,action="DELETE_RECREATE")
    h.repo.record=h.repo.record.copy(snapshot=h.repo.record.snapshot.copy(
      input=h.repo.record.snapshot.input.copy(address="185.10.20.20"),
      recovery=h.repo.record.snapshot.recovery.map(_.copy(previousNodeAddress=Some(input.address)))))
    h.deletion=NodeDeleteOutcome.Unknown("REMNAWAVE_ONBOARDING_DELETE_UNKNOWN")
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Unknown)
    assertEquals(h.events.count(_=="delete"),1)
    assertEquals(h.events.count(_=="create"),0)
    assertEquals(h.events.count(_=="retire"),0)
  }

  test("confirmed deletion and fresh exact NOT_FOUND precede one recreate with a new durable identity") {
    val h=new Harness(OnboardingPhase.DeleteNode)
    reviewedRecovery(h,action="DELETE_RECREATE")
    val target=RemnawaveNodeReleaseCatalog.releases.find(_.nodeVersion=="3.4.0").get.imageReference
    h.repo.record=h.repo.record.copy(snapshot=h.repo.record.snapshot.copy(lifecycleVersion=2,imageReference=target,
      recovery=h.repo.record.snapshot.recovery.map(_.copy(previousImageReference=Some("remnawave/node:3.4.1")))))
    val next=node(id=uid).copy(correlationTags=List("ID:"+h.repo.record.snapshot.correlationId.toString.replace("-","").toUpperCase(java.util.Locale.ROOT)))
    h.create=NodeCreateOutcome.Created(next)
    // The exact old lookup must report the previous identity until DELETE succeeds.
    h.lookup=Some(NodeLookupOutcome.Found(node()))
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded)
    assertEquals(h.repo.record.externalNodeId,Some(next.externalId))
    assertEquals(h.events.count(_=="delete"),1)
    assertEquals(h.events.count(_=="create"),1)
    assertEquals(h.retiredImage,Some("remnawave/node:3.4.1"))
    assertEquals(h.installedImage,Some(target))
    assert(h.repo.completed.contains(OnboardingPhase.ConfirmNodeDeleted))
    assert(h.events.indexOf("delete")<h.events.indexOf("retire-firewall"))
    assert(h.events.indexOf("retire-firewall")<h.events.indexOf("retire"))
    assert(h.events.indexOf("retire")<h.events.indexOf("create"))
    assertNotEquals(h.repo.record.snapshot.correlationId,snap.correlationId)
    assertEquals(OnboardingJson.run(h.repo.record).hcursor.get[String]("correlationId"),Right(h.repo.record.snapshot.correlationId.toString))
    assertEquals(h.repo.record.snapshot.recovery.flatMap(_.previousExternalNodeId),Some(nodeId))
  }

  test("stale or uncertain absence on an approved recreate prevents POST") {
    List(NodeLookupOutcome.Found(node()),NodeLookupOutcome.Unknown("INTEGRATION_REMOTE_UNAVAILABLE")).foreach { outcome =>
      val h=new Harness(OnboardingPhase.CreateNode)
      reviewedRecovery(h,"CONFIRMED_NOT_FOUND","RECREATE")
      h.lookup=Some(outcome)
      h.worker.tick.unsafeRunSync()
      assert(h.repo.record.state.terminal)
      assertEquals(h.events.count(_=="create"),0)
      assertEquals(h.events.count(_=="retire"),0)
    }
  }

  test("v3 ABSENT recreation never retires and requires unchanged local absence before CREATE") {
    LocalInstallationState.all.foreach { state =>
      val h=new Harness(OnboardingPhase.CreateNode)
      reviewedRecovery(h,"CONFIRMED_NOT_FOUND","RECREATE")
      h.repo.record=h.repo.record.copy(snapshot=h.repo.record.snapshot.copy(lifecycleVersion=3,
        recovery=h.repo.record.snapshot.recovery.map(_.copy(localInstallation=Some(LocalInstallationObservation.fromState(LocalInstallationState.Absent))))))
      h.lookup=Some(NodeLookupOutcome.ConfirmedNotFound)
      h.candidateNodes=Some(Nil)
      h.installationState=Some(state)
      val created=node(id=uid).copy(correlationTags=List("ID:"+h.repo.record.snapshot.correlationId.toString.replace("-","").toUpperCase(java.util.Locale.ROOT)))
      h.create=NodeCreateOutcome.Created(created)
      h.worker.tick.unsafeRunSync()
      assertEquals(h.events.count(_=="retire"),0)
      assertEquals(h.events.count(_=="retire-firewall"),0)
      assertEquals(h.events.count(_=="create"),if(state==LocalInstallationState.Absent) 1 else 0)
    }
  }

  test("uncertain enable remains UNKNOWN even when the failure code does not contain UNKNOWN") {
    val h=new Harness(OnboardingPhase.StartNode,initialPanelNode=node(disabled=true))
    reviewedRecovery(h)
    h.enableOutcome=IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_TIMEOUT")
    h.worker.tick.unsafeRunSync()
    assertEquals(h.repo.record.state,ProvisioningRunState.Unknown)
    assertEquals(h.events.count(_=="enable"),1)
    assertEquals(h.events.count(_=="start"),0)
    assertEquals(h.events.count(_=="create"),0)
  }

  test("a conflicting candidate appearing after preview blocks repair and DELETE") {
    List(OnboardingPhase.ConfigureFirewall,OnboardingPhase.DeleteNode).foreach { phase =>
      val h=new Harness(phase)
      reviewedRecovery(h,action=if(phase==OnboardingPhase.DeleteNode) "DELETE_RECREATE" else "RECOVER")
      h.candidateNodes=Some(List(node(),node(id=uid,wrongName=true)))
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.state,ProvisioningRunState.Failed)
      assertEquals(h.repo.record.failureCode,Some("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW"))
      assertEquals(h.events.count(_=="delete"),0)
      assertEquals(h.events.count(_=="create"),0)
      assertEquals(h.events.count(_=="firewall"),0)
      assertEquals(h.events.count(_=="repair"),0)
    }
  }

  test("restarting each existing-node phase reuses its identity and preserves terminal history on repeated ticks") {
    OnboardingPhase.all.foreach { phase =>
      val h=new Harness(phase)
      reviewedRecovery(h,"PRESENT_EXACT")
      h.repo.inFlight=true
      if(OnboardingPhase.all.indexOf(phase)<=OnboardingPhase.all.indexOf(OnboardingPhase.VerifyLocalNode))
        h.repo.storedSecret=Some(cipher.encrypt(h.repo.record.id,org,NodeInstallationData.fromSecretKey(secretText)))
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.state,ProvisioningRunState.Succeeded,phase.code)
      assertEquals(h.repo.record.externalNodeId,Some(nodeId))
      assertEquals(h.events.count(_=="create"),0,phase.code)
      assertEquals(h.events.count(_=="install"),0,phase.code)
      val terminal=h.repo.record
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record,terminal)
    }
  }

  test("uncertain recovered firewall retirement stops before local retirement or CREATE and uses old pinned CIDRs") {
    List(false,true).foreach { recovered =>
      val h=new Harness(OnboardingPhase.RetireNodeFirewall)
      reviewedRecovery(h,"CONFIRMED_NOT_FOUND","RECREATE")
      val oldCidrs=List("203.0.113.0/24")
      h.repo.record=h.repo.record.copy(snapshot=h.repo.record.snapshot.copy(lifecycleVersion=2,
        recovery=h.repo.record.snapshot.recovery.map(_.copy(previousPanelCidrs=Some(oldCidrs)))))
      h.repo.inFlight=recovered
      h.lookup=Some(NodeLookupOutcome.ConfirmedNotFound)
      h.candidateNodes=Some(Nil)
      h.retiredFirewallResult=ProvisioningStepResult(Map.empty,Some("PROVISIONING_FIREWALL_MUTATION_UNCERTAIN"),None,uncertain=true)
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record.state,ProvisioningRunState.Unknown)
      assertEquals(h.retiredCidrs,oldCidrs)
      assertEquals(h.events.count(_=="retire-firewall"),1)
      assertEquals(h.events.count(_=="retire"),0)
      assertEquals(h.events.count(_=="create"),0)
      val terminal=h.repo.record
      h.worker.tick.unsafeRunSync()
      assertEquals(h.repo.record,terminal)
    }
  }

}
