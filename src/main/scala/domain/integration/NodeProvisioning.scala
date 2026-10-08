package ru.bitec.app.ops
package domain.integration

import java.util.UUID

sealed abstract class NodeProvisioningCapability(val code: String)
object NodeProvisioningCapability {
  case object Inventory extends NodeProvisioningCapability("NODE_INVENTORY")
  case object Create extends NodeProvisioningCapability("NODE_CREATE")
  case object InstallationData extends NodeProvisioningCapability("NODE_INSTALLATION_DATA")
  case object Status extends NodeProvisioningCapability("NODE_STATUS")
  case object CreateIdempotency extends NodeProvisioningCapability("NODE_CREATE_IDEMPOTENCY")
  case object ConfigProfile extends NodeProvisioningCapability("NODE_CONFIG_PROFILE")
  case object CreateReconciliation extends NodeProvisioningCapability("NODE_CREATE_RECONCILIATION")
  case object AddressUpdate extends NodeProvisioningCapability("NODE_ADDRESS_UPDATE")
}

/** Runtime evidence for node provisioning only, not permission for actions or config deployment.
  * The provider advertises those independent contracts through IntegrationCapability.
  * Never contains credentials.
  */
final case class NodeApiCompatibility(serverVersion: Option[String], apiGeneration: Option[String],
  sourceCommit: Option[String], capabilities: Set[NodeProvisioningCapability], blocker: Option[String]) {
  def provisioningReady: Boolean = blocker.isEmpty && Set[NodeProvisioningCapability](
    NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData,
    NodeProvisioningCapability.Status, NodeProvisioningCapability.ConfigProfile).subsetOf(capabilities)
}

final case class NodeCreateIntent(name: String, address: String, port: Int,
  configProfileId: UUID, activeInboundIds: List[UUID], correlationId: UUID)

/** Version-neutral projection: no raw provider DTO, config or registration credential. */
final case class ProvisionedNode(externalId: UUID, name: String, address: String, port: Option[Int],
  connected: Boolean, connecting: Boolean, disabled: Boolean, configProfileId: Option[UUID],
  activeInboundIds: List[UUID], correlationTags: List[String])

sealed trait NodeLookupOutcome
object NodeLookupOutcome {
  final case class Found(node: ProvisionedNode) extends NodeLookupOutcome
  case object ConfirmedNotFound extends NodeLookupOutcome
  final case class Unknown(code: String) extends NodeLookupOutcome
}

sealed trait NodeDeleteOutcome
object NodeDeleteOutcome {
  case object Deleted extends NodeDeleteOutcome
  final case class Rejected(code: String) extends NodeDeleteOutcome
  final case class Unknown(code: String) extends NodeDeleteOutcome
}

sealed trait NodeCreateOutcome
object NodeCreateOutcome {
  final case class Created(node: ProvisionedNode) extends NodeCreateOutcome
  final case class Rejected(code: String) extends NodeCreateOutcome
  final case class Unknown(code: String) extends NodeCreateOutcome
}

sealed trait NodeCreateReconciliation
object NodeCreateReconciliation {
  final case class Confirmed(node: ProvisionedNode) extends NodeCreateReconciliation
  case object NotProven extends NodeCreateReconciliation
}

/** Secret-bearing backend value. Deliberately not a case class and has no JSON encoder. */
final class NodeInstallationData private (val secretKey: String) {
  override def toString: String = "NodeInstallationData(<redacted>)"
}
object NodeInstallationData {
  def fromSecretKey(value: String): NodeInstallationData = new NodeInstallationData(value)
}
