package ru.bitec.app.ops
package application.integration

import application.auth.ActorContext
import application.audit.AuditRecorder
import application.port._
import application.provisioning.{ProvisioningRuns,ProvisioningSettings}
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction,AuditTargetType}
import domain.integration._
import domain.provisioning.ProvisioningRunState
import java.time.Instant
import java.util.UUID
import org.typelevel.log4cats.Logger
import scala.concurrent.duration._

final case class RemnawaveOnboardingSettings(panelPoll: FiniteDuration = 5.seconds,
  panelTimeout: FiniteDuration = 180.seconds, baselineTimeout: FiniteDuration = 3600.seconds,
  syncTimeout: FiniteDuration = 900.seconds) {
  require(panelPoll>=1.second && panelTimeout>panelPoll && baselineTimeout>panelPoll && syncTimeout>panelPoll)
}

/** Durable boundaries, short fenced transactions and read-only recovery before any uncertain mutation. */
final class RemnawaveOnboardingWorker[Tx[_]: MonadThrow](repo: RemnawaveOnboardingRepository[Tx],
  operations: RemnawaveOnboardingOperations[IO], providers: IntegrationProviderRegistry[IO], remote: RemnawaveNodeRemote[IO],
  cipher: NodeInstallationCryptography, runner: TransactionRunner[IO,Tx], audit: AuditRecorder[Tx],
  settings: ProvisioningSettings, logger: Logger[IO], polling: RemnawaveOnboardingSettings = RemnawaveOnboardingSettings(),
  owner: UUID = UUID.randomUUID(), clock: IO[Instant] = IO.realTimeInstant,
  panelSources: RemnawavePanelSourceResolver[IO] = integration.remnawave.RemnawavePanelDnsResolver.production(false),
  panelSourcePolicy: Option[OnboardingPanelSources[Tx]] = None,
  nodeAddresses: RemnawaveNodeAddressResolver[IO] = integration.remnawave.RemnawaveNodeAddressDnsResolver.production()) {
  private case object LostLease extends RuntimeException("Onboarding lease lost")
  private sealed trait Decision
  private case class Advance(run: RemnawaveNodeOnboardingRun) extends Decision
  private case class Wait(run: RemnawaveNodeOnboardingRun) extends Decision
  private case class Stop(code: String, unknown: Boolean, updated: Option[RemnawaveNodeOnboardingRun] = None) extends Decision
  def run: IO[Nothing] = (tick.handleErrorWith(_ => logger.warn("remnawave.onboarding.poll_failed")) *> IO.sleep(settings.pollInterval)).foreverM
  def tick: IO[Unit] = if (!settings.enabled) IO.unit else for {
    now <- clock
    _ <- runner.run(repo.cleanupPlans(now.minusMillis(ProvisioningRuns.PlanLifetime24h.toMillis),100))
    token <- IO(UUID.randomUUID())
    claimed <- runner.run(repo.claim(owner,token,now,now.plusMillis(settings.leaseDuration.toMillis),math.min(settings.batchSize,settings.maxConcurrency)))
    _ <- claimed.parTraverse_(r => heartbeat(r,token)(process(r,token)).handleErrorWith {
      case LostLease => IO.unit
      case _ => logger.warn(s"remnawave.onboarding.worker_failed onboardingId=${r.id}")
    })
  } yield ()
  private def heartbeat[A](r: RemnawaveNodeOnboardingRun,token: UUID)(io: IO[A]): IO[A] = {
    def loop: IO[Unit] = IO.sleep(settings.heartbeat) *> clock.flatMap(now =>
      runner.run(repo.renew(r,token,now,now.plusMillis(settings.leaseDuration.toMillis))).flatMap(ok =>
        if(ok) loop else IO.raiseError(LostLease)))
    io.race(loop).flatMap { case Left(v) => IO.pure(v); case Right(_) => IO.raiseError(LostLease) }
  }
  private def process(r: RemnawaveNodeOnboardingRun,token: UUID): IO[Unit] = for {
    now <- clock
    lease <- runner.run(repo.renew(r,token,now,now.plusMillis(settings.leaseDuration.toMillis)))
    _ <- IO.raiseUnless(lease)(LostLease)
    fresh <- runner.run(repo.beginPhase(r,token,now))
    decision <- execute(r,token,fresh).timeoutTo(settings.stepTimeout,IO.pure(Stop("REMNAWAVE_ONBOARDING_PHASE_TIMEOUT",unknown=true)))
      .handleErrorWith {
        case LostLease => IO.raiseError(LostLease)
        case e: IntegrationError if e.code=="REMNAWAVE_ONBOARDING_LEASE_LOST" => IO.raiseError(LostLease)
        case e: IntegrationError => IO.pure(Stop(e.code,
          e.code.contains("UNKNOWN") || e.code=="INTEGRATION_REMOTE_UNAVAILABLE" ||
          e.code=="REMNAWAVE_PANEL_CONNECTIVITY_CONFIRMATION_LOST" ||
          (r.phase.mutation && !fresh) || r.phase == OnboardingPhase.WaitForPanel || r.phase == OnboardingPhase.FinalVerify))
        case _ => IO.pure(Stop("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN",unknown=true))
      }
    at <- clock
    _ <- decision match {
      case Advance(updated) =>
        val next = OnboardingPhase.next(r.phase,r.snapshot)
        val savedRun = updated.copy(phase=next.getOrElse(r.phase),state=if(next.isEmpty) ProvisioningRunState.Succeeded else ProvisioningRunState.Running)
        persist(r,token,savedRun,at,complete=true) *> (if(next.isEmpty) IO.unit else process(savedRun,token))
      case Wait(updated) => persist(r,token,updated,at,complete=false) *> IO.sleep(polling.panelPoll) *> process(updated,token)
      case Stop(code,unknown,updated) => persist(r,token,updated.getOrElse(r).copy(state=if(unknown) ProvisioningRunState.Unknown else ProvisioningRunState.Failed,
        failureCode=Some(code)),at,complete=false)
    }
  } yield ()
  private def persist(r: RemnawaveNodeOnboardingRun,token: UUID,n: RemnawaveNodeOnboardingRun,now: Instant,complete: Boolean): IO[Unit] =
    runner.run(for {
      _ <- if(n.state.terminal) repo.deleteSecret(r,token,now) else MonadThrow[Tx].unit
      saved <- repo.persist(r,token,n,now,complete)
      _ <- MonadThrow[Tx].raiseUnless(saved)(LostLease)
      _ <- if(n.state.terminal) audit.record(ActorContext(r.createdBy,r.organizationId),AuditAction.RemnawaveNodeOnboardingCompleted,
        AuditTargetType.Integration,Some(r.integrationId)) else MonadThrow[Tx].unit
    } yield ()) *> logger.info(s"remnawave.onboarding.phase onboardingId=${r.id} organizationId=${r.organizationId} integrationId=${r.integrationId} resourceId=${r.resourceId} phase=${r.phase.code} state=${n.state.code}")
  private def elapsed(r: RemnawaveNodeOnboardingRun,timeout: FiniteDuration): IO[Boolean] = for {
    now <- clock
    stored <- runner.run(repo.find(r.organizationId,r.integrationId,r.id))
    started = stored.toList.flatMap(_._2).find(_.phase==r.phase).flatMap(_.startedAt).getOrElse(r.createdAt)
  } yield !now.isBefore(started.plusMillis(timeout.toMillis))
  private def spec(r: RemnawaveNodeOnboardingRun) = RemnawaveNodeRemoteSpec(RemnawaveNodeOnboardingRun.installationOwner(r),r.resourceId,r.externalNodeId.get,
    r.snapshot.input.nodePort,r.snapshot.installationImageReference,r.effectivePanelSources,r.snapshot.input.certificateId)
  private def provider = providers.find(IntegrationProviderType.Remnawave).get
  private def matches(r: RemnawaveNodeOnboardingRun,n: ProvisionedNode): Boolean = {
    val i = r.intent
    n.name==i.name && n.address==i.address && n.port.contains(i.port) && n.configProfileId.contains(i.configProfileId) &&
      n.activeInboundIds.toSet==i.activeInboundIds.toSet &&
      n.correlationTags.contains("ID:"+i.correlationId.toString.replace("-","").toUpperCase(java.util.Locale.ROOT)) &&
      r.externalNodeId.forall(_==n.externalId)
  }
  /** Recoverable inventory observation. The session this phase depends on is named durably before
    * the provider is read, so a restart reads that exact session instead of starting another one,
    * and an already running synchronization of the same integration is waited for rather than
    * mistaken for a failure. A COMPLETED session counts only once the stored inventory holds the
    * node this onboarding created, whoever observed it.
    */
  private def syncInventory(r: RemnawaveNodeOnboardingRun,token: UUID): IO[Decision] = r.syncSessionId match {
    case None => syncClaim(r,token)
    case Some(id) => operations.syncSession(r,id).flatMap {
      case None => IO.pure[Decision](Stop("REMNAWAVE_ONBOARDING_SYNC_UNKNOWN",true))
      // The deadline of the session itself is the one stale policy; past it, claiming again lets
      // the existing recovery retire the abandoned session rather than waiting for nothing.
      case Some(session) if session.status==IntegrationSyncStatus.Running => clock.flatMap(at =>
        if (at.isBefore(session.recoverAfterAt)) syncWaiting(r) else syncClaim(r,token))
      case Some(session) => logger.info(s"remnawave.onboarding.sync.recovered onboardingId=${r.id} " +
        s"phase=${r.phase.code} syncSessionId=${session.id} status=${session.status.code} recovered=true") *>
        syncFinished(r,token,session,true)
    }
  }
  private def syncWaiting(r: RemnawaveNodeOnboardingRun): IO[Decision] = elapsed(r,polling.syncTimeout).map(expired =>
    if (expired) Stop("REMNAWAVE_ONBOARDING_SYNC_TIMEOUT",true,Some(r)) else Wait(r))
  private def syncClaim(r: RemnawaveNodeOnboardingRun,token: UUID): IO[Decision] = operations.claimSync(r,token).flatMap {
    case OnboardingSyncClaim.Owned(claimed,observe) =>
      val owned = r.copy(syncSessionId=Some(claimed.id))
      observe.flatMap(session => syncFinished(owned,token,session,false))
    case OnboardingSyncClaim.Foreign(session) =>
      logger.info(s"remnawave.onboarding.sync.attached onboardingId=${r.id} phase=${r.phase.code} " +
        s"syncSessionId=${session.id} owned=false recovered=true") *> syncWaiting(r.copy(syncSessionId=Some(session.id)))
  }
  private def syncFinished(r: RemnawaveNodeOnboardingRun,token: UUID,session: IntegrationSyncSession,
    retry: Boolean): IO[Decision] = session.status match {
    case IntegrationSyncStatus.Completed => operations.inventoryHasNode(r).flatMap(found =>
      if (found) IO.pure[Decision](Advance(r))
      // A snapshot taken before this node existed is not evidence of it. One fresh observation is
      // started per pass, and the phase timeout bounds how long that may repeat.
      else if (retry) syncClaim(r,token) else syncWaiting(r))
    case IntegrationSyncStatus.Failed => IO.pure[Decision](Stop(
      session.errorCode.getOrElse("REMNAWAVE_ONBOARDING_SYNC_FAILED"),false,Some(r)))
    case _ => syncWaiting(r)
  }
  private def execute(r: RemnawaveNodeOnboardingRun,token: UUID,fresh: Boolean): IO[Decision] = {
    import OnboardingPhase._
    r.phase match {
      case PrepareServer if r.snapshot.baselineNeeded => for {
        _ <- operations.validate(r,requireCompliant=false)
        // A crash between the attach and the start leaves the approved child PLANNED; it is started
        // here, idempotently and only while it is still PLANNED, instead of being polled to timeout.
        child <- operations.ensureBaselineStarted(r,token)
        _ <- if (r.baselineRunId.isEmpty) IO.unit else logger.info(s"remnawave.onboarding.baseline.recovered " +
          s"onboardingId=${r.id} phase=${r.phase.code} baselineRunId=${child.id} childState=${child.state.code} recovered=true")
        updated = r.copy(baselineRunId=Some(child.id))
        expired <- elapsed(r,polling.baselineTimeout)
        decision <- child.state match {
          case ProvisioningRunState.Succeeded => operations.validate(updated,requireCompliant=true).as[Decision](Advance(updated))
          case ProvisioningRunState.Failed => IO.pure[Decision](Stop(child.failureCode.getOrElse("REMNAWAVE_ONBOARDING_BASELINE_FAILED"),false,Some(updated)))
          case ProvisioningRunState.Unknown => IO.pure[Decision](Stop(child.failureCode.getOrElse("REMNAWAVE_ONBOARDING_BASELINE_UNKNOWN"),true,Some(updated)))
          case _ if expired => IO.pure[Decision](Stop("REMNAWAVE_ONBOARDING_BASELINE_TIMEOUT",true,Some(updated)))
          case _ => IO.pure[Decision](Wait(updated))
        }
      } yield decision
      case _ => operations.runtime(r).flatMap { case(context,connection) =>
        val nodes = provider.nodeProvisioning.get
        def advance: IO[Decision] = IO.pure(Advance(r))
        def exactNode(id: UUID,intent: NodeCreateIntent): IO[Either[Stop,ProvisionedNode]] =
          nodes.lookupNode(context,id).flatMap {
            case NodeLookupOutcome.Found(n) if OnboardingRecovery.matches(n,intent,Some(id)) => nodes.findNodes(context).map { candidates =>
              if(candidates.exists(other => other.externalId!=id && OnboardingRecovery.candidate(other,intent)))
                Left(Stop("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW",false)) else Right(n)
            }
            case NodeLookupOutcome.Found(_) => IO.pure(Left(Stop("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW",false)))
            case NodeLookupOutcome.ConfirmedNotFound => IO.pure(Left(Stop("REMNAWAVE_ONBOARDING_NODE_NOT_FOUND",false)))
            case _ => IO.pure(Left(Stop("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN",true)))
          }.handleError(_ => Left(Stop("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN",true)))
        def existing: IO[Decision] = exactNode(r.externalNodeId.get,r.intent).map(_.fold(identity,_ => Advance(r)))
        def enableExisting: IO[Decision] = if(!r.snapshot.recovery.exists(_.reusesNode)) advance else
          exactNode(r.externalNodeId.get,r.intent).flatMap {
            case Right(n) if n.disabled =>
              provider.executeAction(context,n.externalId.toString,IntegrationActionCode.NodeEnable).flatMap {
                case IntegrationActionRemoteOutcome.Succeeded => advance
                case IntegrationActionRemoteOutcome.DefinitelyFailed(code) => IO.pure(Stop(code,false))
                case IntegrationActionRemoteOutcome.OutcomeUnknown(code) => nodes.lookupNode(context,n.externalId).map {
                  case NodeLookupOutcome.Found(current) if matches(r,current) && !current.disabled => Advance(r)
                  case _ => Stop(code,true)
                }
              }
            case Right(_) => advance
            case Left(stop) => IO.pure(stop)
          }
        def oldSpec(proof: OnboardingRecovery) = RemnawaveNodeRemoteSpec(proof.installationOwnerId,r.resourceId,
          proof.previousExternalNodeId.get,r.snapshot.input.nodePort,proof.previousImageReference.getOrElse(r.snapshot.imageReference),proof.previousPanelCidrs.getOrElse(r.snapshot.input.panelCidrs),r.snapshot.input.certificateId)
        def previousSources = r.snapshot.recovery.flatMap(_.previousPanelCidrs).getOrElse(r.snapshot.input.panelCidrs)
        def unionSources = (previousSources ++ r.snapshot.input.panelCidrs ++ r.effectivePanelSources).distinct.sorted
        def currentSpec = if(r.snapshot.connectivityRepair || r.observedPanelSource.exists(_.status==PanelSourceObservationStatus.Observed))
          spec(r).copy(panelCidrs=unionSources) else spec(r)
        def validateSources: IO[Unit] = r.snapshot.panelSource.traverse_(reviewed =>
          panelSourcePolicy.fold(panelSources.resolve(context.baseUrl,r.snapshot.input.panelSourceMode,r.snapshot.input.panelCidrs))(
            _.resolve(context,r.snapshot.input.panelSourceMode,r.snapshot.input.panelCidrs)).flatMap(current =>
            IO.raiseUnless(current.resolved && current==reviewed)(IntegrationError("REMNAWAVE_ONBOARDING_SOURCE_CHANGED","Review fresh Panel source evidence"))))
        def finding(connected: Boolean) = PanelConnectivityFinding(r.effectivePanelSources,
          if(r.observedPanelSource.exists(_.status==PanelSourceObservationStatus.Observed)) "AUTO_OBSERVED"
          else r.snapshot.panelSource.fold("LEGACY_MANUAL")(_.confidence),connected)
        def probeSpec: IO[PanelSynProbeSpec] = runner.run(repo.find(r.organizationId,r.integrationId,r.id)).flatMap(stored =>
          stored.toList.flatMap(_._2).find(_.phase==ObservePanelSource).flatMap(_.startedAt)
            .liftTo[IO](IntegrationError("REMNAWAVE_PANEL_SOURCE_PROBE_UNKNOWN","Missing durable probe lease"))
            .map(at => PanelSynProbeSpec(r.id,currentSpec,at.plusSeconds(PanelSynProbeSpec.WindowSeconds))))
        def oldAbsent: IO[Unit] = r.snapshot.recovery.filter(!_.reusesNode).traverse_ { proof =>
          nodes.lookupNode(context,proof.previousExternalNodeId.get).flatMap {
            case NodeLookupOutcome.ConfirmedNotFound => nodes.findNodes(context).flatMap(candidates => IO.raiseWhen(
              candidates.exists(OnboardingRecovery.candidate(_,r.intent.copy(correlationId=proof.previousCorrelationId))))(
                IntegrationError("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW","Conflicting candidate exists")))
            case NodeLookupOutcome.Found(_) => IO.raiseError(IntegrationError("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW","Previous node still exists"))
            case _ => IO.raiseError(IntegrationError("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN","Previous node absence is unproven"))
          }
        }
        def freshLocal(requireAbsent: Boolean): IO[Unit] =
          r.snapshot.recovery.filter(_ => r.snapshot.lifecycleVersion >= 3).traverse_ { proof =>
            remote.localInstallationObservation(connection,if(proof.reusesNode && (!r.snapshot.connectivityRepair || previousSources.isEmpty)) spec(r) else oldSpec(proof)).flatMap { current =>
              current.blocker match {
                case Some(code) => IO.raiseError(IntegrationError(code,"Local installation could not be safely verified"))
                case None => IO.raiseUnless(if(requireAbsent) current.state==LocalInstallationState.Absent
                  else proof.localInstallation.exists(_.state==current.state))(
                    IntegrationError("REMNAWAVE_ONBOARDING_PREVIEW_CHANGED","Local installation changed; review a fresh plan"))
              }
            }
          }
        def reconciliation: IO[Decision] = nodes.reconcileCreate(context,r.intent,r.snapshot.compatibility).map {
          case NodeCreateReconciliation.Confirmed(n) if matches(r,n) => Advance(r.copy(externalNodeId=Some(n.externalId)))
          case _ => Stop("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN",true)
        }
        def mutationResult(io: IO[ProvisioningStepResult]): IO[Decision] =
          io.map(x => if(x.uncertain || x.outputTruncated)
            Stop(x.failureCode.getOrElse("REMNAWAVE_ONBOARDING_REMOTE_UNKNOWN"),true)
            else x.failureCode.fold[Decision](Advance(r))(code => Stop(code,false)))
        def result(io: IO[ProvisioningStepResult]): IO[Decision] = {
          if(r.snapshot.recovery.exists(_.reusesNode)) existing.flatMap {
            case _: Advance => mutationResult(io)
            case other => IO.pure(other)
          } else mutationResult(io)
        }
        r.phase match {
          case IssueTls => for {
            _ <- operations.validate(r,true)
            request=r.snapshot.input.tlsHttp01.get
            serverName=r.snapshot.input.protocol.get.asInstanceOf[RemnawaveProtocol.Hysteria2].serverName
            stored <- runner.run(repo.certificate(r.organizationId,r.resourceId,request.certificateId))
            _ <- stored match {
              case Some((cert,_)) => clock.flatMap { now =>
                IO.raiseUnless(cert.domain==serverName)(IntegrationError("REMNAWAVE_TLS_REFERENCE_INVALID","Certificate scope changed")) *>
                  IO.raiseUnless(now.plusSeconds(7*86400).isBefore(cert.expiresAt))(
                    IntegrationError("REMNAWAVE_TLS_EXPIRY_TOO_CLOSE","Certificate expires too soon"))
              }
              case None => for {
                _ <- remote.publicNodeAddresses(connection).flatMap(ips => nodeAddresses.verifyDomain(serverName,ips)).void
                phase <- runner.run(repo.find(r.organizationId,r.integrationId,r.id))
                at <- phase.toList.flatMap(_._2).find(_.phase==IssueTls).flatMap(_.startedAt)
                  .liftTo[IO](IntegrationError("REMNAWAVE_TLS_HTTP01_UNKNOWN","Missing durable issuance lease"))
                material <- remote.issueCertificate(connection,r.resourceId,serverName,request,at.plusSeconds(210),fresh)
                  .guarantee(remote.cleanupCertificateProbe(connection,r.resourceId,request))
                now <- clock
                metadata <- IO.delay(integration.secret.NodeTlsValidation.validate(serverName,material,now))
                  .flatMap(_.leftMap(code => IntegrationError(code,"Issued certificate is invalid")).liftTo[IO])
                cert=NodeTlsCertificate(request.certificateId,r.organizationId,r.resourceId,serverName,metadata.fingerprint,metadata.expiresAt)
                encrypted <- IO.delay(cipher.encryptTls(cert.id,r.organizationId,material))
                _ <- runner.run(repo.saveIssuedCertificate(r,token,cert,encrypted,now))
              } yield ()
            }
          } yield Advance(r)
          case CreateProtocolProfile if r.protocolBinding.nonEmpty => provider.configProfiles.get.fetchConfigProfile(context,
            r.protocolBinding.get.profileId.toString).flatMap { actual =>
            IO.raiseUnless(domain.configuration.CanonicalJson.sha256(actual.config)==r.protocolBinding.get.configSha256)(
              IntegrationError("REMNAWAVE_PROTOCOL_PROFILE_CHANGED","The reviewed profile changed")) *> advance
          }
          case CreateProtocolProfile => for {
            _ <- operations.validate(r,true)
            protocol <- r.snapshot.input.protocol.liftTo[IO](IntegrationError("REMNAWAVE_PROTOCOL_INVALID","Protocol intent is unavailable"))
            tag=RemnawaveProtocol.inboundTag(r.organizationId,r.resourceId,protocol)
            config=RemnawaveProtocolPreset.render(protocol,tag)
            correlation=if(r.protocolBinding.nonEmpty) r.snapshot.recovery.fold(r.snapshot.correlationId)(_.previousCorrelationId) else r.snapshot.correlationId
            name="ID_"+correlation.toString.replace("-","").take(24)
            outcome <- nodes.ensureProtocolProfile(context,name,tag,config,r.snapshot.compatibility,fresh && r.protocolBinding.isEmpty)
          } yield outcome match {
            case ProtocolProfileOutcome.Confirmed(binding) if r.protocolBinding.forall(_==binding) => Advance(r.copy(protocolBinding=Some(binding)))
            case ProtocolProfileOutcome.Confirmed(_) => Stop("REMNAWAVE_PROTOCOL_PROFILE_CONFLICT",false)
            case ProtocolProfileOutcome.Rejected(code) => Stop(code,false)
            case ProtocolProfileOutcome.Unknown(code) => Stop(code,true)
          }
          case InstallTls => operations.validate(r,true) *> {
              val id=r.snapshot.input.certificateId.get
              runner.run(repo.certificate(r.organizationId,r.resourceId,id)).flatMap(
                _.liftTo[IO](IntegrationError("REMNAWAVE_TLS_REFERENCE_INVALID","Certificate is unavailable"))).flatMap { case(cert,secret) =>
                IO.delay(cipher.decryptTls(secret)).flatMap { material =>
                  IO.delay(integration.secret.NodeTlsValidation.validate(cert.domain,material,java.time.Instant.now()))
                    .flatMap(_.leftMap(code => IntegrationError(code,"Certificate is invalid")).liftTo[IO]) *>
                  mutationResult(remote.installCertificate(connection,spec(r),cert,material))
                }
              }
            }
          case ConfigureClientFirewall => operations.validate(r,true) *>
            result(remote.configureClientFirewall(connection,spec(r),r.snapshot.input.protocol.get))
          case VerifyProtocol => operations.validate(r,true) *> provider.configProfiles.get.fetchConfigProfile(context,
            r.effectiveInput.configProfileId.toString).flatMap { actual =>
            IO.raiseUnless(r.protocolBinding.exists(_.configSha256==domain.configuration.CanonicalJson.sha256(actual.config)))(
              IntegrationError("REMNAWAVE_PROTOCOL_PROFILE_CHANGED","The reviewed protocol configuration changed")) *>
              result(remote.verifyProtocol(connection,spec(r),r.snapshot.input.protocol.get))
          }
          case Validate => operations.validate(r,false) *> validateSources *> r.snapshot.nodeAddress.traverse_ { reviewed =>
            remote.publicNodeAddresses(connection).flatMap { ips =>
              IO.raiseUnless(ips==reviewed.publicAddresses)(IntegrationError("REMNAWAVE_ONBOARDING_SOURCE_CHANGED","Managed Node address changed")) *>
                (if(reviewed.mode==NodeAddressMode.Domain) nodeAddresses.verifyDomain(reviewed.address,ips).void else IO.unit)
            }
          } *> freshLocal(false) *> (r.snapshot.recovery match {
            case Some(proof) => remote.recoveryPreflight(connection,if(proof.reusesNode && (!r.snapshot.connectivityRepair || previousSources.isEmpty)) spec(r) else oldSpec(proof))
            case None => remote.preflight(connection,r.resourceId,r.snapshot.input.nodePort)
          }).flatMap(x =>
            if(x.failureCode.nonEmpty || x.uncertain) IO.pure(Stop(x.failureCode.getOrElse("REMNAWAVE_ONBOARDING_PREFLIGHT_UNKNOWN"),x.uncertain)) else advance)
          case PrepareServer => operations.validate(r,true) *> advance
          case UpdateNodeAddress =>
            val previous=r.snapshot.recovery.flatMap(_.previousNodeAddress).get
            val expected=r.intent.copy(address=previous)
            nodes.lookupNode(context,r.externalNodeId.get).flatMap {
              case NodeLookupOutcome.Found(n) if matches(r,n) => existing
              case NodeLookupOutcome.Found(n) if OnboardingRecovery.matches(n,expected,r.externalNodeId) && fresh =>
                nodes.updateNodeAddress(context,n.externalId,expected,r.intent,r.snapshot.compatibility).flatMap {
                  case IntegrationActionRemoteOutcome.DefinitelyFailed(code) => IO.pure(Stop(code,false))
                  case outcome => nodes.lookupNode(context,n.externalId).map {
                    case NodeLookupOutcome.Found(current) if matches(r,current) => Advance(r)
                    case _ => Stop(outcome match {
                      case IntegrationActionRemoteOutcome.OutcomeUnknown(code) => code
                      case _ => "INTEGRATION_NODE_ADDRESS_RESULT_UNKNOWN"
                    },true)
                  }
                }
              case NodeLookupOutcome.Found(n) if OnboardingRecovery.matches(n,expected,r.externalNodeId) =>
                IO.pure(Stop("INTEGRATION_NODE_ADDRESS_RESULT_UNKNOWN",true))
              case NodeLookupOutcome.Found(_) => IO.pure(Stop("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW",false))
              case _ => IO.pure(Stop("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN",true))
            }
          case ResolvePanelSource => validateSources *> advance
          case ObservePanelSource if r.connectivityFinding.exists(_.connected) =>
            IO.pure(Advance(r.copy(observedPanelSource=Some(PanelSourceObservation(PanelSourceObservationStatus.NotRequired,Nil)))))
          case ObservePanelSource => existing.flatMap {
            case _: Advance => remote.observe(connection,currentSpec).flatMap(local => IO.raiseUnless(local.verified)(
              IntegrationError("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED","Owned local Node changed"))) *>
              probeSpec.flatMap(p => remote.observePanelSynSource(connection,p)).map { observed =>
                val bounded=if((unionSources++observed.sources).distinct.size>32)
                  PanelSourceObservation(PanelSourceObservationStatus.Ambiguous,Nil) else observed
                Advance(r.copy(observedPanelSource=Some(bounded)))
              }
            case other => IO.pure(other)
          }
          case AddObservedPanelSource if !r.observedPanelSource.exists(_.status==PanelSourceObservationStatus.Observed) => advance
          case AddObservedPanelSource => existing.flatMap {
            case _: Advance => remote.observe(connection,spec(r).copy(panelCidrs=(previousSources++r.snapshot.input.panelCidrs).distinct.sorted)).flatMap { local =>
              (if(local.verified) IO.pure(true) else remote.observe(connection,currentSpec).map(_.verified)).flatMap(ok => IO.raiseUnless(ok)(
                IntegrationError("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED","Owned local Node changed")))
            } *> result(remote.reconcilePanelSources(connection,spec(r).copy(panelCidrs=unionSources),previousSources,unionSources))
            case other => IO.pure(other)
          }
          case VerifyObservedPanel if !r.observedPanelSource.exists(_.status==PanelSourceObservationStatus.Observed) => advance
          case VerifyObservedPanel => nodes.getNode(context,r.externalNodeId.get).timeout(15.seconds).flatMap { n =>
            if(!matches(r,n)) IO.pure(Advance(r.copy(connectivityFinding=None)))
            else if(n.connected && !n.disabled) remote.observe(connection,currentSpec).map(local =>
              if(local.verified) Advance(r.copy(connectivityFinding=Some(finding(true))))
              else Stop("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED",true))
            else elapsed(r,polling.panelTimeout).flatMap(expired => if(!expired) IO.pure(Wait(r)) else
              remote.observe(connection,currentSpec).map(local => if(local.verified)
                Advance(r.copy(connectivityFinding=Option.when(!n.disabled)(finding(false))))
                else Stop("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED",true)))
          }.handleErrorWith(e => elapsed(r,polling.panelTimeout).flatMap(expired => if(!expired) IO.pure(Wait(r)) else
            remote.observe(connection,currentSpec).map(local => if(local.verified) Advance(r.copy(connectivityFinding=None))
              else Stop("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED",true))))
          case AddPanelSources => validateSources *> existing.flatMap {
            case _: Advance => remote.observe(connection,if(previousSources.isEmpty) spec(r) else oldSpec(r.snapshot.recovery.get)).flatMap { local =>
              // After a crash the reviewed union may already be present; prove it before continuation.
              (if(local.verified || (previousSources.isEmpty && local.locallyHealthy)) IO.pure(true) else remote.observe(connection,currentSpec).map(_.verified)).flatMap(ok =>
                IO.raiseUnless(ok)(IntegrationError("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED","Owned local node is not healthy"))) *>
                result(remote.reconcilePanelSources(connection,spec(r),previousSources,unionSources))
            }
            case other => IO.pure(other)
          }
          case FinalizePanelSources =>
              val confirmed = r.connectivityFinding.exists(_.connected)
              val target = if(confirmed) r.effectivePanelSources else if(r.snapshot.connectivityRepair) previousSources else Nil
              val confirmation = if(!confirmed) IO.unit else existing.flatMap {
                case _: Advance => nodes.getNode(context,r.externalNodeId.get).flatMap(n => IO.raiseUnless(
                  matches(r,n) && n.connected && !n.disabled)(IntegrationError("REMNAWAVE_PANEL_CONNECTIVITY_CONFIRMATION_LOST","Panel connectivity changed")))
                case stop: Stop => IO.raiseError(IntegrationError(stop.code,"Panel identity is unproven"))
                case _ => IO.raiseError(IntegrationError("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN","Panel identity is unproven"))
              }
              confirmation *>
              remote.observe(connection,currentSpec).flatMap { local =>
                // A completed finalization can be observed after a crash without replaying removal.
                (if(local.verified || (target.isEmpty && local.locallyHealthy)) IO.pure(true) else remote.observe(connection,spec(r).copy(panelCidrs=target)).map(_.verified)).flatMap(ok =>
                  IO.raiseUnless(ok)(IntegrationError("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED","Owned local node changed"))) *>
                  mutationResult(if(target.isEmpty) remote.retireFirewall(connection,currentSpec) else if(!r.snapshot.connectivityRepair && !r.observedPanelSource.exists(_.status==PanelSourceObservationStatus.Observed))
                    IO.pure(ProvisioningStepResult(Map.empty,None,Some(true))) else
                    remote.reconcilePanelSources(connection,spec(r).copy(panelCidrs=unionSources),previousSources,target)).map {
                    case _: Advance if confirmed => Advance(r.copy(connectivityFinding=Some(finding(true)),connectivityCompletion=Some(PanelConnectivityCompletion.Promoted)))
                    case _: Advance =>
                      val completed=r.copy(connectivityCompletion=Some(PanelConnectivityCompletion.RolledBack))
                      r.observedPanelSource.map(_.status) match {
                        case Some(PanelSourceObservationStatus.NoTraffic) => Stop("REMNAWAVE_PANEL_SOURCE_NO_TRAFFIC",false,Some(completed))
                        case Some(PanelSourceObservationStatus.Ambiguous) => Stop("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY",false,Some(completed))
                        case Some(PanelSourceObservationStatus.Unavailable) => Stop("REMNAWAVE_PANEL_SOURCE_OBSERVATION_UNAVAILABLE",false,Some(completed))
                        case _ if r.connectivityFinding.nonEmpty => Stop("REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT",false,Some(completed))
                        case _ => Stop("REMNAWAVE_PANEL_OBSERVATION_UNKNOWN",true,Some(completed))
                      }
                    case other => other
                  }
              }
          case DeleteNode =>
            val proof=r.snapshot.recovery.get
            val oldIntent=r.intent.copy(correlationId=proof.previousCorrelationId,address=proof.previousNodeAddress.getOrElse(r.snapshot.input.address))
            exactNode(proof.previousExternalNodeId.get,oldIntent).flatMap {
              case Left(stop) if stop.code=="REMNAWAVE_ONBOARDING_NODE_NOT_FOUND" => oldAbsent *> advance
              case Right(n) if fresh =>
                nodes.deleteNode(context,n.externalId,r.snapshot.compatibility).map {
                  case NodeDeleteOutcome.Deleted => Advance(r)
                  case NodeDeleteOutcome.Rejected(code) => Stop(code,false)
                  case NodeDeleteOutcome.Unknown(code) => Stop(code,true)
                }
              case Right(_) =>
                IO.pure(Stop("REMNAWAVE_ONBOARDING_DELETE_UNKNOWN",true))
              case Left(stop) => IO.pure(stop)
            }
          case ConfirmNodeDeleted => oldAbsent *> advance
          case RetireNodeFirewall => oldAbsent *> result(remote.retireFirewall(connection,oldSpec(r.snapshot.recovery.get))).flatMap {
            case _: Advance if r.snapshot.input.protocol.nonEmpty => result(remote.retireClientFirewall(connection,
              oldSpec(r.snapshot.recovery.get),r.snapshot.input.protocol.get))
            case decision => IO.pure(decision)
          }
          case RetireLocalNode => oldAbsent *> result(remote.retireInstallation(connection,oldSpec(r.snapshot.recovery.get)))
          case CreateNode if r.snapshot.recovery.exists(_.reusesNode) => operations.validate(r,true) *> existing
          case CreateNode if !fresh || r.externalNodeId.nonEmpty => operations.validate(r,true) *> reconciliation
          case CreateNode => operations.validate(r,true) *> validateSources *> oldAbsent *> (if(r.snapshot.recovery.nonEmpty && !r.snapshot.needsRetirement)
            freshLocal(true) else IO.unit) *> remote.installationPrerequisites(connection).flatMap { prerequisites =>
            IO.raiseUnless(prerequisites.failureCode.isEmpty && !prerequisites.uncertain && !prerequisites.outputTruncated)(
              IntegrationError(prerequisites.failureCode.getOrElse("REMNAWAVE_ONBOARDING_PREREQUISITES_UNKNOWN"),"Installation prerequisites are unavailable"))
          } *> provider.observe(context).flatMap { observation =>
            val profile = observation.objects.find(o => o.objectType==IntegrationObjectType.ConfigProfile && o.externalId==r.effectiveInput.configProfileId.toString)
            val ids = profile.toList.flatMap(_.summary match {case p: RemnawaveConfigProfileSummary => p.inbounds.map(_.uuid); case _ => Nil}).toSet
            IO.raiseUnless(profile.nonEmpty && r.effectiveInput.activeInboundIds.forall(id => ids(id.toString)))(
              IntegrationError("REMNAWAVE_ONBOARDING_INBOUND_INVALID","The selected inbounds changed")) *>
              IO.raiseUnless(r.protocolBinding.forall(binding => profile.exists(_.summary match {
                case p: RemnawaveConfigProfileSummary => p.configSha256.contains(binding.configSha256)
                case _ => false
              })))(IntegrationError("REMNAWAVE_PROTOCOL_PROFILE_CHANGED","Protocol configuration changed before Node creation")) *>
              nodes.createNode(context,r.intent,r.snapshot.compatibility).flatMap {
                case NodeCreateOutcome.Created(n) if matches(r,n) => IO.pure[Decision](Advance(r.copy(externalNodeId=Some(n.externalId))))
                case NodeCreateOutcome.Created(_) => reconciliation
                case NodeCreateOutcome.Rejected(code) => IO.pure[Decision](Stop(code,false))
                case NodeCreateOutcome.Unknown(_) => reconciliation
              }
          }
          case GetInstallationData => runner.run(repo.secret(r)).flatMap {
            case Some(_) => advance
            case None => nodes.installationData(context,r.snapshot.compatibility).flatMap(data => clock.flatMap(now =>
              runner.run(repo.saveSecret(r,token,cipher.encrypt(r.id,r.organizationId,data),now)))) *> advance
          }
          case ConfigureFirewall if !fresh => remote.firewallPresent(connection,spec(r)).map(ok =>
            if(ok) Advance(r) else Stop("REMNAWAVE_ONBOARDING_FIREWALL_UNKNOWN",true))
          case ConfigureFirewall => result(remote.configureOnboardingFirewall(connection,spec(r)))
          case InstallNode => remote.localInstallationState(connection,spec(r)).flatMap {
            case LocalInstallationState.Foreign => IO.pure(Stop("PROVISIONING_NODE_INSTALLATION_UNMANAGED",false))
            case LocalInstallationState.PortConflict => IO.pure(Stop("PROVISIONING_NODE_PORT_OCCUPIED",false))
            case LocalInstallationState.Unknown => IO.pure(Stop("REMNAWAVE_ONBOARDING_INSTALL_UNKNOWN",true))
            case state =>
              def installOrRepair: IO[Decision] = runner.run(repo.secret(r))
                .flatMap(_.liftTo[IO](IntegrationError("REMNAWAVE_ONBOARDING_SECRET_MISSING","Installation data is unavailable")))
                .flatMap(s => result(if(state==LocalInstallationState.Absent)
                  remote.install(connection,spec(r),cipher.decrypt(s)) else remote.repair(connection,spec(r),cipher.decrypt(s))))
              if(state==LocalInstallationState.OwnedComplete) remote.installationPresent(connection,spec(r)).flatMap {
                case true => advance
                case false => installOrRepair
              } else installOrRepair
          }
          case StartNode if !fresh => remote.observe(connection,spec(r)).map(e =>
            if(e.verified) Advance(r) else Stop("REMNAWAVE_ONBOARDING_START_UNKNOWN",true))
          case StartNode => enableExisting.flatMap {
            case _: Advance => result(remote.start(connection,spec(r))).flatMap {
              case stop: Stop if stop.unknown => remote.observe(connection,spec(r)).map(e => if(e.verified) Advance(r) else stop)
              case other => IO.pure(other)
            }
            case other => IO.pure(other)
          }
          case VerifyLocalNode => remote.observe(connection,spec(r)).flatMap(e => if(!e.verified)
            elapsed(r,60.seconds).map(expired => if(expired) Stop("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED",false) else Wait(r))
            else clock.flatMap(now => runner.run(repo.deleteSecret(r,token,now))) *> advance)
          case WaitForPanel => nodes.getNode(context,r.externalNodeId.get).timeout(15.seconds).flatMap { n =>
            if(!matches(r,n) && r.snapshot.lifecycleVersion>=4) IO.pure(Advance(r.copy(connectivityFinding=None)))
            else if(!matches(r,n)) IO.pure(Stop("REMNAWAVE_ONBOARDING_NODE_MISMATCH",false))
            else if(n.connected && !n.disabled && r.snapshot.lifecycleVersion>=4)
              remote.observe(connection,currentSpec).map(local => if(local.verified)
                Advance(r.copy(connectivityFinding=Some(finding(true)))) else Stop("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED",false))
            else if(n.connected && !n.disabled) advance
            else elapsed(r,polling.panelTimeout).flatMap(expired => if(!expired) IO.pure(Wait(r)) else
              remote.observe(connection,currentSpec).map { local =>
                if(local.verified && r.snapshot.lifecycleVersion>=4)
                  Advance(r.copy(connectivityFinding=Option.when(!n.disabled)(finding(false))))
                else if(local.verified && !n.disabled)
                  Stop("REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT",false,Some(r.copy(connectivityFinding=Some(finding(false)))))
                else Stop("REMNAWAVE_NODE_CONNECTION_TIMEOUT",false)
              })
          }.handleErrorWith { e =>
            if(r.snapshot.lifecycleVersion<4) IO.raiseError(e) else elapsed(r,polling.panelTimeout).flatMap(expired =>
              if(!expired) IO.pure(Wait(r)) else remote.observe(connection,currentSpec).map(local =>
                if(local.verified) Advance(r.copy(connectivityFinding=None)) else Stop("REMNAWAVE_ONBOARDING_LOCAL_VERIFICATION_FAILED",true)))
          }
          case SyncInventory => syncInventory(r,token)
          case BindResource => operations.bind(r,token) *> advance
          case SetDesiredState => operations.setDesiredState(r,token) *> advance
          case FinalVerify => for {
            _ <- operations.validate(r,true)
            actualApi <- nodes.inspect(context)
            reviewed = r.snapshot.compatibility
            _ <- IO.raiseUnless(actualApi.provisioningReady && actualApi.serverVersion==reviewed.serverVersion &&
              actualApi.apiGeneration==reviewed.apiGeneration && actualApi.sourceCommit==reviewed.sourceCommit)(
              IntegrationError("INTEGRATION_API_CONTRACT_CHANGED","The reviewed Remnawave contract changed"))
            local <- remote.observe(connection,spec(r))
            panel <- nodes.getNode(context,r.externalNodeId.get)
            bound <- operations.verifyInventoryBindingDesired(r)
          } yield if(local.verified && matches(r,panel) && panel.connected && !panel.disabled && bound) Advance(r)
            else Stop("REMNAWAVE_ONBOARDING_FINAL_VERIFICATION_FAILED",false)
        }
      }
    }
  }
}
