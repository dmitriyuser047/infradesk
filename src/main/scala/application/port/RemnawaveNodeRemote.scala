package ru.bitec.app.ops
package application.port

import domain.connection.Connection
import domain.integration.{LocalInstallationState, LocalInstallationObservation, NodeInstallationData}
import java.util.UUID

/** Backend-controlled values only. Credentials never enter this record. */
final case class RemnawaveNodeRemoteSpec(onboardingId: UUID, resourceId: UUID, externalNodeId: UUID,
  nodePort: Int, imageReference: String, panelCidrs: List[String])
object PanelConnectivityFailure {
  case object ManualOnly extends RuntimeException("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY")
}

final case class RemnawaveNodeLocalEvidence(managedFiles: Boolean, imageMatches: Boolean,
  containerRunning: Boolean, portListening: Boolean, stable: Boolean, firewallMatches: Boolean, installationState: LocalInstallationState = LocalInstallationState.Unknown) {
  def locallyHealthy: Boolean = installationState == LocalInstallationState.OwnedComplete && managedFiles && imageMatches && containerRunning && portListening && stable
  def verified: Boolean = locallyHealthy && firewallMatches
}

/** Each mutation is one separately journalled onboarding phase. No generic shell input. */
trait RemnawaveNodeRemote[F[_]] {
  def localInstallationState(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[LocalInstallationState]
  /** Read-only diagnosis, including proof that absent installations have no own firewall artifacts. */
  def localInstallationObservation(connection: Connection, spec: RemnawaveNodeRemoteSpec)(implicit F: cats.Functor[F]): F[LocalInstallationObservation] =
    F.map(localInstallationState(connection,spec))(LocalInstallationObservation.fromState)
  def preflight(connection: Connection, resourceId: UUID, nodePort: Int): F[ProvisioningStepResult]
  /** Fresh post-baseline readiness check, before creating anything in the Panel. */
  def installationPrerequisites(connection: Connection): F[ProvisioningStepResult]
  def configureFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[ProvisioningStepResult]
  /** Onboarding adds missing reviewed sources but never replaces conflicting existing own rules. */
  def configureOnboardingFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[ProvisioningStepResult]
  def install(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    credential: NodeInstallationData): F[ProvisioningStepResult]
  /** Read-only proof for restart/recreate. Own or absent installations may proceed; foreign state fails closed. */
  def recoveryPreflight(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[ProvisioningStepResult]
  /** Reuses an intact own install, installs an absent one, or CAS-repairs damaged own files. */
  def repair(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    credential: NodeInstallationData): F[ProvisioningStepResult]
  /** Removes only the exact installation owned by this spec. */
  def retireFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[ProvisioningStepResult]
  def retireInstallation(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[ProvisioningStepResult]
  def start(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[ProvisioningStepResult]
  def observe(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[RemnawaveNodeLocalEvidence]
  /** Read-only proof used after crash; absence is never permission to blindly repeat a mutation. */
  def installationPresent(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[Boolean]
  def firewallPresent(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[Boolean]
  /** Read-only: the Panel sources currently allowed by this node's own rule namespace, sorted. */
  def managedPanelCidrs(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[List[String]]
  /** Exact read-only baseline for connectivity recovery; unknown/foreign port policies fail closed. */
  def connectivitySources(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    reviewedSources: List[String]): F[List[String]] =
    throw new UnsupportedOperationException("Panel connectivity observation is unavailable")
  /** Connectivity CAS: only reviewed sources in this exact namespace may exist or be changed.
    * target is the union while testing, then either the confirmed new sources or the previous ones.
    */
  def reconcilePanelSources(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    previousSources: List[String], targetSources: List[String]): F[ProvisioningStepResult] =
    throw new UnsupportedOperationException("Typed Panel connectivity reconciliation is unavailable")
}
