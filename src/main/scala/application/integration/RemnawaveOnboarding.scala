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
  def importCertificate(actor: ActorContext, integration: UUID, resourceId: UUID, domain: String,
    material: NodeTlsMaterial): IO[Json] = IO.raiseError(IntegrationError("REMNAWAVE_TLS_UNAVAILABLE","TLS import is unavailable"))
  def options(org: UUID, integration: UUID): IO[Json]
  def preview(actor: ActorContext, integration: UUID, input: OnboardingInput): IO[Json]
  def reconcile(actor: ActorContext, integration: UUID, run: UUID, action: String): IO[Json]
  def start(actor: ActorContext, integration: UUID, plan: UUID, request: UUID, confirmRecreate: Boolean = false): IO[RemnawaveNodeOnboardingRun]
  def detail(org: UUID, integration: UUID, id: UUID): IO[Json]
  def history(org: UUID, integration: UUID): IO[Json]
}

final class RemnawaveOnboarding[Tx[_]: MonadThrow](repo: RemnawaveOnboardingRepository[Tx],
  query: RemnawaveOnboardingQuery[Tx], integrations: IntegrationRepository[Tx], secrets: IntegrationSecretRepository[Tx],
  cryptography: IntegrationCryptography, providers: IntegrationProviderRegistry[IO], targets: ProvisioningTargetQuery[Tx],
  profiles: ServerProfiles[IO,Tx], inventory: IntegrationInventoryQuery[Tx], runner: TransactionRunner[IO,Tx],
  remote: RemnawaveNodeRemote[IO], audit: AuditRecorder[Tx], settings: ProvisioningSettings,
  provisioningPlans: ProvisioningRunRepository[Tx],
  panelSources: RemnawavePanelSourceResolver[IO] = integration.remnawave.RemnawavePanelDnsResolver.production(false),
  nodeAddresses: RemnawaveNodeAddressResolver[IO] = integration.remnawave.RemnawaveNodeAddressDnsResolver.production(),
  nodeCipher: Option[NodeInstallationCryptography] = None) extends RemnawaveOnboardingApi {
  override def importCertificate(actor: ActorContext, integration: UUID, resource: UUID, domain: String,
    material: NodeTlsMaterial): IO[Json] = for {
    _ <- context(actor.organizationId,integration)
    _ <- runner.run(targets.eligible(actor.organizationId,resource)).flatMap(_.leftMap(error).liftTo[IO])
    now <- IO.realTimeInstant
    metadata <- IO.delay(ru.bitec.app.ops.integration.secret.NodeTlsValidation.validate(domain,material,now))
      .flatMap(_.leftMap(error).liftTo[IO])
    cipher <- nodeCipher.liftTo[IO](error("REMNAWAVE_TLS_UNAVAILABLE"))
    id <- IO(UUID.randomUUID())
    certificate=NodeTlsCertificate(id,actor.organizationId,resource,domain,metadata.fingerprint,metadata.expiresAt)
    encrypted <- IO.delay(cipher.encryptTls(id,actor.organizationId,material))
    _ <- runner.run(repo.insertCertificate(certificate,encrypted) *>
      audit.record(actor,AuditAction.RemnawaveNodeCertificateImported,AuditTargetType.Integration,Some(integration)))
  } yield Json.obj("id"->str(id.toString),"domain"->str(domain),"fingerprint"->str(metadata.fingerprint),
    "expiresAt"->str(metadata.expiresAt.toString))
  private val sourcePolicy = new OnboardingPanelSources(query,runner,panelSources)
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
    eligible <- runner.run(targets.eligibleBatch(org,candidates.map(_.id)))
    statuses <- runner.run(query.serverStatuses(org,eligible))
    servers = candidates.map { candidate =>
      val target = eligible.getOrElse(candidate.id,Left("PROVISIONING_TARGET_NOT_FOUND"))
      val status = statuses.getOrElse(candidate.id,OnboardingServerStatus(None,None,false,"UNOBSERVED",false,false))
      val blocks = target.left.toOption.toList ++ Option.when(status.busy)("REMNAWAVE_ONBOARDING_RESOURCE_BUSY") ++
        Option.when(status.bindingConflict)("REMNAWAVE_ONBOARDING_BINDING_CONFLICT") ++
        Option.when(!status.profileAssigned)("REMNAWAVE_ONBOARDING_PROFILE_REQUIRED")
      val ip=target.toOption.flatMap(_.connection.config.get("host")).flatMap(NodeAddress.publicLiteral).getOrElse("")
      Json.obj("id" -> str(candidate.id.toString),"name" -> str(candidate.name),"address" -> str(ip),
        "environmentName" -> str(candidate.environmentName),"sshStatus" -> str(if(target.isRight) "TRUSTED" else "UNAVAILABLE"),
        "serverProfileName" -> status.profileName.fold(Json.Null)(str),
        "revisionNumber" -> status.revisionNumber.fold(Json.Null)(Json.fromInt),"serverProfileStatus" -> str(status.profileState),
        "blockingProblems" -> strings(blocks))
    }
    items <- profileInventory(org,integration)
  } yield Json.obj("nodeApi" -> OnboardingJson.compatibility(api),"servers" -> Json.arr(servers: _*),
    "profiles" -> Json.arr(items.collect { case item if item.obj.summary.isInstanceOf[RemnawaveConfigProfileSummary] =>
      val summary = item.obj.summary.asInstanceOf[RemnawaveConfigProfileSummary]
      Json.obj("id" -> str(item.obj.externalId),"name" -> str(item.obj.displayName),"inbounds" -> Json.arr(summary.inbounds.map(i =>
        Json.obj("id" -> str(i.uuid),"name" -> str(i.tag))): _*)) }: _*))

  def reconcile(actor: ActorContext,integration: UUID,id: UUID,action: String): IO[Json] = for {
    _ <- IO.raiseUnless(Set("RECOVER","DELETE_RECREATE")(action))(error("REMNAWAVE_ONBOARDING_INVALID_INPUT"))
    stored <- runner.run(repo.find(actor.organizationId,integration,id))
    r <- stored.map(_._1).filter(_.state.terminal).liftTo[IO](error("REMNAWAVE_ONBOARDING_NOT_FOUND"))
    selected=r.snapshot.input.copy(nodeAddressMode=if(r.snapshot.input.nodeAddressMode==NodeAddressMode.Domain) NodeAddressMode.Domain else NodeAddressMode.PublicIp)
    result <- preview(actor,integration,if(action=="RECOVER") selected.copy(panelSourceMode=PanelSourceMode.Auto,panelCidrs=Nil)
      else selected,action)
  } yield result

  def preview(actor: ActorContext,integration: UUID,raw: OnboardingInput): IO[Json] = preview(actor,integration,raw,"RECOVER")

  private def preview(actor: ActorContext,integration: UUID,raw: OnboardingInput,action: String): IO[Json] = for {
    _ <- IO.raiseUnless(settings.enabled)(error("PROVISIONING_DISABLED"))
    normalized <- raw.normalized.leftMap(error).liftTo[IO]
    c <- context(actor.organizationId,integration)
    source <- sourcePolicy.resolve(c._2,normalized.panelSourceMode,normalized.panelCidrs)
    target <- runner.run(targets.eligible(actor.organizationId,normalized.resourceId))
    addressEvidence <- if(normalized.nodeAddressMode==NodeAddressMode.Legacy) IO.pure(Option.empty[NodeAddressEvidence]) else for {
      connection <- target.leftMap(error).liftTo[IO].map(_.connection)
      evidence <- OnboardingNodeAddresses.resolve(normalized,connection,remote,nodeAddresses)
    } yield Some(evidence)
    input = normalized.copy(panelCidrs=source.sources,address=addressEvidence.fold(normalized.address)(_.address))
    certificate <- input.tlsCertificateId.traverse(id => runner.run(repo.certificate(actor.organizationId,input.resourceId,id)))
    certificateProblems = input.protocol.toList.collect { case h: RemnawaveProtocol.Hysteria2 =>
      if(input.tlsHttp01.isEmpty && !certificate.flatten.exists { case (c,_) => c.domain==h.serverName && c.expiresAt.isAfter(java.time.Instant.now().plusSeconds(7*86400)) })
        "REMNAWAVE_PROTOCOL_TLS_REQUIRED" else ""
    }.filter(_.nonEmpty)
    httpDns <- input.tlsHttp01.traverse(_ => target.toOption.traverse { t =>
      remote.publicNodeAddresses(t.connection).flatMap(ips => nodeAddresses.verifyDomain(
        input.protocol.get.asInstanceOf[RemnawaveProtocol.Hysteria2].serverName,ips)).attempt
    })
    api <- c._3.nodeProvisioning.get.inspect(c._2)
    candidates <- runner.run(query.candidates(actor.organizationId,500))
    items <- profileInventory(actor.organizationId,integration)
    profile = items.find(_.obj.externalId==input.configProfileId.toString)
    inbounds = profile.toList.flatMap(_.obj.summary match { case s: RemnawaveConfigProfileSummary => s.inbounds; case _ => Nil })
    baseline <- profiles.preview(actor,input.resourceId).attempt
    busy <- runner.run(repo.active(actor.organizationId,input.resourceId))
    live <- c._3.observe(c._2).attempt
    liveProfile = live.toOption.toList.flatMap(_.objects).find(o => o.objectType==IntegrationObjectType.ConfigProfile && o.externalId==input.configProfileId.toString)
    liveInboundIds = liveProfile.toList.flatMap(_.summary match { case p: RemnawaveConfigProfileSummary => p.inbounds.map(_.uuid); case _ => Nil }).toSet
    image = RemnawaveNodeImagePolicy.select(api)
    previous <- runner.run(repo.createdNodes(actor.organizationId,input.resourceId))
    candidate = RemnawaveNodeOnboardingRun.recoveryCandidate(previous,integration,input,image.getOrElse(""),
      target.toOption.fold(new UUID(0,0))(_.connectionId),reviewedAddressChange=addressEvidence.nonEmpty)
    proof <- OnboardingRecoveryObservation.inspect(candidate.toOption.flatten,input,c._2,c._3.nodeProvisioning.get,remote,target.toOption.map(_.connection),action,
      reviewedAddressChange=addressEvidence.nonEmpty)
    recovery=proof.recovery.map(r => if(r.action=="RECOVER" && r.localVerified) r.copy(action="REPAIR_PANEL_CONNECTIVITY") else r)
    connectivityFirewall <- recovery.filter(_.action=="REPAIR_PANEL_CONNECTIVITY").traverse(r =>
      target.toOption.traverse(t => remote.connectivitySources(t.connection,
        RemnawaveNodeRemoteSpec(r.installationOwnerId,input.resourceId,r.previousExternalNodeId.get,input.nodePort,
          r.previousImageReference.getOrElse(image.getOrElse("")),if(r.previousPanelCidrs.exists(_.nonEmpty)) r.previousPanelCidrs.get else input.panelCidrs,input.certificateId),
        r.previousPanelCidrs.getOrElse(Nil))).attempt)
    conflict <- runner.run(query.bindingConflict(actor.organizationId,input.resourceId,
      recovery.flatMap(_.previousExternalNodeId)))
    local <- if(previous.nonEmpty) IO.pure(List.empty[Either[Throwable,ProvisioningStepResult]]) else
      target.toOption.toList.traverse(t => remote.preflight(t.connection,input.resourceId,input.nodePort).attempt)
    blocks = (Option.when(!source.resolved)("REMNAWAVE_PANEL_SOURCE_UNRESOLVED").toList ++
      Option.when(recovery.exists(r => r.reusesNode && r.previousNodeAddress.nonEmpty) && !api.capabilities(NodeProvisioningCapability.AddressUpdate))("INTEGRATION_API_CONTRACT_UNCONFIRMED") ++
      Option.when(recovery.exists(r => (r.previousPanelCidrs.toList.flatten ++ input.panelCidrs).distinct.size>32))("REMNAWAVE_PANEL_SOURCE_LIMIT_EXCEEDED") ++
      Option.when(connectivityFirewall.exists(_.isLeft))("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY") ++
      recovery.toList.flatMap(_.connectivityProblem.map(_.code)) ++
      target.left.toOption.toList ++ api.blocker.toList ++ Option.when(!api.provisioningReady)("INTEGRATION_API_CONTRACT_UNCONFIRMED") ++
      Option.when(image.isEmpty)("REMNAWAVE_ONBOARDING_IMAGE_UNCONFIRMED") ++ Option.when(!c._1.enabled)("INTEGRATION_MANAGEMENT_REQUIRES_SYNC") ++
      Option.when(c._1.managementMode!=IntegrationManagementMode.ManagedSelected)("INTEGRATION_MANAGEMENT_MODE_REQUIRED") ++
      Option.when(input.protocol.nonEmpty && !api.capabilities(NodeProvisioningCapability.ProtocolProfileCreate))("INTEGRATION_API_CONTRACT_UNCONFIRMED") ++
      certificateProblems ++
      Option.when(httpDns.flatten.exists(_.isLeft))("REMNAWAVE_TLS_HTTP01_DNS_UNCONFIRMED") ++
      Option.when(input.protocol.isEmpty && (profile.isEmpty || liveProfile.isEmpty))("REMNAWAVE_ONBOARDING_CONFIG_PROFILE_MISSING") ++
      Option.when(input.protocol.isEmpty && !input.activeInboundIds.forall(id => inbounds.exists(_.uuid==id.toString) && liveInboundIds(id.toString)))("REMNAWAVE_ONBOARDING_INBOUND_INVALID") ++
      Option.when(busy)("REMNAWAVE_ONBOARDING_RESOURCE_BUSY") ++ Option.when(conflict)("REMNAWAVE_ONBOARDING_BINDING_CONFLICT") ++
      baseline.left.toOption.map(safeCode).toList ++ baseline.toOption.toList.flatMap(_.blockingProblems) ++
      local.toList.flatMap(_.fold(e => List(safeCode(e)),r => r.failureCode.toList ++ Option.when(r.uncertain)("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN"))) ++
      live.left.toOption.map(safeCode) ++ candidate.left.toOption ++
      proof.localInstallation.toList.flatMap(_.blocker) ++ recovery.toList.flatMap(r => r.state match {
        case "PRESENT_CONFLICT" => List("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW")
        case "UNKNOWN" => List("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN")
        case _ => Nil
      }) ++ Option.when(action=="DELETE_RECREATE" && recovery.isEmpty)("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW")).distinct
    pinned = baseline.toOption.flatMap(_.run.input.profileApply)
    zero = new UUID(0,0)
    planCorrelation = if(blocks.isEmpty && recovery.exists(!_.reusesNode)) UUID.randomUUID() else proof.correlation
    now <- IO.realTimeInstant
    snapshotDraft = OnboardingSnapshot(input,c._1.updatedAt,c._1.secretId,target.toOption.fold(zero)(_.connectionId),
      target.toOption.fold(now)(_.connectionUpdatedAt),pinned.fold(zero)(_.profileId),pinned.fold(zero)(_.revisionId),
      pinned.fold(0)(_.revisionNumber),pinned.fold(zero)(_.assignmentId),pinned.fold(0L)(_.assignmentVersion),
      pinned.fold("")(_.revisionHash),baseline.toOption.fold(zero)(_.run.id),baseline.toOption.exists(!_.assessment.compliant),api,
      image.getOrElse(""),planCorrelation,candidates.find(_.id==input.resourceId).fold("")(_.name),
      baseline.toOption.fold("")(_.profileName),input.protocol.fold(profile.fold("")(_.obj.displayName)) {
        case _: RemnawaveProtocol.Hysteria2 => "Hysteria2"
        case _: RemnawaveProtocol.Shadowsocks => "Shadowsocks"
      },
      input.activeInboundIds.flatMap(id => inbounds.find(_.uuid==id.toString).map(_.tag)),
      if(proof.localInstallation.exists(!_.state.repairable) || recovery.exists(r => Set("UNKNOWN","PRESENT_CONFLICT")(r.state))) Nil else
      if(recovery.exists(_.action=="REPAIR_PANEL_CONNECTIVITY")) List("RESOLVE_PANEL_SOURCE","ADD_PANEL_SOURCES","WAIT_FOR_PANEL",
        "FINALIZE_PANEL_SOURCES","SYNC_INVENTORY","BIND_RESOURCE","SET_DESIRED_STATE","FINAL_VERIFY") else
      List("RESOLVE_PANEL_SOURCE") ++
      baseline.toOption.toList.flatMap(_.assessment.modules.filterNot(_._2).map(_._1)) ++
        (if(recovery.exists(_.action=="DELETE_RECREATE")) List("DELETE_NODE","CONFIRM_NODE_DELETED") else Nil) ++
        (if(recovery.exists(r => !r.reusesNode && !r.localInstallation.exists(_.state==LocalInstallationState.Absent))) List("RETIRE_NODE_FIREWALL","RETIRE_LOCAL_NODE") else Nil) ++
        (if(recovery.exists(_.reusesNode)) Nil else List("CREATE_NODE")) ++
        List("CONFIGURE_NODE_FIREWALL", "INSTALL_NODE", "START_NODE", "WAIT_FOR_PANEL", "FINALIZE_PANEL_SOURCES", "SYNC_INVENTORY", "BIND_RESOURCE", "SET_DESIRED_STATE", "FINAL_VERIFY"),
      baseline.toOption.toList.flatMap(_.warnings) ++ Option.when(recovery.exists(_.reusesNode))("REMNAWAVE_ONBOARDING_REUSE_EXISTING_NODE") ++
        Option.when(input.tlsHttp01.nonEmpty)("REMNAWAVE_TLS_HTTP01_TEMPORARY_PORT_80"),blocks,recovery,
      lifecycleVersion=if(input.protocol.nonEmpty) 6 else 5,panelSource=Some(source),nodeAddress=addressEvidence)
    snapshot=snapshotDraft.copy(changes=if(blocks.nonEmpty) snapshotDraft.changes else
      baseline.toOption.toList.flatMap(_.assessment.modules.filterNot(_._2).map(_._1)) ++
        OnboardingPhase.forSnapshot(snapshotDraft).filterNot(p => p==OnboardingPhase.Validate || p==OnboardingPhase.PrepareServer).map(_.code))
    run = RemnawaveNodeOnboardingRun(UUID.randomUUID(),actor.organizationId,integration,input.resourceId,None,actor.userId,
      ProvisioningRunState.Planned,OnboardingPhase.Validate,snapshot,now,now,externalNodeId=proof.node,
      protocolBinding=if(recovery.nonEmpty) candidate.toOption.flatten.flatMap(_.protocolBinding) else None)
    _ <- if (blocks.nonEmpty) IO.unit else runner.run(for {
      current <- integrations.findByIdForUpdate(actor.organizationId,integration)
      _ <- repo.lockResource(actor.organizationId,input.resourceId)
      active <- repo.active(actor.organizationId,input.resourceId)
      _ <- MonadThrow[Tx].raiseWhen(active)(error("REMNAWAVE_ONBOARDING_RESOURCE_BUSY"))
      currentNodes <- repo.createdNodes(actor.organizationId,input.resourceId)
      _ <- MonadThrow[Tx].raiseUnless(currentNodes==previous)(error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
      valid <- profiles.valid(actor.organizationId,input.resourceId,pinned.get)
      unchanged <- targets.unchanged(baseline.toOption.get.run.input)
      _ <- MonadThrow[Tx].raiseUnless(valid && unchanged && current.exists(i => i.updatedAt==c._1.updatedAt && i.secretId==c._1.secretId))(
        error("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED"))
      _ <- repo.insertPlan(run)
    } yield ())
  } yield Json.obj("run" -> OnboardingJson.run(run),"serverName" -> str(snapshot.serverName),
    "serverProfileName" -> str(snapshot.serverProfileName),"revisionNumber" -> Json.fromInt(snapshot.revisionNumber),
    "configProfileName" -> str(snapshot.configProfileName),"inboundNames" -> strings(snapshot.inboundNames),
    "nodeImage" -> image.fold(Json.Null)(str),"nodeApi" -> OnboardingJson.compatibility(api),
    "panelCidrs" -> strings(input.panelCidrs),"panelSource" -> PanelSourceEvidence.encode(source),
    "input" -> Json.obj("resourceId" -> str(input.resourceId.toString),"nodeName" -> str(input.nodeName),"address" -> str(input.address),
      "nodePort" -> Json.fromInt(input.nodePort),"configProfileId" -> str(input.configProfileId.toString),
      "activeInboundIds" -> strings(input.activeInboundIds.map(_.toString)),"desiredState" -> str(input.desiredState),
      "nodeAddressMode" -> str(input.nodeAddressMode.code)).deepMerge(input.protocol.fold(Json.obj())(p => Json.obj("protocol" -> RemnawaveProtocol.encode(p))))
      .deepMerge(input.tlsCertificateId.fold(Json.obj())(id => Json.obj("tlsCertificateId"->str(id.toString))))
      .deepMerge(input.tlsHttp01.fold(Json.obj())(h => Json.obj("tlsHttp01" -> Json.obj("certificateId" -> str(h.certificateId.toString),
        "email" -> str(h.email), "agreeTerms" -> Json.True)))),
    "changes" -> strings(snapshot.changes),"warnings" -> strings(snapshot.warnings),
    "packageFindings" -> Json.fromValues(baseline.toOption.toList.flatMap(_.packageFindings).map(_.json)),
    "blockingProblems" -> strings(blocks),"localInstallationState" -> proof.localState.fold(Json.Null)(s => str(s.code)),
    "localInstallation" -> proof.localInstallation.fold(Json.Null)(OnboardingSnapshotCodec.encodeLocalInstallation),
    "recovery" -> recovery.fold(Json.Null)(OnboardingSnapshotCodec.encodeRecovery))

  def start(actor: ActorContext,integration: UUID,plan: UUID,request: UUID,confirmRecreate: Boolean): IO[RemnawaveNodeOnboardingRun] = for {
    _ <- IO.raiseUnless(settings.enabled)(error("PROVISIONING_DISABLED"))
    draft <- runner.run(repo.find(actor.organizationId,integration,plan)).flatMap(
      _.map(_._1).liftTo[IO](error("REMNAWAVE_ONBOARDING_NOT_FOUND")))
    _ <- if(draft.state != ProvisioningRunState.Planned || draft.snapshot.panelSource.isEmpty) IO.unit else
      context(actor.organizationId,integration).flatMap { case(_,runtime,_) =>
        sourcePolicy.resolve(runtime,draft.snapshot.input.panelSourceMode,draft.snapshot.input.panelCidrs).flatMap(current =>
          IO.raiseUnless(current.resolved && draft.snapshot.panelSource.contains(current))(error("REMNAWAVE_ONBOARDING_SOURCE_CHANGED")))
      }
    _ <- if(draft.state != ProvisioningRunState.Planned) IO.unit else draft.snapshot.nodeAddress.traverse_ { reviewed =>
      runner.run(targets.eligible(actor.organizationId,draft.resourceId)).flatMap(_.leftMap(error).liftTo[IO]).flatMap { target =>
        remote.publicNodeAddresses(target.connection).flatMap { ips =>
          IO.raiseUnless(ips==reviewed.publicAddresses)(error("REMNAWAVE_ONBOARDING_SOURCE_CHANGED")) *>
            (if(reviewed.mode==NodeAddressMode.Domain) nodeAddresses.verifyDomain(reviewed.address,ips).void else IO.unit)
        }
      }
    }
    now <- IO.realTimeInstant
    result <- runner.run(for {
      outcome <- repo.startResult(actor.organizationId,integration,plan,request,actor.userId,now,confirmRecreate)
      started = outcome.run
      _ <- if(!outcome.newlyStarted) MonadThrow[Tx].unit else for {
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
    _.liftTo[IO](error("REMNAWAVE_ONBOARDING_NOT_FOUND"))).flatMap { case(r,p) =>
      r.snapshot.input.certificateId.traverse(cert => runner.run(repo.certificateMetadata(org,r.resourceId,cert))).map { metadata =>
        OnboardingJson.detail(r,p).deepMerge(Json.obj("certificate" -> metadata.flatten.fold(Json.Null)(c =>
          Json.obj("domain" -> str(c.domain),"expiresAt" -> str(c.expiresAt.toString),"automaticRenewal" -> Json.False))))
      }
    }
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
    "correlationId" -> id(r.snapshot.correlationId),
    "externalNodeId" -> r.externalNodeId.fold(Json.Null)(id),"baselineRunId" -> r.baselineRunId.fold(Json.Null)(id),
    "recovery" -> r.snapshot.recovery.fold(Json.Null)(OnboardingSnapshotCodec.encodeRecovery),
    "panelSource" -> r.snapshot.panelSource.fold(Json.Null)(PanelSourceEvidence.encode),
    "observedPanelSource" -> r.observedPanelSource.fold(Json.Null)(PanelSourceObservation.encode),
    "connectivityCompletion" -> r.connectivityCompletion.fold(Json.Null)(c => str(c.code)),
    "protocol" -> r.snapshot.input.protocol.fold(Json.Null)(RemnawaveProtocol.encode),
    "protocolBinding" -> r.protocolBinding.fold(Json.Null)(NodeProtocolBinding.encode),
    "clientTrafficVerification" -> (if(r.snapshot.input.protocol.nonEmpty) str("NOT_RUN") else Json.Null),
    "connectivityFinding" -> r.connectivityFinding.fold(Json.Null)(PanelConnectivityFinding.encode),
    "syncSessionId" -> r.syncSessionId.fold(Json.Null)(id),"failureCode" -> r.failureCode.fold(Json.Null)(str),
    "safeMessage" -> r.failureCode.fold(Json.Null)(_ => str("The onboarding did not complete. Review the recorded phase and existing node before creating a new preview.")),
    "createdAt" -> str(r.createdAt.toString),"updatedAt" -> str(r.updatedAt.toString),"startedAt" -> time(r.startedAt),"finishedAt" -> time(r.finishedAt))
  def detail(r: RemnawaveNodeOnboardingRun,p: List[OnboardingPhaseRecord]): Json = Json.obj("run" -> run(r),
    "phases" -> Json.arr(p.map(s => Json.obj("phase" -> str(s.phase.code),"state" -> str(s.state),
      "outcome" -> phaseOutcome(r,s).fold(Json.Null)(str),
      "startedAt" -> time(s.startedAt),"finishedAt" -> time(s.finishedAt),"failureCode" -> s.failureCode.fold(Json.Null)(str))): _*))
  private def phaseOutcome(r: RemnawaveNodeOnboardingRun,s: OnboardingPhaseRecord): Option[String] = {
    import OnboardingPhase._
    val compensated=r.connectivityCompletion.contains(PanelConnectivityCompletion.RolledBack) ||
      r.snapshot.lifecycleVersion==4 && r.failureCode.contains("REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT") && r.phase==FinalizePanelSources
    s.phase match {
      case FinalizePanelSources if compensated => Some(if(r.connectivityFinding.nonEmpty) "ROLLED_BACK" else "ROLLED_BACK_UNKNOWN")
      case FinalizePanelSources if r.connectivityCompletion.contains(PanelConnectivityCompletion.Promoted) => Some("CONNECTED")
      case WaitForPanel if s.state=="SUCCEEDED" =>
        if(r.observedPanelSource.exists(_.status!=PanelSourceObservationStatus.NotRequired)) Some("NOT_CONNECTED")
        else if(compensated && r.connectivityFinding.isEmpty) Some("NOT_CONFIRMED")
        else r.connectivityFinding.map(f => if(f.connected) "CONNECTED" else "NOT_CONNECTED")
          .orElse(Option.when(r.snapshot.lifecycleVersion>=4)("NOT_CONFIRMED"))
      case VerifyObservedPanel if s.state=="SUCCEEDED" =>
        if(!r.observedPanelSource.exists(_.status==PanelSourceObservationStatus.Observed)) Some("NOT_REQUIRED")
        else r.connectivityFinding.map(f => if(f.connected) "CONNECTED" else "NOT_CONNECTED")
      case ObservePanelSource if s.state=="SUCCEEDED" => r.observedPanelSource.map(_.status.code)
      case AddObservedPanelSource if s.state=="SUCCEEDED" && !r.observedPanelSource.exists(_.status==PanelSourceObservationStatus.Observed) => Some("NOT_REQUIRED")
      case _ => None
    }
  }
}
