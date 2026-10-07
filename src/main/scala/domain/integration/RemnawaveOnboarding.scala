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
  case object DeleteNode extends OnboardingPhase("DELETE_NODE", true)
  case object ConfirmNodeDeleted extends OnboardingPhase("CONFIRM_NODE_DELETED")
  case object RetireNodeFirewall extends OnboardingPhase("RETIRE_NODE_FIREWALL", true)
  case object RetireLocalNode extends OnboardingPhase("RETIRE_LOCAL_NODE", true)
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
  def forSnapshot(s: OnboardingSnapshot): List[OnboardingPhase] = s.recovery.filter(!_.reusesNode).fold(all)(proof =>
    all.take(2) ++ (if(proof.action=="DELETE_RECREATE") List(DeleteNode,ConfirmNodeDeleted) else Nil) ++
      (if(s.needsRetirement) (if(s.lifecycleVersion>=2) List(RetireNodeFirewall) else Nil) ++ List(RetireLocalNode) else Nil) ++ all.drop(2))
  def fromCode(code: String): OnboardingPhase = (all ++ List(DeleteNode,ConfirmNodeDeleted,RetireNodeFirewall,RetireLocalNode)).find(_.code == code).getOrElse(
    throw new IllegalArgumentException("Invalid onboarding phase"))
  def next(phase: OnboardingPhase): Option[OnboardingPhase] = all.lift(all.indexOf(phase) + 1)
  def next(phase: OnboardingPhase,snapshot: OnboardingSnapshot): Option[OnboardingPhase] = {
    val phases=forSnapshot(snapshot); phases.lift(phases.indexOf(phase)+1)
  }
}

/** Fresh provider evidence is separate from the immutable history of execution attempts. */
final case class OnboardingRecovery(sourceRunId: UUID, previousExternalNodeId: Option[UUID],
  previousCorrelationId: UUID, installationOwnerId: UUID, state: String, action: String, previousPanelCidrs: Option[List[String]] = None,
  previousImageReference: Option[String] = None, localInstallation: Option[LocalInstallationObservation] = None) {
  require(OnboardingRecovery.States(state) && OnboardingRecovery.Actions(action))
  require(previousPanelCidrs.forall(values => OnboardingInput.canonicalCidrs(values).contains(values)))
  require(previousImageReference.forall(RemnawaveNodeReleaseCatalog.managedReferences.contains))
  def reusesNode: Boolean = action=="RECOVER"
}
object OnboardingRecovery {
  val States=Set("PRESENT_EXACT","PRESENT_UNHEALTHY","CONFIRMED_NOT_FOUND","PRESENT_CONFLICT","UNKNOWN")
  val Actions=Set("RECOVER","RECREATE","DELETE_RECREATE")
  def candidate(node: ProvisionedNode,intent: NodeCreateIntent): Boolean =
    node.name==intent.name || node.address==intent.address ||
      node.correlationTags.contains("ID:"+intent.correlationId.toString.replace("-","").toUpperCase(java.util.Locale.ROOT))
  def matches(node: ProvisionedNode,intent: NodeCreateIntent,id: Option[UUID]): Boolean =
    id.forall(_==node.externalId) && node.name==intent.name && node.address==intent.address && node.port.contains(intent.port) &&
      node.configProfileId.contains(intent.configProfileId) && node.activeInboundIds.toSet==intent.activeInboundIds.toSet &&
      node.correlationTags.contains("ID:"+intent.correlationId.toString.replace("-","").toUpperCase(java.util.Locale.ROOT))
}

final case class OnboardingInput(resourceId: UUID, nodeName: String, address: String, nodePort: Int,
  configProfileId: UUID, activeInboundIds: List[UUID], panelCidrs: List[String], desiredState: String = "ENABLED") {
  def normalized: Either[String, OnboardingInput] = for {
    cidrs <- OnboardingInput.canonicalCidrs(panelCidrs)
    _ <- Either.cond(nodeName == nodeName.trim && nodeName.length >= 3 && nodeName.length <= 30 &&
      !nodeName.exists(_.isControl) && OnboardingInput.validAddress(address) && nodePort > 0 && nodePort <= 65535 &&
      activeInboundIds.nonEmpty && activeInboundIds.size <= 256 && activeInboundIds.distinct == activeInboundIds &&
      desiredState == "ENABLED", (), "REMNAWAVE_ONBOARDING_INVALID_INPUT")
  } yield copy(panelCidrs = cidrs, activeInboundIds = activeInboundIds.sortBy(_.toString))
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
  inboundNames: List[String], changes: List[String], warnings: List[String], blockers: List[String],
  recovery: Option[OnboardingRecovery] = None, lifecycleVersion: Int = 1) {
  require(Set(1,2,3)(lifecycleVersion))
  def needsRetirement: Boolean = recovery.exists(!_.reusesNode) &&
    !(lifecycleVersion>=3 && recovery.flatMap(_.localInstallation).exists(_.state==LocalInstallationState.Absent))
  def installationImageReference: String = recovery.filter(_.reusesNode)
    .flatMap(_.previousImageReference).getOrElse(imageReference)
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
  /** Ownership follows the node/correlation chain, regardless of the latest terminal phase. */
  def recoveryCandidate(previous: List[RemnawaveNodeOnboardingRun], integration: UUID,
    input: OnboardingInput, image: String, connection: UUID): Either[String,Option[RemnawaveNodeOnboardingRun]] =
    previous match {
      case Nil => Right(None)
      case r :: Nil if r.integrationId==integration && r.state.terminal &&
        r.snapshot.input.copy(panelCidrs=input.panelCidrs).normalized.toOption.zip(input.normalized.toOption)
          .exists { case (previous,current) => previous==current } &&
        RemnawaveNodeReleaseCatalog.forReference(r.snapshot.installationImageReference).exists(original =>
          original.status!="BLOCKED" && RemnawaveNodeReleaseCatalog.forReference(image).exists(target =>
            target.status!="BLOCKED" && target.apiGeneration==original.apiGeneration)) &&
        r.snapshot.connectionId==connection => Right(Some(r))
      case _ => Left("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW")
    }

  def installationOwner(r: RemnawaveNodeOnboardingRun): UUID =
    r.snapshot.recovery.filter(_.reusesNode).fold(r.id)(_.installationOwnerId)

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
    "changes" -> strings(s.changes), "warnings" -> strings(s.warnings), "blockers" -> strings(s.blockers),
    "recovery" -> s.recovery.fold(Json.Null)(encodeRecovery)).deepMerge(
      if(s.lifecycleVersion==1) Json.obj() else Json.obj("lifecycleVersion" -> Json.fromInt(s.lifecycleVersion)))
  def encodeRecovery(r: OnboardingRecovery): Json = Json.obj("sourceRunId" -> id(r.sourceRunId),
    "previousExternalNodeId" -> r.previousExternalNodeId.fold(Json.Null)(id),"previousCorrelationId" -> id(r.previousCorrelationId),
    "installationOwnerId" -> id(r.installationOwnerId),"state" -> str(r.state),"action" -> str(r.action)).deepMerge(
      r.previousPanelCidrs.fold(Json.obj())(values => Json.obj("previousPanelCidrs" -> strings(values)))).deepMerge(
      r.previousImageReference.fold(Json.obj())(value => Json.obj("previousImageReference" -> str(value)))).deepMerge(
      r.localInstallation.fold(Json.obj())(value => Json.obj("localInstallation" -> encodeLocalInstallation(value))))
  def encodeLocalInstallation(value: LocalInstallationObservation): Json = Json.obj(
    "state" -> str(value.state.code),"diagnosis" -> value.diagnosis.fold(Json.Null)(d => str(d.code)),"remediation" -> str(value.remediation))
  private def decodeLocalInstallation(j: Json): LocalInstallationObservation = {
    require(j.asObject.exists(o => o.keys.toSet==Set("state","diagnosis") || o.keys.toSet==Set("state","diagnosis","remediation")),"Invalid local installation observation")
    val c=j.hcursor
    val state=c.get[String]("state").toOption.flatMap(code => LocalInstallationState.all.find(_.code==code)).getOrElse(
      throw new IllegalArgumentException("Invalid local installation state"))
    val diagnosis=c.get[Option[String]]("diagnosis").toOption.getOrElse(throw new IllegalArgumentException("Invalid local diagnosis"))
      .map(code => LocalInstallationDiagnosis.fromCode(code).getOrElse(throw new IllegalArgumentException("Invalid local diagnosis")))
    val observation=LocalInstallationObservation(state,diagnosis)
    require(!c.downField("remediation").succeeded || c.get[String]("remediation").contains(observation.remediation),"Invalid local remediation")
    observation
  }
  def decode(json: Json): OnboardingSnapshot = {
    val expectedKeys = Set("resourceId", "nodeName", "address", "nodePort", "configProfileId", "activeInboundIds", "panelCidrs",
      "desiredState", "integrationUpdatedAt", "integrationSecretId", "connectionId", "connectionUpdatedAt", "profileId",
      "revisionId", "revisionNumber", "assignmentId", "assignmentVersion", "revisionHash", "baselinePlanId", "baselineNeeded",
      "serverVersion", "apiGeneration", "sourceCommit", "capabilities", "compatibilityBlocker", "imageReference", "correlationId",
      "serverName", "serverProfileName", "configProfileName", "inboundNames", "changes", "warnings", "blockers")
    require(json.asObject.exists(o => (o.keys.toSet -- Set("recovery","lifecycleVersion")) == expectedKeys), "Invalid onboarding snapshot")
    val c = json.hcursor
    def s(k: String): String = c.get[String](k).fold(_ => throw new IllegalArgumentException("Invalid onboarding snapshot"), identity)
    def u(k: String) = UUID.fromString(s(k))
    def list(k: String): List[String] = c.get[List[String]](k).toOption.getOrElse(throw new IllegalArgumentException("Invalid onboarding snapshot"))
    def optional(k: String) = c.get[Option[String]](k).toOption.getOrElse(throw new IllegalArgumentException("Invalid onboarding snapshot"))
    val lifecycleVersion = c.downField("lifecycleVersion").focus.fold(1)(value =>
      value.asNumber.flatMap(_.toInt).getOrElse(throw new IllegalArgumentException("Invalid onboarding snapshot")))
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
      list("inboundNames"),list("changes"),list("warnings"),list("blockers"),
      c.downField("recovery").focus.filterNot(_.isNull).map { j =>
        require(j.asObject.exists(o => (o.keys.toSet -- Set("previousPanelCidrs","previousImageReference","localInstallation"))==Set("sourceRunId","previousExternalNodeId","previousCorrelationId","installationOwnerId","state","action")),"Invalid recovery snapshot")
        val r=j.hcursor
        OnboardingRecovery(UUID.fromString(r.get[String]("sourceRunId").toOption.get),
          r.get[Option[String]]("previousExternalNodeId").toOption.get.map(UUID.fromString),
          UUID.fromString(r.get[String]("previousCorrelationId").toOption.get),UUID.fromString(r.get[String]("installationOwnerId").toOption.get),
          r.get[String]("state").toOption.get,r.get[String]("action").toOption.get,
          r.get[Option[List[String]]]("previousPanelCidrs").toOption.getOrElse(throw new IllegalArgumentException("Invalid recovery snapshot")),
          r.get[Option[String]]("previousImageReference").toOption.getOrElse(throw new IllegalArgumentException("Invalid recovery snapshot")),
          r.downField("localInstallation").focus.filterNot(_.isNull).map(decodeLocalInstallation))
      },lifecycleVersion)
  }
}
