package ru.bitec.app.ops
package application.port

import domain.connection.Connection
import domain.integration.{LocalInstallationState, LocalInstallationObservation, NodeInstallationData}
import java.util.UUID
import java.time.Instant
import domain.integration.PanelSourceObservation

/** The phase journal fixes this lease before any remote instrumentation is attached. */
final case class PanelSynProbeSpec(runId: UUID, node: RemnawaveNodeRemoteSpec, deadline: Instant) {
  require(node.nodePort>0 && node.nodePort<=65535)
}
object PanelSynProbeSpec { val WindowSeconds=60L }

/** Backend-controlled values only. Credentials never enter this record. */
final case class RemnawaveNodeRemoteSpec(onboardingId: UUID, resourceId: UUID, externalNodeId: UUID,
  nodePort: Int, imageReference: String, panelCidrs: List[String], tlsCertificateId: Option[UUID] = None)
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
  /** A listener is reusable only with the exact previous installation ownership proof. */
  def protocolPreflight(connection: Connection, resourceId: UUID, protocol: domain.integration.RemnawaveProtocol,
    owner: Option[RemnawaveNodeRemoteSpec], previousProtocol: Option[domain.integration.RemnawaveProtocol] = None): F[List[domain.integration.ProtocolPortObservation]] =
    throw new UnsupportedOperationException("Protocol port observation is unavailable")
  def clientFirewallRetired(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    protocol: domain.integration.RemnawaveProtocol): F[Boolean] =
    throw new UnsupportedOperationException("Previous client firewall observation is unavailable")
  def http01Preflight(connection: Connection): F[Option[String]] =
    throw new UnsupportedOperationException("HTTP-01 port observation is unavailable")
  def issueCertificate(connection: Connection, resourceId: UUID, serverName: String,
    request: domain.integration.NodeTlsHttp01, deadline: Instant, fresh: Boolean): F[domain.integration.NodeTlsMaterial] =
    throw new UnsupportedOperationException("HTTP-01 issuance is unavailable")
  def cleanupCertificateProbe(connection: Connection, resourceId: UUID, request: domain.integration.NodeTlsHttp01): F[Unit] =
    throw new UnsupportedOperationException("HTTP-01 cleanup is unavailable")
  def installCertificate(connection: Connection, spec: RemnawaveNodeRemoteSpec, certificate: domain.integration.NodeTlsCertificate,
    material: domain.integration.NodeTlsMaterial): F[ProvisioningStepResult] =
    throw new UnsupportedOperationException("Certificate installation is unavailable")
  def configureClientFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    protocol: domain.integration.RemnawaveProtocol): F[ProvisioningStepResult] =
    throw new UnsupportedOperationException("Client firewall configuration is unavailable")
  def retireClientFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    protocol: domain.integration.RemnawaveProtocol): F[ProvisioningStepResult] =
    throw new UnsupportedOperationException("Client firewall retirement is unavailable")
  def verifyProtocol(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    protocol: domain.integration.RemnawaveProtocol): F[ProvisioningStepResult] =
    throw new UnsupportedOperationException("Protocol verification is unavailable")
  /** Public interface addresses read through the authenticated, pinned SSH connection. */
  def publicNodeAddresses(connection: Connection): F[List[String]] =
    throw new UnsupportedOperationException("Public Node address observation is unavailable")
  /** Bounded passive instrumentation, including independent host cleanup after loss of the worker. */
  def observePanelSynSource(connection: Connection, probe: PanelSynProbeSpec): F[PanelSourceObservation] =
    throw new UnsupportedOperationException("Passive Panel source observation is unavailable")
  /** Exact idempotent cleanup. Never deletes another probe's table or timer. */
  def cleanupPanelSynProbe(connection: Connection, probe: PanelSynProbeSpec): F[Unit] =
    throw new UnsupportedOperationException("Passive Panel source cleanup is unavailable")
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
