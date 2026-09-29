package ru.bitec.app.ops
package domain.integration

import java.time.Instant
import java.util.UUID

/** What kind of external object an integration reports. Provider-independent. */
sealed trait IntegrationObjectType { def code: String }
object IntegrationObjectType {
  case object Node extends IntegrationObjectType { val code = "NODE" }
  case object Host extends IntegrationObjectType { val code = "HOST" }
  case object ConfigProfile extends IntegrationObjectType { val code = "CONFIG_PROFILE" }
  val All: List[IntegrationObjectType] = List(Node, Host, ConfigProfile)
  def fromCode(code: String): Either[IllegalArgumentException, IntegrationObjectType] =
    All.find(_.code == code).toRight(new IllegalArgumentException("Unknown integration object type"))
}

/** Remnawave's own view of a node's connection, derived deterministically from its flags. It is an
  * external status and never replaces the status of an InfraDesk resource.
  */
sealed trait RemnawaveNodeState { def code: String }
object RemnawaveNodeState {
  case object Connected extends RemnawaveNodeState { val code = "CONNECTED" }
  case object Connecting extends RemnawaveNodeState { val code = "CONNECTING" }
  case object Disconnected extends RemnawaveNodeState { val code = "DISCONNECTED" }
  case object Disabled extends RemnawaveNodeState { val code = "DISABLED" }
  val All: List[RemnawaveNodeState] = List(Connected, Connecting, Disconnected, Disabled)
  def fromCode(code: String): Option[RemnawaveNodeState] = All.find(_.code == code)
  def of(isDisabled: Boolean, isConnected: Boolean, isConnecting: Boolean): RemnawaveNodeState =
    if (isDisabled) Disabled else if (isConnected) Connected else if (isConnecting) Connecting else Disconnected
}

/** A typed, sanitized projection of an external object. Only the fields InfraDesk shows are kept:
  * no proxy URL, no Xray configuration, no raw inbound, no transport parameters.
  */
sealed trait IntegrationObjectSummary
final case class RemnawaveNodeSummary(
  address: String,
  port: Option[Int],
  isConnected: Boolean,
  isConnecting: Boolean,
  isDisabled: Boolean,
  lastStatusChange: Option[Instant],
  xrayVersion: Option[String],
  nodeVersion: Option[String],
  xrayUptimeSeconds: Long,
  trafficTrackingActive: Boolean,
  trafficLimitBytes: Option[Long],
  trafficUsedBytes: Option[Long],
  usersOnline: Long,
  countryCode: String,
  cpuCount: Option[Int],
  cpuModel: Option[String],
  memoryTotalBytes: Option[Long],
  activeConfigProfileUuid: Option[String],
  tags: List[String],
  providerUuid: Option[String],
  providerName: Option[String]
) extends IntegrationObjectSummary {
  def state: RemnawaveNodeState = RemnawaveNodeState.of(isDisabled, isConnected, isConnecting)
}
final case class RemnawaveHostSummary(
  address: String,
  port: Int,
  isDisabled: Boolean,
  isHidden: Boolean,
  configProfileUuid: Option[String],
  configProfileInboundUuid: Option[String],
  nodeUuids: List[String],
  tags: List[String],
  securityLayer: String,
  serverDescription: Option[String]
) extends IntegrationObjectSummary
final case class RemnawaveInboundSummary(uuid: String, tag: String, inboundType: String,
  network: Option[String], security: Option[String], port: Option[Int])
final case class RemnawaveConfigProfileSummary(
  viewPosition: Int,
  createdAt: Instant,
  updatedAt: Instant,
  nodeUuids: List[String],
  inbounds: List[RemnawaveInboundSummary]
) extends IntegrationObjectSummary

/** One object as the provider reported it in a snapshot, before persistence. */
final case class ObservedIntegrationObject(objectType: IntegrationObjectType, externalId: String,
  displayName: String, summary: IntegrationObjectSummary)

/** A complete snapshot: `completeObjectTypes` are the types the provider listed in full, so any
  * stored object of such a type that is missing here is no longer present on the provider.
  */
final case class IntegrationObservation(objects: List[ObservedIntegrationObject],
  completeObjectTypes: Set[IntegrationObjectType]) {
  def count(objectType: IntegrationObjectType): Int = objects.count(_.objectType == objectType)
}

final case class IntegrationInventoryObject(
  id: UUID,
  organizationId: UUID,
  integrationId: UUID,
  objectType: IntegrationObjectType,
  externalId: String,
  displayName: String,
  summary: IntegrationObjectSummary,
  isActive: Boolean,
  firstSeenAt: Instant,
  lastSeenAt: Instant,
  lastSeenSyncSessionId: UUID
)

sealed trait IntegrationSyncTrigger { def code: String }
object IntegrationSyncTrigger {
  case object Manual extends IntegrationSyncTrigger { val code = "MANUAL" }
  case object Scheduled extends IntegrationSyncTrigger { val code = "SCHEDULED" }
  def fromCode(code: String): Either[IllegalArgumentException, IntegrationSyncTrigger] =
    List(Manual, Scheduled).find(_.code == code).toRight(new IllegalArgumentException("Unknown sync trigger"))
}

sealed trait IntegrationSyncStatus { def code: String }
object IntegrationSyncStatus {
  case object Running extends IntegrationSyncStatus { val code = "RUNNING" }
  case object Completed extends IntegrationSyncStatus { val code = "COMPLETED" }
  case object Failed extends IntegrationSyncStatus { val code = "FAILED" }
  def fromCode(code: String): Either[IllegalArgumentException, IntegrationSyncStatus] =
    List(Running, Completed, Failed).find(_.code == code).toRight(new IllegalArgumentException("Unknown sync status"))
}

final case class IntegrationSyncCounts(nodes: Int, hosts: Int, configProfiles: Int, deactivated: Int)

final case class IntegrationSyncSession(
  id: UUID,
  organizationId: UUID,
  integrationId: UUID,
  trigger: IntegrationSyncTrigger,
  requestedByUserId: Option[UUID],
  startedAt: Instant,
  recoverAfterAt: Instant,
  finishedAt: Option[Instant],
  status: IntegrationSyncStatus,
  errorCode: Option[String],
  errorMessage: Option[String],
  counts: Option[IntegrationSyncCounts]
)

final case class IntegrationResourceBinding(
  id: UUID,
  organizationId: UUID,
  integrationId: UUID,
  inventoryObjectId: UUID,
  resourceId: UUID,
  createdByUserId: UUID,
  createdAt: Instant,
  updatedAt: Instant
)
