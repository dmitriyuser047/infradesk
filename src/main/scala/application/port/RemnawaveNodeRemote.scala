package ru.bitec.app.ops
package application.port

import domain.connection.Connection
import domain.integration.NodeInstallationData
import java.util.UUID

/** Backend-controlled values only. Credentials never enter this record. */
final case class RemnawaveNodeRemoteSpec(onboardingId: UUID, resourceId: UUID, externalNodeId: UUID,
  nodePort: Int, imageReference: String, panelCidrs: List[String])

final case class RemnawaveNodeLocalEvidence(managedFiles: Boolean, imageMatches: Boolean,
  containerRunning: Boolean, portListening: Boolean, stable: Boolean, firewallMatches: Boolean) {
  def verified: Boolean = managedFiles && imageMatches && containerRunning && portListening && stable && firewallMatches
}

/** Each mutation is one separately journalled onboarding phase. No generic shell input. */
trait RemnawaveNodeRemote[F[_]] {
  def preflight(connection: Connection, resourceId: UUID, nodePort: Int): F[ProvisioningStepResult]
  /** Fresh post-baseline readiness check, before creating anything in the Panel. */
  def installationPrerequisites(connection: Connection): F[ProvisioningStepResult]
  def configureFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[ProvisioningStepResult]
  def install(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    credential: NodeInstallationData): F[ProvisioningStepResult]
  def start(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[ProvisioningStepResult]
  def observe(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[RemnawaveNodeLocalEvidence]
  /** Read-only proof used after crash; absence is never permission to blindly repeat a mutation. */
  def installationPresent(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[Boolean]
  def firewallPresent(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[Boolean]
  /** Read-only: the Panel sources currently allowed by this node's own rule namespace, sorted. */
  def managedPanelCidrs(connection: Connection, spec: RemnawaveNodeRemoteSpec): F[List[String]]
}
