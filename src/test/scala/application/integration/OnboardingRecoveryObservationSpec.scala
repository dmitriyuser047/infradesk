package ru.bitec.app.ops
package application.integration

import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import domain.integration._
import domain.provisioning.ProvisioningRunState
import java.time.Instant
import java.util.UUID
import munit.FunSuite

final class OnboardingRecoveryObservationSpec extends FunSuite {
  private val now = Instant.parse("2026-10-01T12:00:00Z")
  private def uid: UUID = UUID.randomUUID()
  private val org = uid
  private val integrationId = uid
  private val resourceId = uid
  private val profileId = uid
  private val inboundId = uid
  private val externalId = uid
  private val originalCorrelation = uid
  private val ownerId = uid
  private val sourceRunId = uid
  private val api = NodeApiCompatibility(Some("2.8.0"), Some("PROFILE_PUBKEY"), Some("reviewed-commit"),
    Set(NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData,
      NodeProvisioningCapability.Status, NodeProvisioningCapability.ConfigProfile,
      NodeProvisioningCapability.CreateReconciliation, NodeProvisioningCapability.CreateIdempotency), None)
  private val input = OnboardingInput(resourceId, "edge-01", "192.0.2.20", 30222,
    profileId, List(inboundId), List("198.51.100.0/24"))
  private val connection = Connection(uid, org, ConnectionScope.Organization, "SSH", "edge", "Edge",
    ConnectionConfig(Map.empty), None, true, now, now)
  private val context = IntegrationRuntimeContext(integrationId, org,
    IntegrationBaseUrl.parse("https://panel.example.test").toOption.get, RemnawaveCredential("token", None))
  private val localGood = RemnawaveNodeLocalEvidence(true, true, true, true, true, true, LocalInstallationState.OwnedComplete)

  private def tag(correlation: UUID): String =
    "ID:" + correlation.toString.replace("-", "").toUpperCase(java.util.Locale.ROOT)

  private def node(id: UUID = externalId, name: String = input.nodeName, address: String = input.address,
    port: Option[Int] = Some(input.nodePort), connected: Boolean = true, disabled: Boolean = false,
    configProfileId: Option[UUID] = Some(input.configProfileId), inbounds: List[UUID] = input.activeInboundIds,
    tags: List[String] = List(tag(originalCorrelation))): ProvisionedNode =
    ProvisionedNode(id, name, address, port, connected, !connected, disabled, configProfileId, inbounds, tags)

  private def snapshot(correlation: UUID = originalCorrelation,
    recovery: Option[OnboardingRecovery] = None): OnboardingSnapshot =
    OnboardingSnapshot(input, now, uid, connection.id, now, uid, uid, 3, uid, 2L, "hash", uid,
      false, api, "remnawave/node:2.8.0", correlation, "server", "Ubuntu", "Default", List("inbound"),
      Nil, Nil, Nil, recovery)

  private def previous(state: ProvisioningRunState = ProvisioningRunState.Failed,
    phase: OnboardingPhase = OnboardingPhase.WaitForPanel, external: Option[UUID] = Some(externalId),
    snap: OnboardingSnapshot = snapshot()): RemnawaveNodeOnboardingRun =
    RemnawaveNodeOnboardingRun(sourceRunId, org, integrationId, resourceId, None, uid, state,
      phase, snap, now, now, externalNodeId = external)

  private final class Harness(initialNode: ProvisionedNode = node(), candidates0: List[ProvisionedNode] = Nil,
    lookup0: NodeLookupOutcome = NodeLookupOutcome.Found(node()), local0: RemnawaveNodeLocalEvidence = localGood) {
    var candidates = candidates0
    var lookupOutcome = lookup0
    var lookupError: Option[Throwable] = None
    var local = local0
    var localError: Option[Throwable] = None
    var lookupCalls = 0
    var findCalls = 0
    var observeCalls = 0
    var observedSpec: Option[RemnawaveNodeRemoteSpec] = None
    var forbiddenCalls = List.empty[String]

    private def forbidden[A](name: String): IO[A] = IO {
      forbiddenCalls :+= name
      throw new AssertionError(s"Read-only recovery observation called $name")
    }

    val nodes: NodeProvisioningTransport[IO] = new NodeProvisioningTransport[IO] {
      override def inspect(c: IntegrationRuntimeContext) = IO.pure(api)
      override def findNodes(c: IntegrationRuntimeContext) = IO { findCalls += 1; candidates }
      override def getNode(c: IntegrationRuntimeContext, id: UUID) = IO.pure(initialNode)
      override def lookupNode(c: IntegrationRuntimeContext, id: UUID) = IO {
        lookupCalls += 1
        lookupError match {
          case Some(error) => throw error
          case None => lookupOutcome
        }
      }
      override def deleteNode(c: IntegrationRuntimeContext, id: UUID, reviewed: NodeApiCompatibility) = forbidden[NodeDeleteOutcome]("deleteNode")
      override def createNode(c: IntegrationRuntimeContext, intent: NodeCreateIntent, reviewed: NodeApiCompatibility) = forbidden[NodeCreateOutcome]("createNode")
      override def installationData(c: IntegrationRuntimeContext, reviewed: NodeApiCompatibility) = forbidden[NodeInstallationData]("installationData")
      override def reconcileCreate(c: IntegrationRuntimeContext, intent: NodeCreateIntent, reviewed: NodeApiCompatibility) = forbidden[NodeCreateReconciliation]("reconcileCreate")
    }

    val remote: RemnawaveNodeRemote[IO] = new RemnawaveNodeRemote[IO] {
      override def localInstallationState(c: Connection,s: RemnawaveNodeRemoteSpec) = IO { observedSpec=Some(s); localError.foreach(throw _); local.installationState }
      override def preflight(c: Connection, r: UUID, p: Int) = forbidden[ProvisioningStepResult]("preflight")
      override def installationPrerequisites(c: Connection) = forbidden[ProvisioningStepResult]("installationPrerequisites")
      override def configureFirewall(c: Connection, s: RemnawaveNodeRemoteSpec) = forbidden[ProvisioningStepResult]("configureFirewall")
      override def configureOnboardingFirewall(c: Connection, s: RemnawaveNodeRemoteSpec) = forbidden[ProvisioningStepResult]("configureOnboardingFirewall")
      override def install(c: Connection, s: RemnawaveNodeRemoteSpec, d: NodeInstallationData) = forbidden[ProvisioningStepResult]("install")
      override def recoveryPreflight(c: Connection, s: RemnawaveNodeRemoteSpec) = forbidden[ProvisioningStepResult]("recoveryPreflight")
      override def repair(c: Connection, s: RemnawaveNodeRemoteSpec, d: NodeInstallationData) = forbidden[ProvisioningStepResult]("repair")
      override def retireFirewall(c: Connection,s: RemnawaveNodeRemoteSpec) = forbidden[ProvisioningStepResult]("retireFirewall")
      override def retireInstallation(c: Connection, s: RemnawaveNodeRemoteSpec) = forbidden[ProvisioningStepResult]("retireInstallation")
      override def start(c: Connection, s: RemnawaveNodeRemoteSpec) = forbidden[ProvisioningStepResult]("start")
      override def observe(c: Connection, s: RemnawaveNodeRemoteSpec) = IO { observeCalls += 1; observedSpec=Some(s); local }
      override def installationPresent(c: Connection, s: RemnawaveNodeRemoteSpec) = forbidden[Boolean]("installationPresent")
      override def firewallPresent(c: Connection, s: RemnawaveNodeRemoteSpec) = forbidden[Boolean]("firewallPresent")
      override def managedPanelCidrs(c: Connection, s: RemnawaveNodeRemoteSpec) = forbidden[List[String]]("managedPanelCidrs")
    }

    def inspect(run: RemnawaveNodeOnboardingRun = previous(), action: String = "RECOVER",
      conn: Option[Connection] = Some(connection)): OnboardingRecoveryObservation.Evidence =
      OnboardingRecoveryObservation.inspect(Some(run), input, context, nodes, remote, conn, action).unsafeRunSync()

    def assertReadOnly(): Unit = assertEquals(forbiddenCalls, Nil)
  }

  private def recovery(evidence: OnboardingRecoveryObservation.Evidence): OnboardingRecovery =
    evidence.recovery.getOrElse(fail("expected recovery evidence"))

  test("foreign tenant, integration and resource chains are rejected before provider reads") {
    List(previous().copy(organizationId=uid),previous().copy(integrationId=uid),previous().copy(resourceId=uid)).foreach { foreign =>
      val h=new Harness(candidates0=List(node()))
      val error=intercept[IntegrationError](h.inspect(foreign))
      assertEquals(error.code,"REMNAWAVE_ONBOARDING_NOT_FOUND")
      assertEquals(h.lookupCalls,0)
      assertEquals(h.findCalls,0)
      h.assertReadOnly()
    }
  }

  test("known exact healthy node is reused with its correlation and installation owner") {
    val h = new Harness(candidates0 = List(node()))
    val result = h.inspect()
    assertEquals(recovery(result).state, "PRESENT_EXACT")
    assertEquals(recovery(result).action, "RECOVER")
    assertEquals(result.node, Some(externalId))
    assertEquals(result.correlation, originalCorrelation)
    assertEquals(recovery(result).installationOwnerId, sourceRunId)
    assertEquals(h.observeCalls, 1)
    h.assertReadOnly()
  }

  test("disconnected, disabled, or locally damaged exact nodes are PRESENT_UNHEALTHY and reused") {
    val cases = List(
      node(connected = false) -> localGood,
      node(disabled = true) -> localGood,
      node() -> localGood.copy(stable = false),
      node() -> localGood.copy(managedFiles = false))
    cases.foreach { case (remoteNode, local) =>
      val h = new Harness(initialNode = remoteNode, candidates0 = List(remoteNode), lookup0 = NodeLookupOutcome.Found(remoteNode), local0 = local)
      val result = h.inspect()
      assertEquals(recovery(result).state, "PRESENT_UNHEALTHY")
      assertEquals(recovery(result).action, "RECOVER")
      assertEquals(result.node, Some(externalId))
      assertEquals(result.correlation, originalCorrelation)
      h.assertReadOnly()
    }
  }

  test("fresh local classifier is carried to preview and unsafe states never become exact") {
    List(LocalInstallationState.Absent,LocalInstallationState.OwnedPartial,LocalInstallationState.OwnedDamaged,
      LocalInstallationState.Foreign,LocalInstallationState.PortConflict,LocalInstallationState.Unknown).foreach { state =>
      val h=new Harness(local0=localGood.copy(installationState=state))
      val result=h.inspect()
      assertEquals(result.localState,Some(state))
      assertEquals(recovery(result).state,if(state==LocalInstallationState.Unknown) "UNKNOWN" else "PRESENT_UNHEALTHY")
      h.assertReadOnly()
    }
  }

  test("a recovery chain observes the immutable previous image and owner after target representation changes") {
    val original="remnawave/node:2.8.0"
    val target=RemnawaveNodeReleaseCatalog.forReference(original).get.imageReference
    val proof=OnboardingRecovery(uid,Some(externalId),originalCorrelation,ownerId,"PRESENT_UNHEALTHY","RECOVER",
      previousImageReference=Some(original))
    val h=new Harness()
    val result=h.inspect(previous(snap=snapshot(recovery=Some(proof)).copy(imageReference=target)))
    assertEquals(h.observedSpec.map(_.imageReference),Some(original))
    assertEquals(h.observedSpec.map(_.onboardingId),Some(ownerId))
    assertEquals(recovery(result).previousImageReference,Some(original))
    assertEquals(result.node,Some(externalId))
    h.assertReadOnly()
  }

  test("typed confirmed absence retains observation identity until an executable plan is persisted") {
    val h = new Harness(candidates0 = Nil, lookup0 = NodeLookupOutcome.ConfirmedNotFound)
    val result = h.inspect()
    assertEquals(recovery(result).state, "CONFIRMED_NOT_FOUND")
    assertEquals(recovery(result).action, "RECREATE")
    assertEquals(recovery(result).previousExternalNodeId, Some(externalId))
    assertEquals(recovery(result).previousCorrelationId, originalCorrelation)
    assertEquals(recovery(result).installationOwnerId, sourceRunId)
    assertEquals(result.node, None)
    assertEquals(result.correlation, originalCorrelation)
    h.assertReadOnly()
  }

  test("Panel absence survives every local classification and SSH failure without mutations") {
    LocalInstallationState.all.foreach { state =>
      val h=new Harness(lookup0=NodeLookupOutcome.ConfirmedNotFound,local0=localGood.copy(installationState=state))
      val result=h.inspect()
      assertEquals(recovery(result).state,"CONFIRMED_NOT_FOUND")
      assertEquals(recovery(result).action,"RECREATE")
      assertEquals(result.localInstallation.map(_.state),Some(state))
      assertEquals(result.correlation,originalCorrelation)
      assertEquals(h.inspect().correlation,result.correlation)
      assertEquals(result.localInstallation.flatMap(_.blocker).nonEmpty,!state.repairable)
      h.assertReadOnly()
    }
    val h=new Harness(lookup0=NodeLookupOutcome.ConfirmedNotFound)
    h.localError=Some(new RuntimeException("stderr containing secret"))
    val result=h.inspect()
    assertEquals(recovery(result).state,"CONFIRMED_NOT_FOUND")
    assertEquals(result.localInstallation,Some(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.SshUnavailable)))
    assert(!OnboardingSnapshotCodec.encodeRecovery(recovery(result)).noSpaces.contains("secret"))
    h.assertReadOnly()
    assertEquals(recovery(h.inspect(conn=None)).state,"CONFIRMED_NOT_FOUND")
  }

  test("lookup unknown, timeout, and 5xx outcomes stay UNKNOWN and never create") {
    val outcomes: List[(String, Option[NodeLookupOutcome], Option[Throwable])] = List(
      ("lookup unknown", Some(NodeLookupOutcome.Unknown("INTEGRATION_NODE_LOOKUP_RESULT_UNKNOWN")), None),
      ("timeout", None, Some(new IntegrationError("INTEGRATION_REMOTE_TIMEOUT", "timeout"))),
      ("server error", Some(NodeLookupOutcome.Unknown("INTEGRATION_HTTP_5XX")), None))
    outcomes.foreach { case (_, outcome, error) =>
      val h = new Harness(candidates0 = List(node()), lookup0 = outcome.getOrElse(NodeLookupOutcome.Found(node())))
      h.lookupError = error
      val result = h.inspect()
      assertEquals(recovery(result).state, "UNKNOWN")
      assertEquals(recovery(result).action, "RECOVER")
      assertEquals(result.node, None)
      assertEquals(h.observeCalls, 0)
      h.assertReadOnly()
    }
  }

  test("each immutable node identity mismatch is classified as a conflict") {
    val changed = List(
      node(name = "other-name"),
      node(address = "192.0.2.99"),
      node(port = Some(input.nodePort + 1)),
      node(configProfileId = Some(uid)),
      node(inbounds = List(uid)),
      node(tags = List(tag(uid))),
      node(id = uid))
    changed.foreach { found =>
      val h = new Harness(initialNode = found, candidates0 = List(found), lookup0 = NodeLookupOutcome.Found(found))
      val result = h.inspect()
      assertEquals(recovery(result).state, "PRESENT_CONFLICT")
      assertEquals(result.node, None)
      assertEquals(h.observeCalls, 0)
      h.assertReadOnly()
    }
  }

  test("a candidate conflict overrides typed confirmed absence") {
    val h = new Harness(candidates0 = List(node(name = "edge-conflict")), lookup0 = NodeLookupOutcome.ConfirmedNotFound)
    val result = h.inspect()
    assertEquals(recovery(result).state, "PRESENT_CONFLICT")
    assertEquals(recovery(result).action, "RECOVER")
    assertEquals(result.node, None)
    h.assertReadOnly()
  }

  test("a terminal recovery with a known external ID carries forward its owner and identity chain") {
    val oldRecovery = OnboardingRecovery(uid, Some(uid), uid, ownerId, "PRESENT_UNHEALTHY", "RECOVER")
    val corr = uid
    val newerNode = node(id = uid, tags = List(tag(corr)))
    val run = previous(state = ProvisioningRunState.Unknown, phase = OnboardingPhase.CreateNode,
      external = Some(newerNode.externalId), snap = snapshot(correlation = corr, recovery = Some(oldRecovery)))
    val h = new Harness(initialNode = newerNode, candidates0 = List(newerNode), lookup0 = NodeLookupOutcome.Found(newerNode))
    val result = h.inspect(run)
    assertEquals(recovery(result).sourceRunId, sourceRunId)
    assertEquals(recovery(result).previousExternalNodeId, Some(newerNode.externalId))
    assertEquals(recovery(result).previousCorrelationId, corr)
    assertEquals(recovery(result).installationOwnerId, ownerId)
    assertEquals(result.node, Some(newerNode.externalId))
    h.assertReadOnly()
  }

  test("an UNKNOWN CREATE without a stored ID safely recovers an exact correlation candidate") {
    val run = previous(state = ProvisioningRunState.Unknown, phase = OnboardingPhase.CreateNode, external = None)
    val candidate = node()
    val h = new Harness(initialNode = candidate, candidates0 = List(candidate), lookup0 = NodeLookupOutcome.Unknown("not used"))
    val result = h.inspect(run)
    assertEquals(recovery(result).state, "PRESENT_EXACT")
    assertEquals(recovery(result).action, "RECOVER")
    assertEquals(result.node, Some(externalId))
    assertEquals(result.correlation, originalCorrelation)
    h.assertReadOnly()
  }

  test("an UNKNOWN CREATE without an ID and no candidate remains UNKNOWN, never absent") {
    val run = previous(state = ProvisioningRunState.Unknown, phase = OnboardingPhase.CreateNode, external = None)
    val h = new Harness(candidates0 = Nil, lookup0 = NodeLookupOutcome.Unknown("not used"))
    val result = h.inspect(run)
    assertEquals(recovery(result).state, "UNKNOWN")
    assertEquals(recovery(result).action, "RECOVER")
    assertEquals(recovery(result).previousExternalNodeId, None)
    assertEquals(result.node, None)
    h.assertReadOnly()
  }

  test("DELETE_RECREATE observation carries old identity without allocating mutation identity") {
    val h = new Harness(candidates0 = List(node()))
    val result = h.inspect(action = "DELETE_RECREATE")
    assertEquals(recovery(result).state, "PRESENT_EXACT")
    assertEquals(recovery(result).action, "DELETE_RECREATE")
    assertEquals(recovery(result).previousExternalNodeId, Some(externalId))
    assertEquals(recovery(result).previousCorrelationId, originalCorrelation)
    assertEquals(recovery(result).installationOwnerId, sourceRunId)
    assertEquals(result.node, None)
    assertEquals(result.correlation, originalCorrelation)
    h.assertReadOnly()
  }

  test("observation never changes the source run or its immutable snapshot") {
    val proof = OnboardingRecovery(uid, Some(uid), uid, ownerId, "PRESENT_UNHEALTHY", "RECOVER")
    val run = previous(state = ProvisioningRunState.Failed, snap = snapshot(recovery = Some(proof)))
    val before = run.snapshot
    val h = new Harness(candidates0 = List(node()))
    h.inspect(run)
    assertEquals(run.snapshot, before)
    assertEquals(run.snapshot.recovery, Some(proof))
    h.assertReadOnly()
  }
}
