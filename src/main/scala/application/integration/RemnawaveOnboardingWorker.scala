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
  owner: UUID = UUID.randomUUID(), clock: IO[Instant] = IO.realTimeInstant) {
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
    r.snapshot.input.nodePort,r.snapshot.imageReference,r.snapshot.input.panelCidrs)
  private def provider = providers.find(IntegrationProviderType.Remnawave).get
  private def matches(r: RemnawaveNodeOnboardingRun,n: ProvisionedNode): Boolean = {
    val i = r.snapshot.intent
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
        def existing: IO[Decision] = exactNode(r.externalNodeId.get,r.snapshot.intent).map(_.fold(identity,_ => Advance(r)))
        def enableExisting: IO[Decision] = if(!r.snapshot.recovery.exists(_.reusesNode)) advance else
          exactNode(r.externalNodeId.get,r.snapshot.intent).flatMap {
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
          proof.previousExternalNodeId.get,r.snapshot.input.nodePort,r.snapshot.imageReference,proof.previousPanelCidrs.getOrElse(r.snapshot.input.panelCidrs))
        def oldAbsent: IO[Unit] = r.snapshot.recovery.filter(!_.reusesNode).traverse_ { proof =>
          nodes.lookupNode(context,proof.previousExternalNodeId.get).flatMap {
            case NodeLookupOutcome.ConfirmedNotFound => nodes.findNodes(context).flatMap(candidates => IO.raiseWhen(
              candidates.exists(OnboardingRecovery.candidate(_,r.snapshot.intent.copy(correlationId=proof.previousCorrelationId))))(
                IntegrationError("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW","Conflicting candidate exists")))
            case NodeLookupOutcome.Found(_) => IO.raiseError(IntegrationError("REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW","Previous node still exists"))
            case _ => IO.raiseError(IntegrationError("REMNAWAVE_ONBOARDING_OBSERVATION_UNKNOWN","Previous node absence is unproven"))
          }
        }
        def reconciliation: IO[Decision] = nodes.reconcileCreate(context,r.snapshot.intent,r.snapshot.compatibility).map {
          case NodeCreateReconciliation.Confirmed(n) if matches(r,n) => Advance(r.copy(externalNodeId=Some(n.externalId)))
          case _ => Stop("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN",true)
        }
        def result(io: IO[ProvisioningStepResult]): IO[Decision] = {
          def mutation = io.map(x => if(x.uncertain || x.outputTruncated)
            Stop(x.failureCode.getOrElse("REMNAWAVE_ONBOARDING_REMOTE_UNKNOWN"),true)
            else x.failureCode.fold[Decision](Advance(r))(code => Stop(code,false)))
          if(r.snapshot.recovery.exists(_.reusesNode)) existing.flatMap {
            case _: Advance => mutation
            case other => IO.pure(other)
          } else mutation
        }
        r.phase match {
          case Validate => operations.validate(r,false) *> (r.snapshot.recovery match {
            case Some(proof) => remote.recoveryPreflight(connection,if(proof.reusesNode) spec(r) else oldSpec(proof))
            case None => remote.preflight(connection,r.resourceId,r.snapshot.input.nodePort)
          }).flatMap(x =>
            if(x.failureCode.nonEmpty || x.uncertain) IO.pure(Stop(x.failureCode.getOrElse("REMNAWAVE_ONBOARDING_PREFLIGHT_UNKNOWN"),x.uncertain)) else advance)
          case PrepareServer => operations.validate(r,true) *> advance
          case DeleteNode =>
            val proof=r.snapshot.recovery.get
            val oldIntent=r.snapshot.intent.copy(correlationId=proof.previousCorrelationId)
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
          case RetireNodeFirewall => oldAbsent *> result(remote.retireFirewall(connection,oldSpec(r.snapshot.recovery.get)))
          case RetireLocalNode => oldAbsent *> result(remote.retireInstallation(connection,oldSpec(r.snapshot.recovery.get)))
          case CreateNode if r.snapshot.recovery.exists(_.reusesNode) => operations.validate(r,true) *> existing
          case CreateNode if !fresh || r.externalNodeId.nonEmpty => operations.validate(r,true) *> reconciliation
          case CreateNode => operations.validate(r,true) *> oldAbsent *> remote.installationPrerequisites(connection).flatMap { prerequisites =>
            IO.raiseUnless(prerequisites.failureCode.isEmpty && !prerequisites.uncertain && !prerequisites.outputTruncated)(
              IntegrationError(prerequisites.failureCode.getOrElse("REMNAWAVE_ONBOARDING_PREREQUISITES_UNKNOWN"),"Installation prerequisites are unavailable"))
          } *> provider.observe(context).flatMap { observation =>
            val profile = observation.objects.find(o => o.objectType==IntegrationObjectType.ConfigProfile && o.externalId==r.snapshot.input.configProfileId.toString)
            val ids = profile.toList.flatMap(_.summary match {case p: RemnawaveConfigProfileSummary => p.inbounds.map(_.uuid); case _ => Nil}).toSet
            IO.raiseUnless(profile.nonEmpty && r.snapshot.input.activeInboundIds.forall(id => ids(id.toString)))(
              IntegrationError("REMNAWAVE_ONBOARDING_INBOUND_INVALID","The selected inbounds changed")) *>
              nodes.createNode(context,r.snapshot.intent,r.snapshot.compatibility).flatMap {
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
          case WaitForPanel => nodes.getNode(context,r.externalNodeId.get).flatMap { n =>
            if(!matches(r,n)) IO.pure(Stop("REMNAWAVE_ONBOARDING_NODE_MISMATCH",false))
            else if(n.connected && !n.disabled) advance
            else elapsed(r,polling.panelTimeout).map(expired => if(expired) Stop("REMNAWAVE_NODE_CONNECTION_TIMEOUT",false) else Wait(r))
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
