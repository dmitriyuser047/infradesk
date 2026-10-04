package ru.bitec.app.ops
package domain.integration

import cats.syntax.all._
import domain.provisioning.ServerProfileDiff
import io.circe.Json
import java.time.Instant
import java.util.UUID

/** A named group of Remnawave nodes inside one integration. The desired configuration lives in an
  * immutable revision; this record only points at the one that is currently desired, so promoting
  * a revision is a metadata change and never a remote operation.
  */
final case class RemnawaveFleet(id: UUID, organizationId: UUID, integrationId: UUID, code: String,
  name: String, description: Option[String], desiredRevisionId: Option[UUID], version: Long,
  archived: Boolean, createdBy: UUID, createdAt: Instant, updatedAt: Instant)

object RemnawaveFleet {
  val MaxMembers = 500
  def validCode(value: String): Boolean = value.matches("[a-z0-9][a-z0-9-]{1,62}")
  def validName(value: String): Boolean = value == value.trim && value.length >= 2 && value.length <= 80 &&
    !value.exists(_.isControl)
  def validDescription(value: Option[String]): Boolean =
    value.forall(text => text.length <= 400 && !text.exists(c => c.isControl && c != '\n'))
}

/** What every member of a fleet should be. It holds references to objects that are already typed and
  * versioned elsewhere - never a raw script, a configuration body or a secret - and deliberately no
  * node image: the image lifecycle is its own stage.
  */
final case class FleetDesiredContent(
  serverProfileId: UUID, serverProfileRevisionId: UUID, serverProfileRevisionNumber: Int,
  serverProfileRevisionHash: String,
  inventoryConfigProfileId: UUID, externalConfigProfileId: UUID, configurationProfileId: UUID,
  configRevisionId: UUID, configRevisionNumber: Int, configRevisionHash: String,
  activeInboundIds: List[UUID], nodePort: Int, panelCidrs: List[String],
  desiredNodeState: IntegrationDesiredNodeState) {
  def normalized: Either[String, FleetDesiredContent] = for {
    cidrs <- OnboardingInput.canonicalCidrs(panelCidrs).left.map(_ => "REMNAWAVE_FLEET_CIDR_INVALID")
    _ <- Either.cond(nodePort > 0 && nodePort <= 65535, (), "REMNAWAVE_FLEET_PORT_INVALID")
    _ <- Either.cond(activeInboundIds.nonEmpty && activeInboundIds.size <= 256 &&
      activeInboundIds.distinct == activeInboundIds, (), "REMNAWAVE_FLEET_INBOUNDS_INVALID")
    _ <- Either.cond(serverProfileRevisionHash.matches("[0-9a-f]{64}") &&
      configRevisionHash.matches("[0-9a-f]{64}"), (), "REMNAWAVE_FLEET_REVISION_HASH_INVALID")
    _ <- Either.cond(serverProfileRevisionNumber >= 1 && configRevisionNumber >= 1, (),
      "REMNAWAVE_FLEET_REVISION_INVALID")
  } yield copy(panelCidrs = cidrs, activeInboundIds = activeInboundIds.sortBy(_.toString))
  def inboundSet: Set[String] = activeInboundIds.map(_.toString).toSet
  def json: Json = FleetDesiredContentCodec.encode(this)
  def hash: String = ServerProfileDiff.hashText(json.noSpaces)
}

/** Same closed representation for persistence and for safe API output. Decoding rejects extra keys. */
object FleetDesiredContentCodec {
  val SchemaVersion = 1
  private val Invalid = "REMNAWAVE_FLEET_CONTENT_INVALID"
  private def str(value: String) = Json.fromString(value)
  private def identifier(value: UUID) = str(value.toString)
  private val keys = Set("schemaVersion", "serverProfileId", "serverProfileRevisionId", "serverProfileRevisionNumber",
    "serverProfileRevisionHash", "inventoryConfigProfileId", "externalConfigProfileId", "configurationProfileId",
    "configRevisionId", "configRevisionNumber", "configRevisionHash", "activeInboundIds", "nodePort", "panelCidrs",
    "desiredNodeState")

  def encode(content: FleetDesiredContent): Json = Json.obj(
    "schemaVersion" -> Json.fromInt(SchemaVersion),
    "serverProfileId" -> identifier(content.serverProfileId),
    "serverProfileRevisionId" -> identifier(content.serverProfileRevisionId),
    "serverProfileRevisionNumber" -> Json.fromInt(content.serverProfileRevisionNumber),
    "serverProfileRevisionHash" -> str(content.serverProfileRevisionHash),
    "inventoryConfigProfileId" -> identifier(content.inventoryConfigProfileId),
    "externalConfigProfileId" -> identifier(content.externalConfigProfileId),
    "configurationProfileId" -> identifier(content.configurationProfileId),
    "configRevisionId" -> identifier(content.configRevisionId),
    "configRevisionNumber" -> Json.fromInt(content.configRevisionNumber),
    "configRevisionHash" -> str(content.configRevisionHash),
    "activeInboundIds" -> Json.arr(content.activeInboundIds.sortBy(_.toString).map(identifier): _*),
    "nodePort" -> Json.fromInt(content.nodePort),
    "panelCidrs" -> Json.arr(content.panelCidrs.sorted.map(str): _*),
    "desiredNodeState" -> str(content.desiredNodeState.code))

  def decode(json: Json): Either[String, FleetDesiredContent] = {
    val cursor = json.hcursor
    def text(key: String) = cursor.get[String](key).left.map(_ => Invalid)
    def uuid(value: String) = Either.fromOption(
      scala.util.Try(UUID.fromString(value)).toOption.filter(_.toString == value), Invalid)
    def reference(key: String) = text(key).flatMap(uuid)
    def number(key: String) = cursor.get[Int](key).left.map(_ => Invalid)
    for {
      _ <- Either.cond(json.asObject.exists(_.keys.toSet == keys), (), Invalid)
      schema <- number("schemaVersion")
      _ <- Either.cond(schema == SchemaVersion, (), "REMNAWAVE_FLEET_SCHEMA_UNSUPPORTED")
      profile <- reference("serverProfileId")
      profileRevision <- reference("serverProfileRevisionId")
      profileNumber <- number("serverProfileRevisionNumber")
      profileHash <- text("serverProfileRevisionHash")
      inventoryProfile <- reference("inventoryConfigProfileId")
      externalProfile <- reference("externalConfigProfileId")
      configurationProfile <- reference("configurationProfileId")
      configRevision <- reference("configRevisionId")
      configNumber <- number("configRevisionNumber")
      configHash <- text("configRevisionHash")
      rawInbounds <- cursor.get[List[String]]("activeInboundIds").left.map(_ => Invalid)
      inbounds <- rawInbounds.traverse(uuid)
      port <- number("nodePort")
      cidrs <- cursor.get[List[String]]("panelCidrs").left.map(_ => Invalid)
      stateCode <- text("desiredNodeState")
      state <- Either.fromOption(IntegrationDesiredNodeState.fromCode(stateCode), Invalid)
      content <- FleetDesiredContent(profile, profileRevision, profileNumber, profileHash, inventoryProfile,
        externalProfile, configurationProfile, configRevision, configNumber, configHash, inbounds, port,
        cidrs, state).normalized
    } yield content
  }
}

/** An immutable desired configuration. Nothing updates or deletes it; the fleet points at it. */
final case class RemnawaveFleetRevision(id: UUID, organizationId: UUID, fleetId: UUID, number: Int,
  schemaVersion: Int, contentHash: String, content: FleetDesiredContent, createdBy: UUID, createdAt: Instant)

/** One node this fleet owns the desired state of. Identity is the bound inventory node and resource
  * pair, never a name or an address.
  */
final case class RemnawaveFleetMembership(id: UUID, organizationId: UUID, fleetId: UUID, integrationId: UUID,
  inventoryNodeId: UUID, resourceId: UUID, version: Long, createdBy: UUID, createdAt: Instant,
  removedAt: Option[Instant] = None) {
  def active: Boolean = removedAt.isEmpty
}

/** Whether the desired configuration is met. Deliberately separate from health: a server that is
  * offline is not a server that is configured differently.
  */
sealed abstract class FleetCompliance(val code: String)
object FleetCompliance {
  /** Every required dimension was proven equal to the desired revision by fresh evidence. */
  case object Compliant extends FleetCompliance("COMPLIANT")
  /** Evidence was sufficient and something differs. */
  case object Drifted extends FleetCompliance("DRIFTED")
  /** Compliance could not be proven. Never treated as compliant. */
  case object Unknown extends FleetCompliance("UNKNOWN")
  /** A structural problem makes the comparison meaningless until it is fixed. */
  case object Blocked extends FleetCompliance("BLOCKED")
  val All: List[FleetCompliance] = List(Compliant, Drifted, Unknown, Blocked)
  def fromCode(code: String): Option[FleetCompliance] = All.find(_.code == code)
}

/** Whether the node is doing its job, judged against the operational state the fleet intends. */
sealed abstract class FleetHealth(val code: String)
object FleetHealth {
  case object Healthy extends FleetHealth("HEALTHY")
  case object Degraded extends FleetHealth("DEGRADED")
  case object Unknown extends FleetHealth("UNKNOWN")
  val All: List[FleetHealth] = List(Healthy, Degraded, Unknown)
  def fromCode(code: String): Option[FleetHealth] = All.find(_.code == code)
}

/** Why a member is not compliant. Typed, so the dashboard and a later rollout read the same facts. */
sealed abstract class FleetDriftReason(val code: String)
object FleetDriftReason {
  case object ServerProfileAssignmentMismatch extends FleetDriftReason("SERVER_PROFILE_ASSIGNMENT_MISMATCH")
  case object ServerProfileContentDrift extends FleetDriftReason("SERVER_PROFILE_CONTENT_DRIFT")
  case object ServerProfileUnobserved extends FleetDriftReason("SERVER_PROFILE_UNOBSERVED")
  case object ConfigProfileMismatch extends FleetDriftReason("CONFIG_PROFILE_MISMATCH")
  case object ConfigRevisionDrift extends FleetDriftReason("CONFIG_REVISION_DRIFT")
  case object ConfigRevisionUnobserved extends FleetDriftReason("CONFIG_REVISION_UNOBSERVED")
  case object ActiveInboundsDrift extends FleetDriftReason("ACTIVE_INBOUNDS_DRIFT")
  case object ActiveInboundsUnobserved extends FleetDriftReason("ACTIVE_INBOUNDS_UNOBSERVED")
  case object DesiredNodeStateDrift extends FleetDriftReason("DESIRED_NODE_STATE_DRIFT")
  case object ActualNodeStateDrift extends FleetDriftReason("ACTUAL_NODE_STATE_DRIFT")
  case object NodePortDrift extends FleetDriftReason("NODE_PORT_DRIFT")
  case object PanelCidrDrift extends FleetDriftReason("PANEL_CIDR_DRIFT")
  case object LocalInstallationDrift extends FleetDriftReason("LOCAL_INSTALLATION_DRIFT")
  case object LocalObservationUnavailable extends FleetDriftReason("LOCAL_OBSERVATION_UNAVAILABLE")
  case object LocalManagementUnavailable extends FleetDriftReason("LOCAL_MANAGEMENT_UNAVAILABLE")
  case object BindingMissing extends FleetDriftReason("BINDING_MISSING")
  case object InventoryMissing extends FleetDriftReason("INVENTORY_MISSING")
  case object InventoryStale extends FleetDriftReason("INVENTORY_STALE")
  case object ResourceUnavailable extends FleetDriftReason("RESOURCE_UNAVAILABLE")
  case object ServerProfileUnavailable extends FleetDriftReason("SERVER_PROFILE_UNAVAILABLE")
  case object ConfigProfileUnavailable extends FleetDriftReason("CONFIG_PROFILE_UNAVAILABLE")
  case object ApiContractUnconfirmed extends FleetDriftReason("API_CONTRACT_UNCONFIRMED")
  val All: List[FleetDriftReason] = List(ServerProfileAssignmentMismatch, ServerProfileContentDrift,
    ServerProfileUnobserved, ConfigProfileMismatch, ConfigRevisionDrift, ConfigRevisionUnobserved,
    ActiveInboundsDrift, ActiveInboundsUnobserved, DesiredNodeStateDrift, ActualNodeStateDrift, NodePortDrift,
    PanelCidrDrift, LocalInstallationDrift, LocalObservationUnavailable, LocalManagementUnavailable,
    BindingMissing, InventoryMissing, InventoryStale, ResourceUnavailable, ServerProfileUnavailable,
    ConfigProfileUnavailable, ApiContractUnconfirmed)
  def fromCode(code: String): Option[FleetDriftReason] = All.find(_.code == code)
}

/** Why a member is not healthy. Never mixed with the compliance reasons. */
sealed abstract class FleetHealthReason(val code: String)
object FleetHealthReason {
  case object NodeDisconnected extends FleetHealthReason("NODE_DISCONNECTED")
  case object NodeDisabledUnexpectedly extends FleetHealthReason("NODE_DISABLED_UNEXPECTEDLY")
  case object NodeEnabledUnexpectedly extends FleetHealthReason("NODE_ENABLED_UNEXPECTEDLY")
  case object LocalContainerNotRunning extends FleetHealthReason("LOCAL_CONTAINER_NOT_RUNNING")
  case object LocalPortNotListening extends FleetHealthReason("LOCAL_PORT_NOT_LISTENING")
  case object LocalContainerUnstable extends FleetHealthReason("LOCAL_CONTAINER_UNSTABLE")
  case object LocalObservationFailed extends FleetHealthReason("LOCAL_OBSERVATION_FAILED")
  case object InventoryStale extends FleetHealthReason("INVENTORY_STALE")
  case object InventoryMissing extends FleetHealthReason("INVENTORY_MISSING")
  val All: List[FleetHealthReason] = List(NodeDisconnected, NodeDisabledUnexpectedly, NodeEnabledUnexpectedly,
    LocalContainerNotRunning, LocalPortNotListening, LocalContainerUnstable, LocalObservationFailed,
    InventoryStale, InventoryMissing)
  def fromCode(code: String): Option[FleetHealthReason] = All.find(_.code == code)
}

/** What would stop a later rollout from touching this member. Stage25D computes it and acts on none of it. */
sealed abstract class FleetRolloutBlocker(val code: String)
object FleetRolloutBlocker {
  case object NoTrustedSsh extends FleetRolloutBlocker("NO_TRUSTED_SSH")
  case object NoManagedLocalInstallation extends FleetRolloutBlocker("NO_MANAGED_LOCAL_INSTALLATION")
  case object ApiContractUnconfirmed extends FleetRolloutBlocker("API_CONTRACT_UNCONFIRMED")
  case object BindingInvalid extends FleetRolloutBlocker("BINDING_INVALID")
  case object UnknownRemoteState extends FleetRolloutBlocker("UNKNOWN_REMOTE_STATE")
  case object ActiveConflictingOperation extends FleetRolloutBlocker("ACTIVE_CONFLICTING_OPERATION")
  case object ReferencedObjectUnavailable extends FleetRolloutBlocker("REFERENCED_OBJECT_UNAVAILABLE")
  val All: List[FleetRolloutBlocker] = List(NoTrustedSsh, NoManagedLocalInstallation, ApiContractUnconfirmed,
    BindingInvalid, UnknownRemoteState, ActiveConflictingOperation, ReferencedObjectUnavailable)
  def fromCode(code: String): Option[FleetRolloutBlocker] = All.find(_.code == code)
}

/** A derived read model, bound to the exact desired revision and membership version it was computed
  * for. It is a cache: deleting it loses no desired state.
  */
final case class RemnawaveFleetNodeAssessment(id: UUID, organizationId: UUID, fleetId: UUID,
  fleetRevisionId: UUID, membershipId: UUID, membershipVersion: Long, inventoryNodeId: UUID, resourceId: UUID,
  compliance: FleetCompliance, health: FleetHealth, driftReasons: List[FleetDriftReason],
  healthReasons: List[FleetHealthReason], rolloutBlockers: List[FleetRolloutBlocker],
  inventoryObservedAt: Option[Instant], serverObservedAt: Option[Instant], localObservedAt: Option[Instant],
  computedAt: Instant, assessmentVersion: Int = RemnawaveFleetNodeAssessment.Version) {
  /** The oldest piece of evidence the verdict rests on, which is what makes the verdict stale. */
  def oldestEvidenceAt: Option[Instant] =
    List(inventoryObservedAt, serverObservedAt, localObservedAt).flatten.sortBy(_.toEpochMilli).headOption
}
object RemnawaveFleetNodeAssessment { val Version = 1 }

/** Counts for one fleet, derived from its members' assessments. */
final case class RemnawaveFleetSummary(totalNodes: Int, compliant: Int, drifted: Int, unknown: Int, blocked: Int,
  healthy: Int, degraded: Int, healthUnknown: Int, assessed: Int, desiredRevisionNumber: Option[Int],
  lastAssessmentAt: Option[Instant], oldestEvidenceAt: Option[Instant])
