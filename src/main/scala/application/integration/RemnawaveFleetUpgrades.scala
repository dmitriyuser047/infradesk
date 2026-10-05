package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.integration._
import io.circe.Json
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

final case class NodeUpgradePreviewInput(releaseRevisionId: UUID, canaryMemberIds: List[UUID],
  waveSize: Int, automaticRollback: Boolean, pauseAfterCanary: Boolean)
final case class NodeUpgradeDetail(run: RemnawaveFleetUpgradeRun, members: List[RemnawaveFleetUpgradeMember],
  actions: List[RemnawaveFleetUpgradeAction])
final case class NodeUpgradePreview(status: String, issues: List[String], run: Option[RemnawaveFleetUpgradeRun])
trait RemnawaveFleetUpgradeApi {
  def status(org: UUID, integration: UUID, fleet: UUID): IO[Json]
  def select(actor: ActorContext, integration: UUID, fleet: UUID, releaseId: String): IO[FleetNodeReleaseRevision]
  def preview(actor: ActorContext, integration: UUID, fleet: UUID, input: NodeUpgradePreviewInput): IO[NodeUpgradePreview]
  def start(actor: ActorContext, integration: UUID, fleet: UUID, plan: UUID, request: UUID): IO[RemnawaveFleetUpgradeRun]
  def detail(org: UUID, integration: UUID, fleet: UUID, id: UUID): IO[NodeUpgradeDetail]
  def history(org: UUID, integration: UUID, fleet: UUID): IO[List[RemnawaveFleetUpgradeRun]]
  def control(actor: ActorContext, integration: UUID, fleet: UUID, id: UUID, command: String,
    scope: FleetRollbackScope = FleetRollbackScope.CurrentWave): IO[RemnawaveFleetUpgradeRun]
}
final case class NodeUpgradeSettings(enabled: Boolean = true, staleAfter: FiniteDuration = 15.minutes,
  planTtl: FiniteDuration = 24.hours, lease: FiniteDuration = 120.seconds, pollInterval: FiniteDuration = 5.seconds,
  verificationTimeout: FiniteDuration = 5.minutes) {
  require(planTtl > Duration.Zero && planTtl <= 24.hours && staleAfter > Duration.Zero && lease >= 30.seconds)
}

object NodeUpgradeAdmission {
  val RefreshRequired = "REMNAWAVE_FLEET_UPGRADE_REFRESH_REQUIRED"
  val PlanChanged = "REMNAWAVE_FLEET_UPGRADE_PLAN_CHANGED"
  val HealthGate = "REMNAWAVE_FLEET_UPGRADE_HEALTH_GATE"
  def fresh(value: Option[Instant], now: Instant, age: FiniteDuration): Boolean =
    value.exists(t => !t.isAfter(now) && t.isAfter(now.minusMillis(age.toMillis)))
  def assessment(row: FleetMemberRow, revision: UUID, now: Instant, age: FiniteDuration): Option[String] =
    if (!row.assessment.exists(a => a.fleetRevisionId == revision && a.membershipVersion == row.membership.version &&
      a.membershipId == row.membership.id && a.inventoryNodeId == row.membership.inventoryNodeId && a.resourceId == row.membership.resourceId &&
      fresh(Some(a.computedAt), now, age) && List(a.inventoryObservedAt, a.serverObservedAt, a.localObservedAt).forall(fresh(_, now, age))))
      Some(RefreshRequired)
    else if (row.assessment.exists(a => a.compliance != FleetCompliance.Compliant || a.health != FleetHealth.Healthy || a.rolloutBlockers.nonEmpty) ||
      row.disabled || !row.connected || !row.resourceActive || !row.localManaged) Some(HealthGate)
    else None
  def versionConsistent(release: NodeRelease, reported: Option[String]): Boolean =
    reported.forall(_.stripPrefix("v") == release.nodeVersion)
  def configurationMatches(e: FleetStoredEvidence, d: FleetDesiredContent): Boolean =
    e.resourceActive && e.inventoryActive && e.configProfileAvailable && e.serverProfileAvailable &&
      e.assignment.exists(_.revisionId == d.serverProfileRevisionId) &&
      e.desiredStateRecord.exists(_.state == d.desiredNodeState) &&
      e.remoteConfigSha256.contains(d.configRevisionHash) &&
      e.node.exists(n => n.activeConfigProfileUuid.contains(d.externalConfigProfileId.toString) &&
        n.activeInboundIds.exists(_.toSet == d.inboundSet) && n.isDisabled == (d.desiredNodeState == IntegrationDesiredNodeState.Disabled)) &&
      (for { observed <- e.serverObservation; desired <- e.desiredServerContent }
        yield domain.provisioning.ServerProfileDiff.assess(desired, observed.content).compliant).contains(true)
  def sameBaseline(a: NodeImageObservation, b: NodeImageObservation): Boolean =
    a.actualImageId == b.actualImageId && a.platform == b.platform && a.containerId == b.containerId &&
      a.containerCreatedAt == b.containerCreatedAt && a.containerStartedAt == b.containerStartedAt &&
      a.configuredImage == b.configuredImage && a.composeHash == b.composeHash && a.markerHash == b.markerHash
}

private[integration] final case class NodeUpgradeVerification(member: NodeUpgradeMemberPlan, release: NodeRelease, after: Instant)

private[integration] trait NodeUpgradeExecution[Tx[_]] {
  def settings: NodeUpgradeSettings
  private[integration] def admission(run: RemnawaveFleetUpgradeRun, atomicMember: Option[UUID] = None): IO[Option[String]]
  private[integration] def verificationBatch(run: RemnawaveFleetUpgradeRun, checks: List[NodeUpgradeVerification]): IO[Map[UUID, Option[String]]]
  private[integration] final def verification(run: RemnawaveFleetUpgradeRun, member: NodeUpgradeMemberPlan, release: NodeRelease, after: Instant): IO[Option[String]] =
    verificationBatch(run, List(NodeUpgradeVerification(member, release, after))).map(_(member.membershipId))
  private[integration] def connection(org: UUID, member: NodeUpgradeMemberPlan): IO[domain.connection.Connection]
  private[integration] def panel(org: UUID, integration: UUID): IO[NodeApiCompatibility]
  private[integration] def spec(snapshot: NodeUpgradeSnapshot, member: NodeUpgradeMemberPlan): RemnawaveNodeRemoteSpec
  private[integration] def recordOutcome(run: RemnawaveFleetUpgradeRun): Tx[Unit]
}

/** Release selection and admission are metadata operations. Every remote read is outside Tx. */
final class RemnawaveFleetUpgrades[Tx[_]: MonadThrow](fleets: RemnawaveFleetRepository[Tx],
  query: RemnawaveFleetQuery[Tx], repo: RemnawaveFleetUpgradeRepository[Tx], integrations: IntegrationRepository[Tx],
  secrets: IntegrationSecretRepository[Tx], cryptography: IntegrationCryptography,
  providers: IntegrationProviderRegistry[IO], targets: ProvisioningTargetQuery[Tx],
  audit: AuditRecorder[Tx], runner: TransactionRunner[IO, Tx], val settings: NodeUpgradeSettings) extends NodeUpgradeExecution[Tx] with RemnawaveFleetUpgradeApi {
  import NodeUpgradeAdmission._
  private def error(code: String) = IntegrationError(code, "Node image lifecycle requires current verified evidence")
  private def loadedFleet(org: UUID, integration: UUID, fleet: UUID): Tx[RemnawaveFleet] =
    fleets.fleet(org, integration, fleet).map(_.filter(!_.archived))
      .flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_NOT_FOUND")))
  private def context(org: UUID, integration: UUID): IO[(Integration, IntegrationRuntimeContext, IntegrationProvider[IO])] =
    runner.run(for {
      i <- integrations.findById(org, integration).flatMap(_.liftTo[Tx](error("INTEGRATION_NOT_FOUND")))
      s <- secrets.find(org, i.secretId).flatMap(_.liftTo[Tx](error("INTEGRATION_CREDENTIAL_INVALID")))
    } yield i -> s).flatMap { case (i, s) => IO.delay(cryptography.decrypt(s)).flatMap(c =>
      providers.find(i.providerType).filter(_.nodeProvisioning.nonEmpty).liftTo[IO](error("INTEGRATION_PROVIDER_UNSUPPORTED"))
        .map(p => (i, IntegrationRuntimeContext(i.id, org, i.baseUrl, c), p))) }
  private[integration] def panel(org: UUID, integration: UUID): IO[NodeApiCompatibility] =
    context(org, integration).flatMap(c => c._3.nodeProvisioning.get.inspect(c._2))
  private[integration] def connection(org: UUID, m: NodeUpgradeMemberPlan): IO[domain.connection.Connection] =
    runner.run(targets.eligible(org, m.resourceId)).flatMap(_.toOption.filter(t =>
      t.connectionId == m.sourceConnectionId && t.connectionUpdatedAt == m.sourceUpdatedAt)
      .map(_.connection).liftTo[IO](error(PlanChanged)))
  private[integration] def spec(s: NodeUpgradeSnapshot, m: NodeUpgradeMemberPlan): RemnawaveNodeRemoteSpec =
    RemnawaveNodeRemoteSpec(m.onboardingId, m.resourceId, m.externalNodeId,
      s.configuration.nodePort, m.originalImageReference, s.configuration.panelCidrs)
  private def record(actor: ActorContext, integration: UUID, action: AuditAction): Tx[Unit] =
    audit.record(actor, action, AuditTargetType.Integration, Some(integration))
  private[integration] def recordOutcome(run: RemnawaveFleetUpgradeRun): Tx[Unit] =
    record(ActorContext(run.createdBy, run.organizationId), run.integrationId, AuditAction.RemnawaveFleetUpgradeCompleted)
  private[integration] def verificationBatch(run: RemnawaveFleetUpgradeRun,
    checks: List[NodeUpgradeVerification]): IO[Map[UUID, Option[String]]] = for {
    api <- panel(run.organizationId, run.integrationId)
    now <- IO.realTimeInstant
    facts <- runner.run(for {
      rows <- query.memberRowsBatch(run.organizationId, run.fleetId, checks.map(_.member.membershipId))
      images <- repo.observationsBatch(run.organizationId, run.fleetId, checks.map(_.member.membershipId))
      revision <- fleets.revision(run.organizationId, run.fleetId, run.snapshot.fleetRevisionId)
      evidence <- revision.fold(Map.empty[UUID, FleetStoredEvidence].pure[Tx])(r =>
        query.storedEvidenceBatch(run.organizationId, checks.map(_.member.membershipId), r))
      sync <- query.lastSuccessfulSyncAt(run.organizationId, run.integrationId)
    } yield (rows.iterator.map(r => r.membership.id -> r).toMap,
      images.iterator.map(o => o.membershipId -> o).toMap, evidence, sync))
  } yield {
    val (rows, images, proofs, sync) = facts
    checks.iterator.map { check =>
      val NodeUpgradeVerification(member, release, after) = check
      val row = rows.get(member.membershipId)
      val image = images.get(member.membershipId).filter(o => o.membershipVersion == member.membershipVersion &&
        o.inventoryNodeId == member.inventoryNodeId && o.resourceId == member.resourceId &&
        o.sourceConnectionId == member.sourceConnectionId && o.sourceUpdatedAt == member.sourceUpdatedAt)
      val evidence = proofs.get(member.membershipId)
      val newEvidence = sync.exists(_.isAfter(after)) && row.flatMap(_.assessment).exists(a =>
        a.computedAt.isAfter(after) && a.inventoryObservedAt.exists(_.isAfter(after)) && a.localObservedAt.exists(_.isAfter(after))) &&
        image.exists(o => o.observedAt.isAfter(after) && fresh(Some(o.observedAt), now, settings.staleAfter))
      val code = if (api != run.snapshot.panel || !RemnawaveNodeReleaseCatalog.compatibility(release, api, member.baseline.platform).compatible) Some(PlanChanged)
      else if (!newEvidence) Some(RefreshRequired)
      else row.flatMap(r => assessment(r, run.snapshot.fleetRevisionId, now, settings.staleAfter))
        .orElse(Option.unless(image.exists(o => o.observation.healthy && o.observation.matches(release) &&
          o.observation.markerHash == member.baseline.markerHash) &&
          evidence.exists(e => configurationMatches(e, run.snapshot.configuration) &&
            e.node.exists(n => n.isConnected && !n.isDisabled && versionConsistent(release, n.nodeVersion))))(HealthGate))
      member.membershipId -> code
    }.toMap
  }

  def select(actor: ActorContext, integration: UUID, fleetId: UUID, releaseId: String): IO[FleetNodeReleaseRevision] = for {
    release <- RemnawaveNodeReleaseCatalog.find(releaseId).filter(_.status == "AVAILABLE")
      .liftTo[IO](error("NODE_RELEASE_UNREVIEWED"))
    api <- panel(actor.organizationId, integration)
    _ <- IO.raiseUnless(RemnawaveNodeReleaseCatalog.compatibility(release, api).compatible)(error("NODE_RELEASE_INCOMPATIBLE"))
    now <- IO.realTimeInstant
    revision <- runner.run(for {
      _ <- fleets.lockFleet(actor.organizationId, fleetId)
      _ <- loadedFleet(actor.organizationId, integration, fleetId)
      busy <- repo.active(actor.organizationId, fleetId)
      _ <- MonadThrow[Tx].raiseWhen(busy.nonEmpty)(error("REMNAWAVE_FLEET_UPGRADE_ACTIVE"))
      number <- repo.nextRevisionNumber(actor.organizationId, fleetId)
      r = FleetNodeReleaseRevision(UUID.randomUUID(), actor.organizationId, integration, fleetId, number, release, api, actor.userId, now)
      _ <- repo.promote(r)
      _ <- record(actor, integration, AuditAction.RemnawaveNodeReleaseTargetChanged)
    } yield r)
  } yield revision

  def status(org: UUID, integration: UUID, fleetId: UUID): IO[Json] = for {
    api <- panel(org, integration)
    now <- IO.realTimeInstant
    data <- runner.run(for {
      _ <- loadedFleet(org, integration, fleetId)
      target <- repo.releaseTarget(org, fleetId)
      rows <- query.memberRows(org, fleetId)
      observations <- repo.observations(org, fleetId)
      active <- repo.active(org, fleetId)
      desired <- fleets.fleet(org, integration, fleetId).flatMap(_.flatMap(_.desiredRevisionId).flatTraverse(id => fleets.revision(org, fleetId, id)))
      evidence <- desired.fold(Map.empty[UUID, FleetStoredEvidence].pure[Tx])(r => query.storedEvidenceBatch(org, rows.map(_.membership.id), r))
      reported = evidence.view.mapValues(_.node.flatMap(_.nodeVersion)).toMap
    } yield (target, rows, observations, active, reported.toMap))
    (target, rows, observations, active, reported) = data
    imageIndex = observations.iterator.map(o => (o.membershipId, o.membershipVersion) -> o).toMap
  } yield Json.obj("panel" -> NodeReleaseJson.panelEncoder(api.copy(
    serverVersion = api.serverVersion.filter(v => NodeRelease.version(v).nonEmpty),
    sourceCommit = api.sourceCommit.filter(_.matches("[0-9a-f]{40}")))),
    "releases" -> Json.arr(RemnawaveNodeReleaseCatalog.releases.map(r => NodeReleaseJson.releaseEncoder(r).deepMerge(Json.obj(
      "compatibility" -> Json.obj("state" -> Json.fromString(RemnawaveNodeReleaseCatalog.compatibility(r, api).state),
        "reasons" -> Json.arr(RemnawaveNodeReleaseCatalog.compatibility(r, api).reasons.map(Json.fromString): _*))))): _*),
    "target" -> target.fold(Json.Null)(NodeReleaseJson.releaseRevisionEncoder.apply),
    "active" -> active.fold(Json.Null)(NodeUpgradeJson.run),
    "members" -> Json.arr(rows.map { row =>
      val obs = imageIndex.get((row.membership.id, row.membership.version)).filter(o =>
        fresh(Some(o.observedAt), now, settings.staleAfter)).map(_.observation)
      val installed = obs.flatMap(o => o.actualImageId.flatMap(id => o.platform.flatMap(p => RemnawaveNodeReleaseCatalog.identify(id, p))))
      val known = installed.filter(r => obs.exists(o => o.managedFiles && o.matches(r)) && versionConsistent(r, reported.getOrElse(row.membership.id, None)))
      val availability = if (known.isEmpty) "UNKNOWN" else if (!RemnawaveNodeReleaseCatalog.compatibility(known.get, api, obs.flatMap(_.platform)).compatible) "UNSUPPORTED"
        else target.fold("CURRENT") { t =>
          val order = implicitly[Ordering[(Int, Int, Int)]]
          if (known.get.releaseId == t.release.releaseId) "CURRENT"
          else if (order.lt(NodeRelease.version(known.get.nodeVersion).get, NodeRelease.version(t.release.nodeVersion).get)) "UPDATE_AVAILABLE"
          else "AHEAD_OF_TARGET"
        }
      Json.obj("membershipId" -> Json.fromString(row.membership.id.toString), "nodeName" -> Json.fromString(row.nodeName),
        "status" -> Json.fromString(availability), "releaseId" -> known.fold(Json.Null)(r => Json.fromString(r.releaseId)),
        "reportedVersion" -> reported.getOrElse(row.membership.id, None).filter(v => NodeRelease.version(v).nonEmpty).fold(Json.Null)(Json.fromString),
        "observation" -> obs.fold(Json.Null)(NodeReleaseJson.observationEncoder.apply))
    }: _*))

  def preview(actor: ActorContext, integration: UUID, fleetId: UUID, input: NodeUpgradePreviewInput): IO[NodeUpgradePreview] = for {
    _ <- IO.raiseUnless(settings.enabled)(error("PROVISIONING_DISABLED"))
    api <- panel(actor.organizationId, integration)
    now <- IO.realTimeInstant
    loaded <- runner.run(for {
      i <- integrations.findById(actor.organizationId, integration).flatMap(_.liftTo[Tx](error("INTEGRATION_NOT_FOUND")))
      f <- loadedFleet(actor.organizationId, integration, fleetId)
      r <- f.desiredRevisionId.flatTraverse(id => fleets.revision(actor.organizationId, fleetId, id))
        .flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_REVISION_NOT_FOUND")))
      release <- repo.releaseTarget(actor.organizationId, fleetId).map(_.filter(_.id == input.releaseRevisionId))
        .flatMap(_.liftTo[Tx](error("NODE_RELEASE_REVISION_NOT_FOUND")))
      rows <- query.memberRows(actor.organizationId, fleetId)
      images <- repo.observations(actor.organizationId, fleetId)
      sync <- query.lastSuccessfulSyncAt(actor.organizationId, integration)
      evidence <- query.storedEvidenceBatch(actor.organizationId, rows.map(_.membership.id), r)
      sources <- targets.eligibleBatch(actor.organizationId, rows.map(_.membership.resourceId))
      busy <- fleets.rolloutActive(actor.organizationId, fleetId)
    } yield (i, r, release, rows, images, sync, evidence, sources, busy))
    (i, revision, release, rows, images, sync, evidence, sources, busy) = loaded
    imageIndex = images.iterator.map(o => (o.membershipId, o.membershipVersion) -> o).toMap
    issues = (Option.when(!i.enabled || i.managementMode != IntegrationManagementMode.ManagedSelected)("INTEGRATION_MANAGEMENT_MODE_REQUIRED") ++
      Option.when(busy)("REMNAWAVE_FLEET_UPGRADE_ACTIVE") ++
      Option.when(input.waveSize < 1 || input.waveSize > 25 || input.canaryMemberIds.distinct != input.canaryMemberIds ||
        rows.isEmpty || rows.size > RemnawaveFleet.MaxMembers)("NODE_UPGRADE_POLICY_INVALID") ++
      Option.when(!fresh(sync, now, settings.staleAfter))(RefreshRequired) ++
      Option.when(!RemnawaveNodeReleaseCatalog.compatibility(release.release, api).compatible || release.release.status != "AVAILABLE")("NODE_RELEASE_INCOMPATIBLE") ++
      rows.flatMap { row =>
        val image = imageIndex.get((row.membership.id, row.membership.version))
        val proof = evidence.get(row.membership.id).flatMap(_.provenance)
        val source = sources.get(row.membership.resourceId).flatMap(_.toOption)
        assessment(row, revision.id, now, settings.staleAfter).toList ++
          Option.when(proof.isEmpty || !proof.exists(_.provisioningReady) || source.isEmpty ||
            !evidence.get(row.membership.id).exists(e => e.bindingPresent && e.bindingResourceId.contains(row.membership.resourceId) && !e.busy))("NODE_UPGRADE_UNMANAGED") ++
          Option.when(!image.exists(o => fresh(Some(o.observedAt), now, settings.staleAfter) && o.inventoryNodeId == row.membership.inventoryNodeId &&
            o.resourceId == row.membership.resourceId && proof.exists(_.onboardingId == o.onboardingId) &&
            source.exists(t => t.connectionId == o.sourceConnectionId && t.connectionUpdatedAt == o.sourceUpdatedAt)))(RefreshRequired) ++
          Option.when(!image.exists(o => o.observation.healthy && o.observation.composeHash.nonEmpty && o.observation.markerHash.nonEmpty &&
            o.observation.platform.exists(p => RemnawaveNodeReleaseCatalog.compatibility(release.release, api, Some(p)).compatible)))(HealthGate) ++
          Option.when(!image.exists(o => o.observation.actualImageId.flatMap(id => o.observation.platform.flatMap(p =>
            RemnawaveNodeReleaseCatalog.identify(id, p))).exists(r => o.observation.matches(r) &&
            versionConsistent(r, evidence.get(row.membership.id).flatMap(_.node).flatMap(_.nodeVersion)))))("NODE_UPGRADE_BASELINE_UNKNOWN")
      }).toList.distinct
    result <- if (issues.nonEmpty) IO.pure(NodeUpgradePreview(if (issues.contains(RefreshRequired)) "REFRESH_REQUIRED" else "BLOCKED", issues, None))
      else {
        val facts = rows.map { row =>
          val image = imageIndex((row.membership.id, row.membership.version)).observation
          val previous = RemnawaveNodeReleaseCatalog.identify(image.actualImageId.get, image.platform.get).get
          val rollback = Option.when(RemnawaveNodeReleaseCatalog.compatibility(previous, api, image.platform).compatible && previous.status != "BLOCKED")(previous)
          val source = sources.get(row.membership.resourceId).flatMap(_.toOption).get
          val proof = evidence.get(row.membership.id).flatMap(_.provenance).get
          NodeUpgradeMemberPlan(row.membership.id, row.membership.version, row.membership.inventoryNodeId, proof.externalNodeId,
            row.membership.resourceId, row.nodeName, proof.onboardingId, proof.imageReference, source.connectionId, source.connectionUpdatedAt,
            0, 0, image.matches(release.release), image, rollback.map(_.releaseId),
            rollback.map(r => s"${r.imageRepository}@${r.forPlatform(image.platform.get).get.manifestDigest}"),
            evidence.get(row.membership.id).flatMap(_.node).flatMap(_.nodeVersion))
        }
        val pending = facts.filterNot(_.skipped)
        val canary = input.canaryMemberIds.toSet
        val pendingIds = pending.iterator.map(_.membershipId).toSet
        val invalid = (input.canaryMemberIds.exists(id => !pendingIds(id)) ||
          (pending.size > 1 && canary.isEmpty) || (pending.nonEmpty && canary.size == pending.size && pending.size > 1))
        val noRollback = input.automaticRollback && pending.exists(!_.rollbackAvailable)
        if (invalid || noRollback) IO.pure(NodeUpgradePreview("BLOCKED", List(if (noRollback) "NODE_UPGRADE_NO_PREVIOUS_IMAGE" else "NODE_UPGRADE_CANARY_REQUIRED"), None))
        else {
          val ordered = pending.filter(m => canary(m.membershipId)).sortBy(m => (m.nodeName, m.membershipId.toString)) ++
            pending.filterNot(m => canary(m.membershipId)).sortBy(m => (m.nodeName, m.membershipId.toString))
          val plans = ordered.zipWithIndex.map { case (m, pos) => m.copy(position = pos,
            wave = if (canary(m.membershipId) || pending.size == 1) 0 else 1 + ((pos - canary.size) / input.waveSize)) } ++
            facts.filter(_.skipped).zipWithIndex.map { case (m, pos) => m.copy(position = ordered.size + pos) }
          val snapshot = NodeUpgradeSnapshot(1, actor.organizationId, integration, fleetId, revision.id, revision.contentHash,
            i.updatedAt, revision.content, release.id, release.hash, release.release, api, input.canaryMemberIds,
            input.waveSize, input.pauseAfterCanary, input.automaticRollback, plans)
          val run = RemnawaveFleetUpgradeRun(UUID.randomUUID(), actor.organizationId, integration, fleetId, release.id, None,
            FleetRolloutState.Planned, NodeUpgradePhase.Validate, snapshot, snapshot.hash, 0, snapshot.waveCount,
            actor.userId, now, now.plusMillis(settings.planTtl.toMillis), None, None, None, None, None, None, None,
            FleetRollbackScope.CurrentWave, false, now, now, None, 1L, now)
          val members = plans.map(m => RemnawaveFleetUpgradeMember(UUID.randomUUID(), actor.organizationId, run.id,
            m.membershipId, m.resourceId, m.wave, m.position, if (m.skipped) FleetRolloutMemberState.Skipped else FleetRolloutMemberState.Pending,
            None, None, None, None, None, None))
          runner.run(repo.insertPlan(run, members)).as(NodeUpgradePreview("READY", Nil, Some(run)))
        }
      }
  } yield result

  /** Used before START, RESUME, VALIDATE, every wave and every new mutation. */
  private[integration] def admission(run: RemnawaveFleetUpgradeRun, atomicMember: Option[UUID] = None): IO[Option[String]] = for {
    api <- panel(run.organizationId, run.integrationId)
    now <- IO.realTimeInstant
    code <- runner.run(admissionStored(run, api, now, atomicMember))
  } yield code
  private def admissionStored(run: RemnawaveFleetUpgradeRun, api: NodeApiCompatibility, now: Instant,
    atomicMember: Option[UUID] = None): Tx[Option[String]] = {
    val s = run.snapshot
    for {
      i <- integrations.findById(run.organizationId, run.integrationId)
      f <- fleets.fleet(run.organizationId, run.integrationId, run.fleetId)
      revision <- fleets.revision(run.organizationId, run.fleetId, s.fleetRevisionId)
      target <- repo.releaseTarget(run.organizationId, run.fleetId)
      rows <- query.memberRows(run.organizationId, run.fleetId)
      images <- repo.observations(run.organizationId, run.fleetId)
      members <- repo.members(run.id)
      actions <- repo.actions(run.id)
      sync <- query.lastSuccessfulSyncAt(run.organizationId, run.integrationId)
      sources <- targets.eligibleBatch(run.organizationId, s.members.map(_.resourceId))
      evidenceIndex <- revision.fold(Map.empty[UUID, FleetStoredEvidence].pure[Tx])(r =>
        query.storedEvidenceBatch(run.organizationId, s.members.map(_.membershipId), r))
      memberIndex = members.iterator.map(m => m.membershipId -> m).toMap
      imageIndex = images.iterator.map(o => (o.membershipId, o.membershipVersion) -> o).toMap
      switchIndex = actions.filter(a => !a.rollback && a.kind == "SWITCH").iterator.map(a => a.memberId -> a).toMap
      pins = s.members.map { m =>
        val source = sources.get(m.resourceId).flatMap(_.toOption)
        val evidence = evidenceIndex.get(m.membershipId)
        source.exists(t => t.connectionId == m.sourceConnectionId && t.connectionUpdatedAt == m.sourceUpdatedAt) &&
        evidence.exists(e => e.membership.version == m.membershipVersion && e.membership.inventoryNodeId == m.inventoryNodeId &&
          e.membership.resourceId == m.resourceId && e.bindingResourceId.contains(m.resourceId) && e.bindingPresent &&
          configurationMatches(e, s.configuration) &&
          e.node.exists(n => {
            val state = memberIndex.get(m.membershipId).map(_.state)
            if (state.contains(FleetRolloutMemberState.Pending)) (for { id <- m.baseline.actualImageId; platform <- m.baseline.platform;
              release <- RemnawaveNodeReleaseCatalog.identify(id, platform) } yield release).exists(versionConsistent(_, n.nodeVersion))
            else if (state.contains(FleetRolloutMemberState.Succeeded) || state.contains(FleetRolloutMemberState.Skipped)) versionConsistent(s.target, n.nodeVersion)
            else true
          }) &&
          e.provenance.exists(p => p.onboardingId == m.onboardingId && p.externalNodeId == m.externalNodeId && p.imageReference == m.originalImageReference) &&
          (memberIndex.get(m.membershipId).exists(_.state != FleetRolloutMemberState.Pending) || !e.busy)) }
    } yield {
      val intact = s.hash == run.snapshotHash && api == s.panel && RemnawaveNodeReleaseCatalog.find(s.target.releaseId).contains(s.target) &&
        s.target.status == "AVAILABLE" && RemnawaveNodeReleaseCatalog.compatibility(s.target, api).compatible &&
        i.exists(v => v.enabled && v.updatedAt == s.integrationPin && v.managementMode == IntegrationManagementMode.ManagedSelected) &&
        f.exists(v => !v.archived && v.desiredRevisionId.contains(s.fleetRevisionId)) && revision.exists(_.contentHash == s.fleetRevisionHash) &&
        target.exists(v => v.id == s.releaseRevisionId && v.hash == s.releaseRevisionHash) &&
        rows.map(_.membership.id).toSet == s.members.map(_.membershipId).toSet && pins.forall(identity)
      if (!intact) Some(PlanChanged)
      else if (!fresh(sync, now, settings.staleAfter)) Some(RefreshRequired)
      else rows.iterator.flatMap { row =>
        assessment(row, s.fleetRevisionId, now, settings.staleAfter).filter(code =>
          code != HealthGate || !atomicMember.contains(row.membership.id))
      }.toList.headOption.orElse {
        s.members.iterator.flatMap { m =>
          val image = imageIndex.get((m.membershipId, m.membershipVersion))
          val member = memberIndex.get(m.membershipId)
          if (!image.exists(o => fresh(Some(o.observedAt), now, settings.staleAfter))) Some(RefreshRequired)
          else if (member.exists(_.state == FleetRolloutMemberState.Pending) && !image.exists(o => o.observation.healthy && sameBaseline(o.observation, m.baseline))) Some(PlanChanged)
          else if (member.exists(v => v.state == FleetRolloutMemberState.Succeeded && v.wave < run.currentWave) &&
            !image.exists(o => o.observation.healthy && o.observation.matches(s.target) &&
              switchIndex.get(member.get.id).flatMap(_.finishedAt).exists(o.observedAt.isAfter))) Some(HealthGate)
          else None
        }.toList.headOption
      }
    }
  }
  def detail(org: UUID, integration: UUID, fleet: UUID, id: UUID): IO[NodeUpgradeDetail] = runner.run(for {
    r <- repo.run(org, fleet, id).map(_.filter(_.integrationId == integration)).flatMap(_.liftTo[Tx](error("NODE_UPGRADE_NOT_FOUND")))
    members <- repo.members(r.id)
    actions <- repo.actions(r.id)
  } yield NodeUpgradeDetail(r, members, actions))
  def history(org: UUID, integration: UUID, fleet: UUID): IO[List[RemnawaveFleetUpgradeRun]] = runner.run(
    loadedFleet(org, integration, fleet) *> repo.history(org, fleet))
  def start(actor: ActorContext, integration: UUID, fleet: UUID, planId: UUID, request: UUID): IO[RemnawaveFleetUpgradeRun] = for {
    existing <- runner.run(repo.byRequest(actor.organizationId, request))
    result <- existing match {
      case Some(r) => IO.raiseUnless(r.id == planId && r.integrationId == integration && r.fleetId == fleet)(error("NODE_UPGRADE_REQUEST_REUSED")).as(r)
      case None => for {
        _ <- IO.raiseUnless(settings.enabled)(error("PROVISIONING_DISABLED"))
        d <- detail(actor.organizationId, integration, fleet, planId)
        code <- admission(d.run)
        _ <- code.traverse_(c => IO.raiseError[Unit](error(c)))
        api <- panel(actor.organizationId, integration)
        now <- IO.realTimeInstant
        r <- runner.run(for {
          _ <- fleets.lockFleet(actor.organizationId, fleet)
          current <- repo.run(actor.organizationId, fleet, planId, true).flatMap(_.liftTo[Tx](error("NODE_UPGRADE_NOT_FOUND")))
          _ <- MonadThrow[Tx].raiseUnless(current.state == FleetRolloutState.Planned && current.expiresAt.isAfter(now))(error("NODE_UPGRADE_PLAN_EXPIRED"))
          reason <- admissionStored(current, api, now)
          _ <- reason.traverse_(c => MonadThrow[Tx].raiseError[Unit](error(c)))
          ok <- repo.start(actor.organizationId, planId, request, now)
          _ <- MonadThrow[Tx].raiseUnless(ok)(error(PlanChanged))
          _ <- record(actor, integration, AuditAction.RemnawaveFleetUpgradeRequested)
          r <- repo.byId(planId).map(_.get)
        } yield r)
      } yield r
    }
  } yield result
  def control(actor: ActorContext, integration: UUID, fleet: UUID, id: UUID, command: String,
    scope: FleetRollbackScope = FleetRollbackScope.CurrentWave): IO[RemnawaveFleetUpgradeRun] = for {
    d <- detail(actor.organizationId, integration, fleet, id)
    _ <- IO.raiseWhen(d.run.state.terminal)(error("NODE_UPGRADE_TERMINAL"))
    api <- if (command == "resume") panel(actor.organizationId, integration) else IO.pure(d.run.snapshot.panel)
    now <- IO.realTimeInstant
    r <- runner.run(for {
      _ <- fleets.lockFleet(actor.organizationId, fleet)
      current <- repo.run(actor.organizationId, fleet, id, true).flatMap(_.liftTo[Tx](error("NODE_UPGRADE_NOT_FOUND")))
      _ <- if (command != "resume") ().pure[Tx] else admissionStored(current, api, now).flatMap(_.traverse_(c => MonadThrow[Tx].raiseError[Unit](error(c))))
      members <- repo.members(id)
      _ <- MonadThrow[Tx].raiseWhen(command == "rollback" && (members.exists(_.state == FleetRolloutMemberState.Unknown) ||
        current.snapshot.members.filter(m => members.exists(v => v.membershipId == m.membershipId && v.startedAt.nonEmpty)).exists(!_.rollbackAvailable)))(error("NODE_UPGRADE_ROLLBACK_UNAVAILABLE"))
      ok <- command match {
        case "pause" => repo.requestPause(actor.organizationId, id, now)
        case "resume" => repo.resume(actor.organizationId, id, now)
        case "rollback" => repo.requestRollback(actor.organizationId, id, scope, now)
        case _ => MonadThrow[Tx].raiseError[Boolean](error("NODE_UPGRADE_CONTROL_INVALID"))
      }
      _ <- MonadThrow[Tx].raiseUnless(ok)(error("NODE_UPGRADE_CONTROL_INVALID"))
      action = if (command == "pause") AuditAction.RemnawaveFleetUpgradePaused else if (command == "resume") AuditAction.RemnawaveFleetUpgradeResumed else AuditAction.RemnawaveFleetUpgradeRollbackRequested
      _ <- record(actor, integration, action)
      r <- repo.byId(id).map(_.get)
    } yield r)
  } yield r
}

object NodeUpgradeJson {
  private def str(v: String) = Json.fromString(v)
  private def id(v: UUID) = str(v.toString)
  private def opt[A](v: Option[A])(f: A => Json): Json = v.fold(Json.Null)(f)
  def run(r: RemnawaveFleetUpgradeRun): Json = Json.obj("id" -> id(r.id), "state" -> str(r.state.code), "phase" -> str(r.phase.code),
    "releaseRevisionId" -> id(r.releaseRevisionId), "targetVersion" -> str(r.snapshot.target.nodeVersion),
    "currentWave" -> Json.fromInt(r.currentWave), "waveCount" -> Json.fromInt(r.waveCount), "failureCode" -> opt(r.failureCode)(str),
    "pauseReason" -> opt(r.pauseReason)(str), "pauseRequested" -> Json.fromBoolean(r.pauseRequestedAt.nonEmpty),
    "rollbackRequested" -> Json.fromBoolean(r.rollbackRequestedAt.nonEmpty), "rollbackIncomplete" -> Json.fromBoolean(r.rollbackIncomplete),
    "createdAt" -> str(r.createdAt.toString), "expiresAt" -> str(r.expiresAt.toString), "updatedAt" -> str(r.updatedAt.toString),
    "startedAt" -> opt(r.startedAt)(t => str(t.toString)), "finishedAt" -> opt(r.finishedAt)(t => str(t.toString)))
  def detail(d: NodeUpgradeDetail): Json = run(d.run).deepMerge(Json.obj("snapshot" -> NodeReleaseJson.snapshotEncoder(d.run.snapshot),
    "members" -> Json.arr(d.members.map(m => Json.obj("id" -> id(m.id), "membershipId" -> id(m.membershipId),
      "wave" -> Json.fromInt(m.wave), "position" -> Json.fromInt(m.position), "state" -> str(m.state.code), "failureCode" -> opt(m.failureCode)(str),
      "localVerifiedAt" -> opt(m.localVerifiedAt)(t => str(t.toString)), "panelVerifiedAt" -> opt(m.panelVerifiedAt)(t => str(t.toString)))): _*),
    "actions" -> Json.arr(d.actions.map(a => Json.obj("id" -> id(a.id), "memberId" -> id(a.memberId), "kind" -> str(a.kind),
      "rollback" -> Json.fromBoolean(a.rollback), "state" -> str(a.state.code), "targetReference" -> str(a.targetReference),
      "failureCode" -> opt(a.failureCode)(str), "startedAt" -> str(a.startedAt.toString), "finishedAt" -> opt(a.finishedAt)(t => str(t.toString)))): _*)))
}
