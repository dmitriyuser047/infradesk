package ru.bitec.app.ops
package application.integration

import application.port.RemnawaveNodeLocalEvidence
import domain.integration._
import domain.provisioning._
import io.circe.Json
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import scala.concurrent.duration._
import support.ServerProfileFixtures

/** Desired versus actual, as one pure function. Nothing here touches a database or a transport. */
final class FleetAssessorSpec extends FunSuite {
  private val now = Instant.parse("2026-10-04T12:00:00Z")
  private val stale = 900.seconds
  private def uid = UUID.randomUUID()
  private val org = uid
  private val integration = uid
  private val resource = uid
  private val node = uid
  private val inboundA = uid
  private val inboundB = uid
  private val configProfile = uid
  private val serverProfile = uid
  private val serverRevision = uid
  private val content = ServerProfileFixtures.content

  private val desired = FleetDesiredContent(serverProfile, serverRevision, 5, content.hash, uid, configProfile,
    uid, uid, 9, "b" * 64, List(inboundA, inboundB), 2222, List("198.51.100.0/24"),
    IntegrationDesiredNodeState.Enabled)

  private def summary(connected: Boolean = true, disabled: Boolean = false,
    profile: Option[String] = Some(configProfile.toString),
    inbounds: Option[List[String]] = Some(List(inboundA, inboundB).map(_.toString))): RemnawaveNodeSummary =
    RemnawaveNodeSummary("192.0.2.10", Some(2222), connected, false, disabled, None, None, None, 0L, false,
      None, None, 0L, "NL", None, None, None, profile, Nil, None, None, inbounds)

  private def assignment(revisionId: UUID = serverRevision) = ServerProfileAssignment(uid, org, resource,
    serverProfile, revisionId, 5, 1L, uid, now)

  private def observation(actual: Json = ServerProfileFixtures.observed(),
    at: Instant = now) = ServerProfileObservation(uid, org, resource, uid, now, None, None, None, actual,
    "c" * 64, at)

  private def desiredState(state: IntegrationDesiredNodeState = IntegrationDesiredNodeState.Enabled) =
    IntegrationDesiredState(uid, org, integration, node, state, 1L, uid, now, now, None, None)

  private val healthyLocal = RemnawaveNodeLocalEvidence(managedFiles = true, imageMatches = true,
    containerRunning = true, portListening = true, stable = true, firewallMatches = true, installationState = LocalInstallationState.OwnedComplete)

  /** The compliant baseline every case below changes exactly one thing in. */
  private def evidence(
    desiredContent: FleetDesiredContent = desired,
    node: Option[RemnawaveNodeSummary] = Some(summary()),
    inventoryObservedAt: Option[Instant] = Some(now),
    remoteConfigSha: Option[String] = Some("b" * 64),
    assignmentValue: Option[ServerProfileAssignment] = Some(assignment()),
    observationValue: Option[ServerProfileObservation] = Some(observation()),
    desiredStateValue: Option[IntegrationDesiredState] = Some(desiredState()),
    localManaged: Boolean = true,
    local: Option[RemnawaveNodeLocalEvidence] = Some(healthyLocal),
    localObservedAt: Option[Instant] = Some(now),
    localFailed: Boolean = false,
    bindingPresent: Boolean = true,
    bindingResourceId: Option[UUID] = Some(resource),
    resourceActive: Boolean = true,
    serverProfileAvailable: Boolean = true,
    configProfileAvailable: Boolean = true,
    inventoryActive: Boolean = true,
    busy: Boolean = false,
    trustedSsh: Boolean = true,
    apiConfirmed: Boolean = true): FleetMemberEvidence = FleetMemberEvidence(desiredContent, resource,
    Some(content), bindingPresent, bindingResourceId, resourceActive, serverProfileAvailable,
    configProfileAvailable, node, inventoryActive, inventoryObservedAt, remoteConfigSha, assignmentValue,
    observationValue, desiredStateValue, localManaged, local, localObservedAt, localFailed, trustedSsh,
    apiConfirmed, busy)

  private def verdict(value: FleetMemberEvidence) = FleetAssessor.assess(value, now, stale)

  test("a member whose every dimension matches with fresh evidence is compliant and healthy") {
    val result = verdict(evidence())
    assertEquals(result.compliance, FleetCompliance.Compliant)
    assertEquals(result.health, FleetHealth.Healthy)
    assertEquals(result.driftReasons, Nil)
    assertEquals(result.healthReasons, Nil)
    assertEquals(result.rolloutBlockers, Nil)
  }

  test("an assignment on another revision is assignment drift, and changed host state is content drift") {
    val assignmentDrift = verdict(evidence(assignmentValue = Some(assignment(revisionId = uid))))
    assertEquals(assignmentDrift.compliance, FleetCompliance.Drifted)
    assert(assignmentDrift.driftReasons.contains(FleetDriftReason.ServerProfileAssignmentMismatch))
    assert(!assignmentDrift.driftReasons.contains(FleetDriftReason.ServerProfileContentDrift))

    // The assignment is right, the server itself is not.
    val contentDrift = verdict(evidence(observationValue =
      Some(observation(ServerProfileFixtures.observed(ServerProfileFixtures.disabled)))))
    assertEquals(contentDrift.compliance, FleetCompliance.Drifted)
    assert(contentDrift.driftReasons.contains(FleetDriftReason.ServerProfileContentDrift))
    assert(!contentDrift.driftReasons.contains(FleetDriftReason.ServerProfileAssignmentMismatch))

    val unobserved = verdict(evidence(observationValue = None))
    assertEquals(unobserved.compliance, FleetCompliance.Unknown)
    assert(unobserved.driftReasons.contains(FleetDriftReason.ServerProfileUnobserved))
  }

  test("a different remote configuration hash is revision drift and a different profile is a mismatch") {
    val hashDrift = verdict(evidence(remoteConfigSha = Some("a" * 64)))
    assertEquals(hashDrift.compliance, FleetCompliance.Drifted)
    assert(hashDrift.driftReasons.contains(FleetDriftReason.ConfigRevisionDrift))

    val profileDrift = verdict(evidence(node = Some(summary(profile = Some(uid.toString)))))
    assertEquals(profileDrift.compliance, FleetCompliance.Drifted)
    assert(profileDrift.driftReasons.contains(FleetDriftReason.ConfigProfileMismatch))

    val unobserved = verdict(evidence(remoteConfigSha = None))
    assertEquals(unobserved.compliance, FleetCompliance.Unknown)
    assert(unobserved.driftReasons.contains(FleetDriftReason.ConfigRevisionUnobserved))
  }

  test("inbounds compare as sets: order is equal, a missing or an extra one is drift") {
    val reordered = verdict(evidence(node = Some(summary(
      inbounds = Some(List(inboundB, inboundA).map(_.toString))))))
    assertEquals(reordered.compliance, FleetCompliance.Compliant)

    val missing = verdict(evidence(node = Some(summary(inbounds = Some(List(inboundA.toString))))))
    assertEquals(missing.compliance, FleetCompliance.Drifted)
    assert(missing.driftReasons.contains(FleetDriftReason.ActiveInboundsDrift))

    val extra = verdict(evidence(node = Some(summary(
      inbounds = Some(List(inboundA, inboundB, uid).map(_.toString))))))
    assertEquals(extra.compliance, FleetCompliance.Drifted)
    assert(extra.driftReasons.contains(FleetDriftReason.ActiveInboundsDrift))

    // A snapshot stored before inbounds were observed proves nothing either way.
    val unobserved = verdict(evidence(node = Some(summary(inbounds = None))))
    assertEquals(unobserved.compliance, FleetCompliance.Unknown)
    assert(unobserved.driftReasons.contains(FleetDriftReason.ActiveInboundsUnobserved))
  }

  test("a contradicting stored intent and a contradicting panel flag are separate drifts") {
    val intent = verdict(evidence(desiredStateValue = Some(desiredState(IntegrationDesiredNodeState.Disabled))))
    assertEquals(intent.compliance, FleetCompliance.Drifted)
    assert(intent.driftReasons.contains(FleetDriftReason.DesiredNodeStateDrift))

    val missingIntent = verdict(evidence(desiredStateValue = None))
    assertEquals(missingIntent.compliance, FleetCompliance.Drifted)
    assert(missingIntent.driftReasons.contains(FleetDriftReason.DesiredNodeStateDrift))

    val actual = verdict(evidence(node = Some(summary(disabled = true))))
    assertEquals(actual.compliance, FleetCompliance.Drifted)
    assert(actual.driftReasons.contains(FleetDriftReason.ActualNodeStateDrift))
    assertEquals(actual.health, FleetHealth.Degraded)
    assert(actual.healthReasons.contains(FleetHealthReason.NodeDisabledUnexpectedly))
  }

  test("a configuration that fully matches while the node is offline is compliant but degraded") {
    val result = verdict(evidence(node = Some(summary(connected = false))))
    assertEquals(result.compliance, FleetCompliance.Compliant)
    assertEquals(result.health, FleetHealth.Degraded)
    assertEquals(result.healthReasons, List(FleetHealthReason.NodeDisconnected))
    assertEquals(result.driftReasons, Nil)
  }

  test("a fleet that wants a node disabled does not call an offline node unhealthy") {
    val content = desired.copy(desiredNodeState = IntegrationDesiredNodeState.Disabled)
    val result = verdict(evidence(desiredContent = content, node = Some(summary(connected = false,
      disabled = true)), desiredStateValue = Some(desiredState(IntegrationDesiredNodeState.Disabled)),
      local = Some(healthyLocal.copy(containerRunning = false, portListening = false))))
    assertEquals(result.health, FleetHealth.Healthy)
    assertEquals(result.healthReasons, Nil)
  }

  test("evidence older than the threshold is never compliant and never drifted") {
    val old = Some(now.minusSeconds(stale.toSeconds + 1))
    val result = FleetAssessor.assess(evidence(inventoryObservedAt = old), now, stale)
    assertEquals(result.compliance, FleetCompliance.Unknown)
    assert(result.driftReasons.contains(FleetDriftReason.InventoryStale))
    assertEquals(result.health, FleetHealth.Unknown)
    assertEquals(result.healthReasons, List(FleetHealthReason.InventoryStale))

    // Even a difference that the stale snapshot shows is not reported as drift.
    val staleMismatch = FleetAssessor.assess(evidence(inventoryObservedAt = old,
      node = Some(summary(profile = Some(uid.toString)))), now, stale)
    assertEquals(staleMismatch.compliance, FleetCompliance.Unknown)
    assert(!staleMismatch.driftReasons.contains(FleetDriftReason.ConfigProfileMismatch))
  }

  test("local port, firewall and installation drift are separate reasons, and no mutation is implied") {
    val port = verdict(evidence(local = Some(healthyLocal.copy(portListening = false))))
    assert(port.driftReasons.contains(FleetDriftReason.NodePortDrift))
    assertEquals(port.compliance, FleetCompliance.Drifted)

    val firewall = verdict(evidence(local = Some(healthyLocal.copy(firewallMatches = false))))
    assert(firewall.driftReasons.contains(FleetDriftReason.PanelCidrDrift))

    val files = verdict(evidence(local = Some(healthyLocal.copy(managedFiles = false))))
    assert(files.driftReasons.contains(FleetDriftReason.LocalInstallationDrift))

    // The running image is part of the same managed installation, and never an upgrade proposal.
    val image = verdict(evidence(local = Some(healthyLocal.copy(imageMatches = false))))
    assert(image.driftReasons.contains(FleetDriftReason.LocalInstallationDrift))
  }

  test("a node Stage25C never installed keeps its panel verdict and its local state stays unproven") {
    val result = verdict(evidence(localManaged = false, local = None, localObservedAt = None,
      apiConfirmed = false))
    assertEquals(result.compliance, FleetCompliance.Unknown)
    assert(result.driftReasons.contains(FleetDriftReason.LocalManagementUnavailable))
    assert(result.driftReasons.contains(FleetDriftReason.ApiContractUnconfirmed))
    // The panel dimensions were still compared, so no mismatch is reported for them.
    assert(!result.driftReasons.contains(FleetDriftReason.ConfigProfileMismatch))
    assert(!result.driftReasons.contains(FleetDriftReason.ActiveInboundsDrift))
    assert(result.rolloutBlockers.contains(FleetRolloutBlocker.NoManagedLocalInstallation))
    assert(result.rolloutBlockers.contains(FleetRolloutBlocker.ApiContractUnconfirmed))
    assertEquals(result.health, FleetHealth.Healthy)

    // A managed node whose observation failed is unknown, not unhealthy by assumption.
    val failed = verdict(evidence(local = None, localObservedAt = None, localFailed = true))
    assertEquals(failed.compliance, FleetCompliance.Unknown)
    assert(failed.driftReasons.contains(FleetDriftReason.LocalObservationUnavailable))
    assertEquals(failed.health, FleetHealth.Unknown)
    assertEquals(failed.healthReasons, List(FleetHealthReason.LocalObservationFailed))
  }

  test("a structural problem blocks the comparison instead of reporting a difference") {
    List(
      evidence(bindingPresent = false, bindingResourceId = None) -> FleetDriftReason.BindingMissing,
      evidence(bindingResourceId = Some(uid)) -> FleetDriftReason.BindingMissing,
      evidence(node = None) -> FleetDriftReason.InventoryMissing,
      evidence(inventoryActive = false) -> FleetDriftReason.InventoryMissing,
      evidence(resourceActive = false) -> FleetDriftReason.ResourceUnavailable,
      evidence(serverProfileAvailable = false) -> FleetDriftReason.ServerProfileUnavailable,
      evidence(configProfileAvailable = false) -> FleetDriftReason.ConfigProfileUnavailable
    ).foreach { case (value, reason) =>
      val result = verdict(value)
      assertEquals(result.compliance, FleetCompliance.Blocked, reason.code)
      assert(result.driftReasons.contains(reason), reason.code)
    }
  }

  test("a member in a conflicting operation is blocked from a rollout without being called drifted") {
    val result = verdict(evidence(busy = true))
    assertEquals(result.compliance, FleetCompliance.Compliant)
    assertEquals(result.rolloutBlockers, List(FleetRolloutBlocker.ActiveConflictingOperation))
  }

  test("an untrusted SSH source is a rollout blocker, which Stage25D only records") {
    val result = verdict(evidence(trustedSsh = false))
    assert(result.rolloutBlockers.contains(FleetRolloutBlocker.NoTrustedSsh))
  }

  test("a preview judges only what the database knows and ignores the local dimensions") {
    val result = FleetAssessor.assess(evidence(localManaged = false, local = None, localObservedAt = None,
      apiConfirmed = false), now, stale, localDimensions = false)
    assertEquals(result.compliance, FleetCompliance.Compliant)
    assert(!result.driftReasons.contains(FleetDriftReason.LocalManagementUnavailable))
    assert(!result.driftReasons.contains(FleetDriftReason.ApiContractUnconfirmed))
  }
  test("typed local installation states govern fleet compliance and admission even when legacy booleans are true") {
    LocalInstallationState.all.foreach { state =>
      val verdict=FleetAssessor.assess(evidence(local=Some(healthyLocal.copy(installationState=state))),now,stale)
      val expected=state match {
        case LocalInstallationState.OwnedComplete => FleetCompliance.Compliant
        case LocalInstallationState.Unknown => FleetCompliance.Unknown
        case LocalInstallationState.Foreign | LocalInstallationState.PortConflict => FleetCompliance.Blocked
        case _ => FleetCompliance.Drifted
      }
      assertEquals(verdict.compliance,expected,state.code)
      if(state != LocalInstallationState.OwnedComplete) assert(verdict.rolloutBlockers.contains(FleetRolloutBlocker.NoManagedLocalInstallation))
    }
  }

}
