package ru.bitec.app.ops
package domain.integration

import domain.provisioning.{ProvisioningRunState, ServerProfileContent}
import io.circe.Json
import java.time.Instant
import java.util.UUID
import scala.util.Try

sealed abstract class OnboardingPhase(val code: String, val mutation: Boolean = false)
object OnboardingPhase {
  case object Validate extends OnboardingPhase("VALIDATE")
  case object PrepareServer extends OnboardingPhase("PREPARE_SERVER", true)
  case object CreateNode extends OnboardingPhase("CREATE_NODE", true)
  case object GetInstallationData extends OnboardingPhase("GET_INSTALLATION_DATA")
  case object ConfigureFirewall extends OnboardingPhase("CONFIGURE_NODE_FIREWALL", true)
  case object InstallNode extends OnboardingPhase("INSTALL_NODE", true)
  case object StartNode extends OnboardingPhase("START_NODE", true)
  case object VerifyLocalNode extends OnboardingPhase("VERIFY_LOCAL_NODE")
  case object WaitForPanel extends OnboardingPhase("WAIT_FOR_PANEL")
  case object SyncInventory extends OnboardingPhase("SYNC_INVENTORY")
  case object BindResource extends OnboardingPhase("BIND_RESOURCE", true)
  case object SetDesiredState extends OnboardingPhase("SET_DESIRED_STATE", true)
  case object FinalVerify extends OnboardingPhase("FINAL_VERIFY")
  lazy val all: List[OnboardingPhase] = List(Validate, PrepareServer, CreateNode, GetInstallationData,
    ConfigureFirewall, InstallNode, StartNode, VerifyLocalNode, WaitForPanel, SyncInventory,
    BindResource, SetDesiredState, FinalVerify)
  def fromCode(code: String): OnboardingPhase = all.find(_.code == code).getOrElse(
    throw new IllegalArgumentException("Invalid onboarding phase"))
  def next(phase: OnboardingPhase): Option[OnboardingPhase] = all.lift(all.indexOf(phase) + 1)
}

final case class OnboardingInput(resourceId: UUID, nodeName: String, address: String, nodePort: Int,
  configProfileId: UUID, activeInboundIds: List[UUID], panelCidrs: List[String], desiredState: String = "ENABLED") {
  def normalized: Either[String, OnboardingInput] = for {
    cidrs <- OnboardingInput.canonicalCidrs(panelCidrs)
    _ <- Either.cond(nodeName == nodeName.trim && nodeName.length >= 3 && nodeName.length <= 30 &&
      !nodeName.exists(_.isControl) && OnboardingInput.validAddress(address) && nodePort > 0 && nodePort <= 65535 &&
      activeInboundIds.nonEmpty && activeInboundIds.size <= 256 && activeInboundIds.distinct == activeInboundIds &&
      desiredState == "ENABLED", (), "REMNAWAVE_ONBOARDING_INVALID_INPUT")
  } yield copy(panelCidrs = cidrs)
}
object OnboardingInput {
  def validAddress(value: String): Boolean = value.length >= 2 && value.length <= 253 &&
    (value.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*") ||
      (value.contains(":") && value.matches("[0-9a-fA-F:]+") &&
        Try(java.net.InetAddress.getByName(value)).toOption.exists(_.isInstanceOf[java.net.Inet6Address])))
  def canonicalCidrs(values: List[String]): Either[String, List[String]] = {
    val parsed = values.map(ServerProfileContent.canonicalFirewallSource)
    if (values.isEmpty || values.size > 32 || values.exists(!_.contains("/")) || parsed.exists(_.isLeft)) Left("REMNAWAVE_ONBOARDING_CIDR_INVALID")
    else {
      val result = parsed.flatMap(_.toOption)
      if (result.exists(v => v == "ANY" || v == "any" || !v.contains("/") || v.endsWith("/0")) || result.distinct.size != result.size)
        Left("REMNAWAVE_ONBOARDING_CIDR_INVALID") else Right(result.sorted)
    }
  }
}

/** Every identity and release decision is pinned; this record cannot contain a secret. */
final case class OnboardingSnapshot(input: OnboardingInput, integrationUpdatedAt: Instant, integrationSecretId: UUID,
  connectionId: UUID, connectionUpdatedAt: Instant, profileId: UUID, revisionId: UUID, revisionNumber: Int,
  assignmentId: UUID, assignmentVersion: Long, revisionHash: String, baselinePlanId: UUID,
  baselineNeeded: Boolean, compatibility: NodeApiCompatibility, imageReference: String,
  correlationId: UUID, serverName: String, serverProfileName: String, configProfileName: String,
  inboundNames: List[String], changes: List[String], warnings: List[String], blockers: List[String]) {
  def intent: NodeCreateIntent = NodeCreateIntent(input.nodeName, input.address, input.nodePort,
    input.configProfileId, input.activeInboundIds, correlationId)
}
final case class RemnawaveNodeOnboardingRun(id: UUID, organizationId: UUID, integrationId: UUID,
  resourceId: UUID, requestId: Option[UUID], createdBy: UUID, state: ProvisioningRunState,
  phase: OnboardingPhase, snapshot: OnboardingSnapshot, createdAt: Instant, updatedAt: Instant,
  externalNodeId: Option[UUID] = None, baselineRunId: Option[UUID] = None, syncSessionId: Option[UUID] = None,
  failureCode: Option[String] = None, startedAt: Option[Instant] = None, finishedAt: Option[Instant] = None,
  claimToken: Option[UUID] = None, claimDeadline: Option[Instant] = None)
object RemnawaveNodeOnboardingRun {
  /** Recovery is a new approval, limited to the known pre-install firewall failure boundary. */
  def firewallRecovery(previous: List[RemnawaveNodeOnboardingRun], integration: UUID,
    input: OnboardingInput, image: String, connection: UUID): Either[String,Option[RemnawaveNodeOnboardingRun]] =
    previous match {
      case Nil => Right(None)
      case r :: Nil if r.integrationId==integration && r.state==ProvisioningRunState.Failed &&
        r.phase==OnboardingPhase.ConfigureFirewall && r.externalNodeId.nonEmpty && r.snapshot.input==input &&
        r.snapshot.imageReference==image && r.snapshot.connectionId==connection => Right(Some(r))
      case _ => Left("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW")
    }

  /** One request ID per onboarding baseline. Starting the approved child again - after a crash
    * between the attach and the start - is therefore the same Stage25A request, not a second one.
    */
  def baselineRequestId(onboardingId: UUID): UUID =
    UUID.nameUUIDFromBytes((onboardingId.toString + ":baseline").getBytes(java.nio.charset.StandardCharsets.UTF_8))
}
final case class OnboardingPhaseRecord(phase: OnboardingPhase, state: String,
  startedAt: Option[Instant], finishedAt: Option[Instant], failureCode: Option[String])

object RemnawaveNodeImagePolicy {
  // Reviewed Node releases are deliberately independent of Panel patch versions.
  // Source evidence: Node commits 596f015a5c8f876dc9a9d61b6cb78d35bd8e379b / 44912631321664dbd5822e9bf8d96766ccff7c93.
  def select(api: NodeApiCompatibility): Option[String] = RemnawaveNodeReleaseCatalog.default(api).map(_.imageReference)
}

/** Same representation for persistence and safe API snapshots. Closed decoding rejects extra keys. */
object OnboardingSnapshotCodec {
  private def str(v: String) = Json.fromString(v)
  private def id(v: UUID) = str(v.toString)
  private def strings(v: List[String]) = Json.arr(v.map(str): _*)
  def encode(s: OnboardingSnapshot): Json = Json.obj(
    "resourceId" -> id(s.input.resourceId), "nodeName" -> str(s.input.nodeName), "address" -> str(s.input.address),
    "nodePort" -> Json.fromInt(s.input.nodePort), "configProfileId" -> id(s.input.configProfileId),
    "activeInboundIds" -> strings(s.input.activeInboundIds.map(_.toString)), "panelCidrs" -> strings(s.input.panelCidrs),
    "desiredState" -> str(s.input.desiredState), "integrationUpdatedAt" -> str(s.integrationUpdatedAt.toString),
    "integrationSecretId" -> id(s.integrationSecretId), "connectionId" -> id(s.connectionId),
    "connectionUpdatedAt" -> str(s.connectionUpdatedAt.toString), "profileId" -> id(s.profileId),
    "revisionId" -> id(s.revisionId), "revisionNumber" -> Json.fromInt(s.revisionNumber),
    "assignmentId" -> id(s.assignmentId), "assignmentVersion" -> Json.fromLong(s.assignmentVersion),
    "revisionHash" -> str(s.revisionHash), "baselinePlanId" -> id(s.baselinePlanId),
    "baselineNeeded" -> Json.fromBoolean(s.baselineNeeded), "serverVersion" -> s.compatibility.serverVersion.fold(Json.Null)(str),
    "apiGeneration" -> s.compatibility.apiGeneration.fold(Json.Null)(str), "sourceCommit" -> s.compatibility.sourceCommit.fold(Json.Null)(str),
    "capabilities" -> strings(s.compatibility.capabilities.toList.map(_.code).sorted),
    "compatibilityBlocker" -> s.compatibility.blocker.fold(Json.Null)(str), "imageReference" -> str(s.imageReference),
    "correlationId" -> id(s.correlationId), "serverName" -> str(s.serverName), "serverProfileName" -> str(s.serverProfileName),
    "configProfileName" -> str(s.configProfileName), "inboundNames" -> strings(s.inboundNames),
    "changes" -> strings(s.changes), "warnings" -> strings(s.warnings), "blockers" -> strings(s.blockers))
  def decode(json: Json): OnboardingSnapshot = {
    val expectedKeys = Set("resourceId", "nodeName", "address", "nodePort", "configProfileId", "activeInboundIds", "panelCidrs",
      "desiredState", "integrationUpdatedAt", "integrationSecretId", "connectionId", "connectionUpdatedAt", "profileId",
      "revisionId", "revisionNumber", "assignmentId", "assignmentVersion", "revisionHash", "baselinePlanId", "baselineNeeded",
      "serverVersion", "apiGeneration", "sourceCommit", "capabilities", "compatibilityBlocker", "imageReference", "correlationId",
      "serverName", "serverProfileName", "configProfileName", "inboundNames", "changes", "warnings", "blockers")
    require(json.asObject.exists(_.keys.toSet == expectedKeys), "Invalid onboarding snapshot")
    val c = json.hcursor
    def s(k: String): String = c.get[String](k).fold(_ => throw new IllegalArgumentException("Invalid onboarding snapshot"), identity)
    def u(k: String) = UUID.fromString(s(k))
    def list(k: String): List[String] = c.get[List[String]](k).toOption.getOrElse(throw new IllegalArgumentException("Invalid onboarding snapshot"))
    def optional(k: String) = c.get[Option[String]](k).toOption.getOrElse(throw new IllegalArgumentException("Invalid onboarding snapshot"))
    val capabilities = List(NodeProvisioningCapability.Inventory, NodeProvisioningCapability.Status,
      NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData, NodeProvisioningCapability.ConfigProfile,
      NodeProvisioningCapability.CreateReconciliation, NodeProvisioningCapability.CreateIdempotency)
    val input = OnboardingInput(u("resourceId"),s("nodeName"),s("address"),c.get[Int]("nodePort").toOption.get,
      u("configProfileId"),list("activeInboundIds").map(UUID.fromString),list("panelCidrs"),s("desiredState"))
      .normalized.fold(code => throw new IllegalArgumentException(code),identity)
    OnboardingSnapshot(input,Instant.parse(s("integrationUpdatedAt")),u("integrationSecretId"),u("connectionId"),
      Instant.parse(s("connectionUpdatedAt")),u("profileId"),u("revisionId"),c.get[Int]("revisionNumber").toOption.get,
      u("assignmentId"),c.get[Long]("assignmentVersion").toOption.get,s("revisionHash"),u("baselinePlanId"),
      c.get[Boolean]("baselineNeeded").toOption.get,NodeApiCompatibility(optional("serverVersion"),optional("apiGeneration"),
        optional("sourceCommit"),list("capabilities").map(code => capabilities.find(_.code == code).get).toSet,optional("compatibilityBlocker")),
      s("imageReference"),u("correlationId"),s("serverName"),s("serverProfileName"),s("configProfileName"),
      list("inboundNames"),list("changes"),list("warnings"),list("blockers"))
  }
}
