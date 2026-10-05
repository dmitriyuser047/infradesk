package ru.bitec.app.ops
package domain.integration

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.parser.parse
import java.time.Instant
import java.util.UUID
import scala.util.Try
import domain.provisioning.ServerProfileDiff

/** OCI index, platform manifest and image config are distinct identities. The config digest is
  * Docker's actual Image ID; checking a tag or RepoDigests alone does not prove the running binary. */
final case class NodeReleasePlatform(platform: String, manifestDigest: String, configDigest: String,
  compressedBytes: Long, provenanceManifestDigest: String, provenanceBlobDigest: String) {
  def valid: Boolean = Set("linux/amd64", "linux/arm64")(platform) && compressedBytes > 0 &&
    List(manifestDigest, configDigest, provenanceManifestDigest, provenanceBlobDigest).forall(NodeRelease.digest)
}

final case class NodeRelease(releaseId: String, nodeVersion: String, imageRepository: String,
  manifestDigest: String, platforms: List[NodeReleasePlatform], minimumPanelVersion: String,
  maximumPanelVersionExclusive: String, apiGeneration: String, sourceCommit: String, releasedAt: Instant,
  status: String, catalogVersion: Int) {
  def imageReference: String = s"$imageRepository@$manifestDigest"
  def forPlatform(platform: String): Option[NodeReleasePlatform] = platforms.find(_.platform == platform)
  def valid: Boolean = releaseId == s"node-$nodeVersion" && NodeRelease.version(nodeVersion).nonEmpty &&
    imageRepository == "ghcr.io/remnawave/node" && NodeRelease.digest(manifestDigest) &&
    sourceCommit.matches("[0-9a-f]{40}") && platforms.nonEmpty && platforms.forall(_.valid) &&
    platforms.map(_.platform).distinct.size == platforms.size && catalogVersion > 0 &&
    Set("AVAILABLE", "DEPRECATED", "BLOCKED")(status) &&
    Set("PROFILE_PUBKEY", "PROFILE_SECRET_KEY")(apiGeneration) &&
    NodeRelease.version(minimumPanelVersion).nonEmpty && NodeRelease.version(maximumPanelVersionExclusive).nonEmpty
}
object NodeRelease {
  def digest(value: String): Boolean = value.matches("sha256:[0-9a-f]{64}")
  def immutableReference(value: String): Boolean = value.matches("ghcr\\.io/remnawave/node@sha256:[0-9a-f]{64}")
  def version(value: String): Option[(Int, Int, Int)] = value.stripPrefix("v").split("\\.").toList match {
    case List(a, b, c) if List(a, b, c).forall(_.matches("0|[1-9][0-9]{0,3}")) =>
      for { x <- a.toIntOption; y <- b.toIntOption; z <- c.toIntOption } yield (x, y, z)
    case _ => None
  }
}

final case class NodeReleaseCompatibility(state: String, reasons: List[String]) {
  def compatible: Boolean = state == "COMPATIBLE"
}

/** Backend-owned, reviewed artifacts only. The existing Panel metadata catalog remains the source
  * of reviewed Panel commits; no additional detector or network discovery is introduced here. */
object RemnawaveNodeReleaseCatalog {
  import NodeReleaseJson._
  private def resource(path: String): String = {
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(throw new IllegalStateException("Missing release catalog"))
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString finally stream.close()
  }
  lazy val releases: List[NodeRelease] = {
    val rows = parse(resource("/integration/remnawave/node-image-releases.json")).flatMap(_.as[List[NodeRelease]])
      .fold(_ => throw new IllegalStateException("Invalid node release catalog"), identity)
    require(rows.nonEmpty && rows.forall(_.valid) && rows.map(_.releaseId).distinct.size == rows.size,
      "Invalid node release catalog")
    rows
  }
  private lazy val panelReleases = parse(resource("/integration/remnawave/node-api-releases.json")).toOption
    .flatMap(_.asArray).getOrElse(throw new IllegalStateException("Invalid Panel release catalog")).flatMap { row =>
      val c = row.hcursor
      for { version <- c.get[String]("version").toOption; commit <- c.get[String]("commit").toOption;
        generation <- c.get[String]("generation").toOption } yield version -> (commit, generation)
    }.toMap
  def find(id: String): Option[NodeRelease] = releases.find(_.releaseId == id)
  def compatibility(release: NodeRelease, api: NodeApiCompatibility, platform: Option[String] = None): NodeReleaseCompatibility = {
    if (!release.valid) return NodeReleaseCompatibility("INCOMPATIBLE", List("NODE_RELEASE_UNREVIEWED"))
    val panel = api.serverVersion.flatMap(v => panelReleases.get(v.stripPrefix("v")))
    val unconfirmed = !api.provisioningReady || panel.isEmpty ||
      !panel.exists(p => api.sourceCommit.contains(p._1) && api.apiGeneration.contains(p._2))
    val order = implicitly[Ordering[(Int, Int, Int)]]
    val version = api.serverVersion.flatMap(NodeRelease.version)
    val reasons = List(
      Option.when(unconfirmed)("CUSTOM_PANEL_BUILD_UNCONFIRMED"),
      Option.when(!release.valid || release.status == "BLOCKED")("NODE_RELEASE_UNREVIEWED"),
      Option.when(api.apiGeneration.exists(_ != release.apiGeneration))("API_GENERATION_UNSUPPORTED"),
      Option.when(version.exists(v => order.lt(v, NodeRelease.version(release.minimumPanelVersion).get)))("PANEL_TOO_OLD"),
      Option.when(version.exists(v => order.gteq(v, NodeRelease.version(release.maximumPanelVersionExclusive).get)))("PANEL_TOO_NEW"),
      Option.when(platform.exists(p => release.forPlatform(p).isEmpty))("ARCHITECTURE_UNSUPPORTED")).flatten
    NodeReleaseCompatibility(if (unconfirmed) "UNCONFIRMED" else if (reasons.nonEmpty) "INCOMPATIBLE" else "COMPATIBLE", reasons)
  }
  def default(api: NodeApiCompatibility): Option[NodeRelease] = releases.filter(r => r.status == "AVAILABLE" &&
    compatibility(r, api).compatible).sortBy(r => NodeRelease.version(r.nodeVersion).get).lastOption
  def identify(imageId: String, platform: String): Option[NodeRelease] = releases.find(_.forPlatform(platform)
    .exists(_.configDigest == imageId))
  def managedReferences: List[String] = (releases.flatMap(r => r.imageReference ::
    r.platforms.map(p => s"${r.imageRepository}@${p.manifestDigest}")) ++
    List("remnawave/node:2.8.0", "remnawave/node:3.4.1")).distinct
}

/** Narrow local evidence; never Docker's full inspect, environment, registration payload or logs. */
final case class NodeImageObservation(managedFiles: Boolean, configuredImage: Option[String],
  actualImageId: Option[String], repoDigests: List[String], platform: Option[String], containerId: Option[String],
  containerCreatedAt: Option[Instant], containerStartedAt: Option[Instant], containerRunning: Boolean,
  restartCount: Int, portListening: Boolean, stable: Boolean, composeHash: Option[String],
  markerHash: Option[String], imageBytes: Long, freeBytes: Long) {
  def healthy: Boolean = managedFiles && containerRunning && portListening && stable && restartCount == 0
  def matches(release: NodeRelease): Boolean = platform.flatMap(release.forPlatform).exists(p =>
    actualImageId.contains(p.configDigest) && configuredImage.exists(ref =>
      ref == release.imageReference || ref == s"${release.imageRepository}@${p.manifestDigest}" ||
        ref == s"remnawave/node:${release.nodeVersion}"))
}
final case class FleetNodeImageObservation(organizationId: UUID, fleetId: UUID, membershipId: UUID,
  membershipVersion: Long, inventoryNodeId: UUID, resourceId: UUID, onboardingId: UUID,
  sourceConnectionId: UUID, sourceUpdatedAt: Instant, observation: NodeImageObservation, observedAt: Instant)

final case class FleetNodeReleaseRevision(id: UUID, organizationId: UUID, integrationId: UUID, fleetId: UUID,
  number: Int, release: NodeRelease, panel: NodeApiCompatibility, createdBy: UUID, createdAt: Instant) {
  // PostgreSQL timestamps have microsecond precision. Creation time is immutable metadata,
  // not part of the software content hash, so a persisted round trip cannot change this pin.
  def hash: String = ServerProfileDiff.hashText(NodeReleaseJson.releaseRevisionEncoder(this)
    .mapObject(_.remove("createdAt")).noSpaces)
}

sealed abstract class NodeUpgradePhase(val code: String)
object NodeUpgradePhase {
  case object Validate extends NodeUpgradePhase("VALIDATE")
  case object PrepareCanary extends NodeUpgradePhase("PREPARE_CANARY")
  case object PrefetchCanary extends NodeUpgradePhase("PREFETCH_CANARY")
  case object UpgradeCanary extends NodeUpgradePhase("UPGRADE_CANARY")
  case object VerifyCanary extends NodeUpgradePhase("VERIFY_CANARY")
  case object PrefetchWave extends NodeUpgradePhase("PREFETCH_WAVE")
  case object UpgradeWave extends NodeUpgradePhase("UPGRADE_WAVE")
  case object VerifyWave extends NodeUpgradePhase("VERIFY_WAVE")
  case object FinalVerify extends NodeUpgradePhase("FINAL_VERIFY")
  case object Rollback extends NodeUpgradePhase("ROLLBACK")
  case object Complete extends NodeUpgradePhase("COMPLETE")
  val All = List(Validate, PrepareCanary, PrefetchCanary, UpgradeCanary, VerifyCanary, PrefetchWave,
    UpgradeWave, VerifyWave, FinalVerify, Rollback, Complete)
  def fromCode(code: String): Option[NodeUpgradePhase] = All.find(_.code == code)
}

final case class NodeUpgradeMemberPlan(membershipId: UUID, membershipVersion: Long, inventoryNodeId: UUID,
  externalNodeId: UUID, resourceId: UUID, nodeName: String, onboardingId: UUID, originalImageReference: String,
  sourceConnectionId: UUID, sourceUpdatedAt: Instant, wave: Int, position: Int, skipped: Boolean,
  baseline: NodeImageObservation, previousReleaseId: Option[String], previousImageReference: Option[String],
  reportedVersion: Option[String]) {
  def rollbackAvailable: Boolean = previousImageReference.exists(NodeRelease.immutableReference) &&
    previousReleaseId.nonEmpty && baseline.composeHash.nonEmpty && baseline.markerHash.nonEmpty
}
final case class NodeUpgradeSnapshot(schemaVersion: Int, organizationId: UUID, integrationId: UUID, fleetId: UUID,
  fleetRevisionId: UUID, fleetRevisionHash: String, integrationPin: Instant, configuration: FleetDesiredContent,
  releaseRevisionId: UUID, releaseRevisionHash: String, target: NodeRelease, panel: NodeApiCompatibility,
  canaryMemberIds: List[UUID], waveSize: Int, pauseAfterCanary: Boolean, automaticRollback: Boolean,
  members: List[NodeUpgradeMemberPlan]) {
  def hash: String = ServerProfileDiff.hashText(NodeReleaseJson.snapshotEncoder(this).noSpaces)
  def waveCount: Int = members.filterNot(_.skipped).map(_.wave).maxOption.fold(0)(_ + 1)
}
final case class RemnawaveFleetUpgradeRun(id: UUID, organizationId: UUID, integrationId: UUID, fleetId: UUID,
  releaseRevisionId: UUID, requestId: Option[UUID], state: FleetRolloutState, phase: NodeUpgradePhase,
  snapshot: NodeUpgradeSnapshot, snapshotHash: String, currentWave: Int, waveCount: Int, createdBy: UUID,
  createdAt: Instant, expiresAt: Instant, startedAt: Option[Instant], finishedAt: Option[Instant],
  failureCode: Option[String], pauseReason: Option[String], pausedAt: Option[Instant],
  pauseRequestedAt: Option[Instant], rollbackRequestedAt: Option[Instant], rollbackScope: FleetRollbackScope,
  rollbackIncomplete: Boolean, nextRunAt: Instant, phaseStartedAt: Instant, claimToken: Option[UUID],
  version: Long, updatedAt: Instant)
final case class RemnawaveFleetUpgradeMember(id: UUID, organizationId: UUID, upgradeId: UUID,
  membershipId: UUID, resourceId: UUID, wave: Int, position: Int, state: FleetRolloutMemberState,
  failureCode: Option[String], localVerifiedAt: Option[Instant], panelVerifiedAt: Option[Instant],
  lastObservation: Option[NodeImageObservation], startedAt: Option[Instant], finishedAt: Option[Instant])
final case class RemnawaveFleetUpgradeAction(id: UUID, organizationId: UUID, upgradeId: UUID, memberId: UUID,
  kind: String, rollback: Boolean, state: FleetActionState, targetReference: String,
  failureCode: Option[String], startedAt: Instant, finishedAt: Option[Instant])

object NodeReleaseJson {
  private def closed[A](decoder: Decoder[A], encoder: Encoder[A]): Decoder[A] = Decoder.instance { cursor =>
    decoder(cursor).flatMap(value => Either.cond(cursor.value.asObject.exists(obj =>
      encoder(value).asObject.exists(_.keys.toSet == obj.keys.toSet)), value,
      io.circe.DecodingFailure("Unexpected node release fields", cursor.history)))
  }
  implicit val instantCodec: Codec[Instant] = Codec.from(Decoder.decodeString.emap(v =>
    Try(Instant.parse(v)).toEither.left.map(_ => "Invalid timestamp")), Encoder.encodeString.contramap(_.toString))
  implicit val capabilityEncoder: Encoder[NodeProvisioningCapability] = Encoder.encodeString.contramap(_.code)
  implicit val capabilityDecoder: Decoder[NodeProvisioningCapability] = Decoder.decodeString.emap(code =>
    List(NodeProvisioningCapability.Inventory, NodeProvisioningCapability.Status, NodeProvisioningCapability.Create,
      NodeProvisioningCapability.InstallationData, NodeProvisioningCapability.ConfigProfile,
      NodeProvisioningCapability.CreateReconciliation).find(_.code == code).toRight("Invalid capability"))
  implicit val panelEncoder: Encoder[NodeApiCompatibility] = Encoder.forProduct5("serverVersion", "apiGeneration", "sourceCommit", "capabilities", "blocker")(value => (value.serverVersion, value.apiGeneration, value.sourceCommit, value.capabilities.toList.sortBy(_.code), value.blocker))
  implicit val panelDecoder: Decoder[NodeApiCompatibility] = closed(Decoder.forProduct5("serverVersion", "apiGeneration", "sourceCommit", "capabilities", "blocker")(NodeApiCompatibility.apply), panelEncoder)
  implicit val platformEncoder: Encoder[NodeReleasePlatform] = Encoder.forProduct6("platform", "manifestDigest", "configDigest", "compressedBytes", "provenanceManifestDigest", "provenanceBlobDigest")(value => (value.platform, value.manifestDigest, value.configDigest, value.compressedBytes, value.provenanceManifestDigest, value.provenanceBlobDigest))
  implicit val platformDecoder: Decoder[NodeReleasePlatform] = closed(Decoder.forProduct6("platform", "manifestDigest", "configDigest", "compressedBytes", "provenanceManifestDigest", "provenanceBlobDigest")(NodeReleasePlatform.apply), platformEncoder)
  implicit val releaseEncoder: Encoder[NodeRelease] = Encoder.forProduct12("releaseId", "nodeVersion", "imageRepository", "manifestDigest", "platforms", "minimumPanelVersion", "maximumPanelVersionExclusive", "apiGeneration", "sourceCommit", "releasedAt", "status", "catalogVersion")(value => (value.releaseId, value.nodeVersion, value.imageRepository, value.manifestDigest, value.platforms, value.minimumPanelVersion, value.maximumPanelVersionExclusive, value.apiGeneration, value.sourceCommit, value.releasedAt, value.status, value.catalogVersion))
  implicit val releaseDecoder: Decoder[NodeRelease] = closed(Decoder.forProduct12("releaseId", "nodeVersion", "imageRepository", "manifestDigest", "platforms", "minimumPanelVersion", "maximumPanelVersionExclusive", "apiGeneration", "sourceCommit", "releasedAt", "status", "catalogVersion")(NodeRelease.apply).emap(r => Either.cond(r.valid, r, "Invalid release")), releaseEncoder)
  implicit val observationEncoder: Encoder[NodeImageObservation] = Encoder.forProduct16("managedFiles", "configuredImage", "actualImageId", "repoDigests", "platform", "containerId", "containerCreatedAt", "containerStartedAt", "containerRunning", "restartCount", "portListening", "stable", "composeHash", "markerHash", "imageBytes", "freeBytes")(value => (value.managedFiles, value.configuredImage, value.actualImageId, value.repoDigests, value.platform, value.containerId, value.containerCreatedAt, value.containerStartedAt, value.containerRunning, value.restartCount, value.portListening, value.stable, value.composeHash, value.markerHash, value.imageBytes, value.freeBytes))
  implicit val observationDecoder: Decoder[NodeImageObservation] = closed(Decoder.forProduct16("managedFiles", "configuredImage", "actualImageId", "repoDigests", "platform", "containerId", "containerCreatedAt", "containerStartedAt", "containerRunning", "restartCount", "portListening", "stable", "composeHash", "markerHash", "imageBytes", "freeBytes")(NodeImageObservation.apply).emap(o => Either.cond(
    o.actualImageId.forall(NodeRelease.digest) && o.configuredImage.forall(v => NodeRelease.immutableReference(v) || Set("remnawave/node:2.8.0", "remnawave/node:3.4.1")(v)) &&
    o.repoDigests.size <= 16 && o.repoDigests.forall(NodeRelease.immutableReference) && o.platform.forall(Set("linux/amd64", "linux/arm64")) &&
    o.containerId.forall(_.matches("[0-9a-f]{64}")) && List(o.composeHash, o.markerHash).forall(_.forall(_.matches("[0-9a-f]{64}"))) &&
    o.restartCount >= 0 && o.imageBytes >= 0 && o.freeBytes >= 0, o, "Invalid image observation")), observationEncoder)
  implicit val releaseRevisionEncoder: Encoder[FleetNodeReleaseRevision] = Encoder.forProduct9("id", "organizationId", "integrationId", "fleetId", "number", "release", "panel", "createdBy", "createdAt")(value => (value.id, value.organizationId, value.integrationId, value.fleetId, value.number, value.release, value.panel, value.createdBy, value.createdAt))
  implicit val configurationEncoder: Encoder[FleetDesiredContent] = Encoder.instance(_.json)
  implicit val configurationDecoder: Decoder[FleetDesiredContent] = Decoder.decodeJson.emap(FleetDesiredContentCodec.decode)
  implicit val memberPlanEncoder: Encoder[NodeUpgradeMemberPlan] = Encoder.forProduct17("membershipId", "membershipVersion", "inventoryNodeId", "externalNodeId", "resourceId", "nodeName", "onboardingId", "originalImageReference", "sourceConnectionId", "sourceUpdatedAt", "wave", "position", "skipped", "baseline", "previousReleaseId", "previousImageReference", "reportedVersion")(value => (value.membershipId, value.membershipVersion, value.inventoryNodeId, value.externalNodeId, value.resourceId, value.nodeName, value.onboardingId, value.originalImageReference, value.sourceConnectionId, value.sourceUpdatedAt, value.wave, value.position, value.skipped, value.baseline, value.previousReleaseId, value.previousImageReference, value.reportedVersion))
  implicit val memberPlanDecoder: Decoder[NodeUpgradeMemberPlan] = closed(Decoder.forProduct17("membershipId", "membershipVersion", "inventoryNodeId", "externalNodeId", "resourceId", "nodeName", "onboardingId", "originalImageReference", "sourceConnectionId", "sourceUpdatedAt", "wave", "position", "skipped", "baseline", "previousReleaseId", "previousImageReference", "reportedVersion")(NodeUpgradeMemberPlan.apply), memberPlanEncoder)
  implicit val snapshotEncoder: Encoder[NodeUpgradeSnapshot] = Encoder.forProduct17("schemaVersion", "organizationId", "integrationId", "fleetId", "fleetRevisionId", "fleetRevisionHash", "integrationPin", "configuration", "releaseRevisionId", "releaseRevisionHash", "target", "panel", "canaryMemberIds", "waveSize", "pauseAfterCanary", "automaticRollback", "members")(value => (value.schemaVersion, value.organizationId, value.integrationId, value.fleetId, value.fleetRevisionId, value.fleetRevisionHash, value.integrationPin, value.configuration, value.releaseRevisionId, value.releaseRevisionHash, value.target, value.panel, value.canaryMemberIds, value.waveSize, value.pauseAfterCanary, value.automaticRollback, value.members))
  implicit val snapshotDecoder: Decoder[NodeUpgradeSnapshot] = closed(Decoder.forProduct17("schemaVersion", "organizationId", "integrationId", "fleetId", "fleetRevisionId", "fleetRevisionHash", "integrationPin", "configuration", "releaseRevisionId", "releaseRevisionHash", "target", "panel", "canaryMemberIds", "waveSize", "pauseAfterCanary", "automaticRollback", "members")(NodeUpgradeSnapshot.apply).emap { s =>
    Either.cond(s.schemaVersion == 1 && s.target.valid && s.waveSize >= 1 && s.waveSize <= 25 &&
      s.members.nonEmpty && s.members.size <= RemnawaveFleet.MaxMembers &&
      s.members.map(_.membershipId).distinct.size == s.members.size &&
      s.members.map(_.resourceId).distinct.size == s.members.size && s.members.map(_.position).sorted == s.members.indices.toList &&
      s.canaryMemberIds.distinct.size == s.canaryMemberIds.size && s.canaryMemberIds.forall(id => s.members.exists(m => m.membershipId == id && !m.skipped && m.wave == 0)) &&
      List(s.fleetRevisionHash, s.releaseRevisionHash).forall(_.matches("[0-9a-f]{64}")) &&
      s.members.forall(m => m.membershipVersion > 0 && m.wave >= 0 && m.position >= 0 &&
        m.previousImageReference.forall(NodeRelease.immutableReference) && m.reportedVersion.forall(v => NodeRelease.version(v).nonEmpty) &&
        m.baseline.actualImageId.exists(NodeRelease.digest)) &&
      s.members.filterNot(_.skipped).forall(m => s.target.forPlatform(m.baseline.platform.getOrElse("")).nonEmpty),
      s, "Invalid upgrade snapshot")
  }, snapshotEncoder)
}
