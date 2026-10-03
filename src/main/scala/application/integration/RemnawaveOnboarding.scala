package ru.bitec.app.ops
package application.integration

import application.auth.ActorContext
import application.audit.AuditRecorder
import application.port._
import application.provisioning.{ProvisioningError,ProvisioningRuns,ProvisioningSettings,ServerProfileError,ServerProfilePlan,ServerProfiles}
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction,AuditTargetType}
import domain.integration._
import domain.provisioning._
import io.circe.Json
import java.time.Instant
import java.util.UUID

trait RemnawaveOnboardingApi {
  def options(org: UUID, integration: UUID): IO[Json]
  def preview(actor: ActorContext, integration: UUID, input: OnboardingInput): IO[Json]
  def start(actor: ActorContext, integration: UUID, plan: UUID, request: UUID): IO[RemnawaveNodeOnboardingRun]
  def detail(org: UUID, integration: UUID, id: UUID): IO[Json]
  def history(org: UUID, integration: UUID): IO[Json]
}

final class RemnawaveOnboarding[Tx[_]: MonadThrow](repo: RemnawaveOnboardingRepository[Tx],
  query: RemnawaveOnboardingQuery[Tx], integrations: IntegrationRepository[Tx], secrets: IntegrationSecretRepository[Tx],
  cryptography: IntegrationCryptography, providers: IntegrationProviderRegistry[IO], targets: ProvisioningTargetQuery[Tx],
  profiles: ServerProfiles[IO,Tx], inventory: IntegrationInventoryQuery[Tx], runner: TransactionRunner[IO,Tx],
  remote: RemnawaveNodeRemote[IO], audit: AuditRecorder[Tx], settings: ProvisioningSettings,
  provisioningPlans: ProvisioningRunRepository[Tx]) extends RemnawaveOnboardingApi {
  private def error(code: String) = IntegrationError(code,"Remnawave onboarding could not proceed")
  private def context(org: UUID, integration: UUID): IO[(Integration,IntegrationRuntimeContext,IntegrationProvider[IO])] =
    runner.run(for {
      i <- integrations.findById(org,integration).flatMap(_.liftTo[Tx](error("INTEGRATION_NOT_FOUND")))
      s <- secrets.find(org,i.secretId).flatMap(_.liftTo[Tx](error("INTEGRATION_CREDENTIAL_INVALID")))
    } yield i -> s).flatMap { case(i,s) => IO.delay(cryptography.decrypt(s)).flatMap(c =>
      providers.find(i.providerType).filter(_.nodeProvisioning.nonEmpty).liftTo[IO](error("INTEGRATION_PROVIDER_UNSUPPORTED"))
        .map(p => (i,IntegrationRuntimeContext(i.id,org,i.baseUrl,c),p))) }
  private def profileInventory(org: UUID, integration: UUID): IO[List[InventoryItem]] = runner.run(
    inventory.list(org,integration,IntegrationObjectType.ConfigProfile,InventoryFilter(Some(true),None,None,500,0))).map(_.items)
  def options(org: UUID, integration: UUID): IO[Json] = for {
    c <- context(org,integration)
    api <- c._3.nodeProvisioning.get.inspect(c._2)
    candidates <- runner.run(query.candidates(org,500))
    servers <- candidates.traverse { candidate => for {
      target <- runner.run(targets.eligible(org,candidate.id))
      automation <- profiles.automation(org,candidate.id)
      busy <- runner.run(repo.active(org,candidate.id))
      conflict <- runner.run(query.bindingConflict(org,candidate.id,None))
      blocks = target.left.toOption.toList ++ Option.when(busy)("REMNAWAVE_ONBOARDING_RESOURCE_BUSY") ++
        Option.when(conflict)("REMNAWAVE_ONBOARDING_BINDING_CONFLICT") ++ Option.when(automation.assignment.isEmpty)("REMNAWAVE_ONBOARDING_PROFILE_REQUIRED")
    } yield Json.obj("id" -> str(candidate.id.toString),"name" -> str(candidate.name),"address" -> str(candidate.address),
      "environmentName" -> str(candidate.environmentName),"sshStatus" -> str(if(target.isRight) "TRUSTED" else "UNAVAILABLE"),
      "serverProfileName" -> automation.profile.fold(Json.Null)(p => str(p.name)),
      "revisionNumber" -> automation.revision.fold(Json.Null)(r => Json.fromInt(r.number)),"serverProfileStatus" -> str(automation.state),
      "blockingProblems" -> strings(blocks)) }
    items <- profileInventory(org,integration)
  } yield Json.obj("nodeApi" -> OnboardingJson.compatibility(api),"servers" -> Json.arr(servers: _*),
    "profiles" -> Json.arr(items.collect { case item if item.obj.summary.isInstanceOf[RemnawaveConfigProfileSummary] =>
      val summary = item.obj.summary.asInstanceOf[RemnawaveConfigProfileSummary]
      Json.obj("id" -> str(item.obj.externalId),"name" -> str(item.obj.displayName),"inbounds" -> Json.arr(summary.inbounds.map(i =>
        Json.obj("id" -> str(i.uuid),"name" -> str(i.tag))): _*)) }: _*))

  def preview(actor: ActorContext,integration: UUID,raw: OnboardingInput): IO[Json] = for {
    _ <- IO.raiseUnless(settings.enabled)(error("PROVISIONING_DISABLED"))
    input <- raw.normalized.leftMap(error).liftTo[IO]
    c <- context(actor.organizationId,integration)
    target <- runner.run(targets.eligible(actor.organizationId,input.resourceId))
    api <- c._3.nodeProvisioning.get.inspect(c._2)
    candidates <- runner.run(query.candidates(actor.organizationId,500))
    items <- profileInventory(actor.organizationId,integration)
    profile = items.find(_.obj.externalId==input.configProfileId.toString)
    inbounds = profile.toList.flatMap(_.obj.summary match { case s: RemnawaveConfigProfileSummary => s.inbounds; case _ => Nil })
    baseline <- profiles.preview(actor,input.resourceId).attempt
    busy <- runner.run(repo.active(actor.organizationId,input.resourceId))
    conflict <- runner.run(query.bindingConflict(actor.organizationId,input.resourceId,None))
    local <- target.toOption.traverse(t => remote.preflight(t.connection,input.resourceId,input.nodePort).attempt)
    live <- c._3.observe(c._2).attempt
    liveProfile = live.toOption.toList.flatMap(_.objects).find(o => o.objectType==IntegrationObjectType.ConfigProfile && o.externalId==input.configProfileId.toString)
    liveInboundIds = liveProfile.toList.flatMap(_.summary match { case p: RemnawaveConfigProfileSummary => p.inbounds.map(_.uuid); case _ => Nil }).toSet
    image = RemnawaveNodeImagePolicy.select(api)
    blocks = (target.left.toOption.toList ++ api.blocker.toList ++ Option.when(!api.provisioningReady)("INTEGRATION_API_CONTRACT_UNCONFIRMED") ++
      Option.when(image.isEmpty)("REMNAWAVE_ONBOARDING_IMAGE_UNCONFIRMED") ++ Option.when(!c._1.enabled)("INTEGRATION_MANAGEMENT_REQUIRES_SYNC") ++
      Option.when(c._1.managementMode!=IntegrationManagementMode.ManagedSelected)("INTEGRATION_MANAGEMENT_MODE_REQUIRED") ++
      Option.when(profile.isEmpty || liveProfile.isEmpty)("REMNAWAVE_ONBOARDING_CONFIG_PROFILE_MISSING") ++
      Option.when(!input.activeInboundIds.forall(id => inbounds.exists(_.uuid==id.toString) && liveInboundIds(id.toString)))("REMNAWAVE_ONBOARDING_INBOUND_INVALID") ++
      Option.when(busy)("REMNAWAVE_ONBOARDING_RESOURCE_BUSY") ++ Option.when(conflict)("REMNAWAVE_ONBOARDING_BINDING_CONFLICT") ++
      baseline.left.toOption.map(safeCode).toList ++ baseline.toOption.toList.flatMap(_.blockingProblems) ++
      local.toList.flatMap(_.fold(e => List(safeCode(e)),r => r.failureCode.toList ++ Option.when(r.uncertain)("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN"))) ++
      live.left.toOption.map(safeCode)).distinct
    pinned = baseline.toOption.flatMap(_.run.input.profileApply)
    zero = new UUID(0,0)
    now <- IO.realTimeInstant
    snapshot = OnboardingSnapshot(input,c._1.updatedAt,c._1.secretId,target.toOption.fold(zero)(_.connectionId),
      target.toOption.fold(now)(_.connectionUpdatedAt),pinned.fold(zero)(_.profileId),pinned.fold(zero)(_.revisionId),
      pinned.fold(0)(_.revisionNumber),pinned.fold(zero)(_.assignmentId),pinned.fold(0L)(_.assignmentVersion),
      pinned.fold("")(_.revisionHash),baseline.toOption.fold(zero)(_.run.id),baseline.toOption.exists(!_.assessment.compliant),api,
      image.getOrElse(""),UUID.randomUUID(),candidates.find(_.id==input.resourceId).fold("")(_.name),
      baseline.toOption.fold("")(_.profileName),profile.fold("")(_.obj.displayName),
      input.activeInboundIds.flatMap(id => inbounds.find(_.uuid==id.toString).map(_.tag)),
      baseline.toOption.toList.flatMap(_.assessment.modules.filterNot(_._2).map(_._1)) ++
        List("CREATE_NODE", "CONFIGURE_NODE_FIREWALL", "INSTALL_NODE", "START_NODE", "SYNC_INVENTORY", "BIND_RESOURCE", "SET_DESIRED_STATE", "FINAL_VERIFY"),
      baseline.toOption.toList.flatMap(_.warnings),blocks)
    run = RemnawaveNodeOnboardingRun(UUID.randomUUID(),actor.organizationId,integration,input.resourceId,None,actor.userId,
      ProvisioningRunState.Planned,OnboardingPhase.Validate,snapshot,now,now)
    _ <- if (blocks.nonEmpty) IO.unit else runner.run(for {
      _ <- repo.lockResource(actor.organizationId,input.resourceId)
      active <- repo.active(actor.organizationId,input.resourceId)
      _ <- MonadThrow[Tx].raiseWhen(active)(error("REMNAWAVE_ONBOARDING_RESOURCE_BUSY"))
      valid <- profiles.valid(actor.organizationId,input.resourceId,pinned.get)
      current <- integrations.findById(actor.organizationId,integration)
      unchanged <- targets.unchanged(baseline.toOption.get.run.input)
      _ <- MonadThrow[Tx].raiseUnless(valid && unchanged && current.exists(i => i.updatedAt==c._1.updatedAt && i.secretId==c._1.secretId))(
        error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
      _ <- repo.insertPlan(run)
    } yield ())
  } yield Json.obj("run" -> OnboardingJson.run(run),"serverName" -> str(snapshot.serverName),
    "serverProfileName" -> str(snapshot.serverProfileName),"revisionNumber" -> Json.fromInt(snapshot.revisionNumber),
    "configProfileName" -> str(snapshot.configProfileName),"inboundNames" -> strings(snapshot.inboundNames),
    "nodeImage" -> image.fold(Json.Null)(str),"nodeApi" -> OnboardingJson.compatibility(api),
    "panelCidrs" -> strings(input.panelCidrs),"changes" -> strings(snapshot.changes),"warnings" -> strings(snapshot.warnings),
    "blockingProblems" -> strings(blocks))

  def start(actor: ActorContext,integration: UUID,plan: UUID,request: UUID): IO[RemnawaveNodeOnboardingRun] = for {
    _ <- IO.raiseUnless(settings.enabled)(error("PROVISIONING_DISABLED"))
    now <- IO.realTimeInstant
    result <- runner.run(for {
      started <- repo.start(actor.organizationId,integration,plan,request,actor.userId,now)
      _ <- if(started.updatedAt!=now) MonadThrow[Tx].unit else for {
        target <- targets.eligible(actor.organizationId,started.resourceId)
        i <- integrations.findById(actor.organizationId,integration)
        child <- provisioningPlans.find(actor.organizationId,started.snapshot.baselinePlanId)
        pin <- child.flatMap(_._1.input.profileApply).liftTo[Tx](error("REMNAWAVE_ONBOARDING_PROFILE_CHANGED"))
        valid <- profiles.valid(actor.organizationId,started.resourceId,pin)
        _ <- MonadThrow[Tx].raiseUnless(valid && target.exists(t => t.connectionId==started.snapshot.connectionId &&
          t.connectionUpdatedAt==started.snapshot.connectionUpdatedAt) && i.exists(v => v.secretId==started.snapshot.integrationSecretId &&
          v.updatedAt==started.snapshot.integrationUpdatedAt && v.enabled && v.managementMode==IntegrationManagementMode.ManagedSelected))(
          error("REMNAWAVE_ONBOARDING_SOURCE_CHANGED"))
        _ <- audit.record(actor,AuditAction.RemnawaveNodeOnboardingRequested,AuditTargetType.Integration,Some(integration))
      } yield ()
    } yield started)
  } yield result
  def detail(org: UUID,integration: UUID,id: UUID): IO[Json] = runner.run(repo.find(org,integration,id)).flatMap(
    _.liftTo[IO](error("REMNAWAVE_ONBOARDING_NOT_FOUND"))).map { case(r,p) => OnboardingJson.detail(r,p) }
  def history(org: UUID,integration: UUID): IO[Json] = runner.run(repo.history(org,integration,50)).map(rows => Json.obj("items" -> Json.arr(rows.map(OnboardingJson.run): _*)))
  private def safeCode(e: Throwable): String = e match {
    case p: ProvisioningError => p.code
    case p: ServerProfileError => if(p.code=="SERVER_PROFILE_INVALID") "REMNAWAVE_ONBOARDING_PROFILE_REQUIRED" else p.code
    case p: IntegrationError => p.code
    case _ => "REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN"
  }
  private def str(v: String) = Json.fromString(v)
  private def strings(v: List[String]) = Json.arr(v.map(str): _*)
}

object OnboardingJson {
  private def str(v: String) = Json.fromString(v)
  private def id(v: UUID) = str(v.toString)
  private def time(v: Option[Instant]) = v.fold(Json.Null)(t => str(t.toString))
  def compatibility(a: NodeApiCompatibility): Json = Json.obj("serverVersion" -> a.serverVersion.fold(Json.Null)(str),
    "apiGeneration" -> a.apiGeneration.fold(Json.Null)(str),"sourceCommit" -> a.sourceCommit.fold(Json.Null)(str),
    "capabilities" -> Json.arr(a.capabilities.toList.map(_.code).sorted.map(str): _*),"provisioningReady" -> Json.fromBoolean(a.provisioningReady),
    "blocker" -> a.blocker.fold(Json.Null)(str))
  def run(r: RemnawaveNodeOnboardingRun): Json = Json.obj("id" -> id(r.id),"organizationId" -> id(r.organizationId),
    "integrationId" -> id(r.integrationId),"resourceId" -> id(r.resourceId),"requestId" -> r.requestId.fold(Json.Null)(id),
    "state" -> str(r.state.code),"phase" -> str(r.phase.code),"nodeName" -> str(r.snapshot.input.nodeName),
    "address" -> str(r.snapshot.input.address),"nodePort" -> Json.fromInt(r.snapshot.input.nodePort),
    "externalNodeId" -> r.externalNodeId.fold(Json.Null)(id),"baselineRunId" -> r.baselineRunId.fold(Json.Null)(id),
    "syncSessionId" -> r.syncSessionId.fold(Json.Null)(id),"failureCode" -> r.failureCode.fold(Json.Null)(str),
    "safeMessage" -> r.failureCode.fold(Json.Null)(_ => str("The onboarding did not complete. Review the recorded phase and existing node before creating a new preview.")),
    "createdAt" -> str(r.createdAt.toString),"updatedAt" -> str(r.updatedAt.toString),"startedAt" -> time(r.startedAt),"finishedAt" -> time(r.finishedAt))
  def detail(r: RemnawaveNodeOnboardingRun,p: List[OnboardingPhaseRecord]): Json = Json.obj("run" -> run(r),
    "phases" -> Json.arr(p.map(s => Json.obj("phase" -> str(s.phase.code),"state" -> str(s.state),
      "startedAt" -> time(s.startedAt),"finishedAt" -> time(s.finishedAt),"failureCode" -> s.failureCode.fold(Json.Null)(str))): _*))
}
