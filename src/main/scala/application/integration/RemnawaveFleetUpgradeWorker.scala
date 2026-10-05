package ru.bitec.app.ops
package application.integration

import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.effect.syntax.all._
import cats.syntax.all._
import domain.integration._
import java.time.Instant
import java.util.UUID
import org.typelevel.log4cats.Logger

private[integration] case object NodeUpgradeLeaseLost extends NodeImageAuthorizationFailure("NODE_UPGRADE_LEASE_LOST")
private[integration] final case class NodeUpgradeAdmissionDenied(code: String) extends NodeImageAuthorizationFailure(code)

/** A durable, specialized image lifecycle. One node switch is an atomic boundary; pause is honored
  * before the next node. An expired worker cannot journal, advance or authorize another mutation. */
final class RemnawaveFleetUpgradeWorker[Tx[_]: MonadThrow](repo: RemnawaveFleetUpgradeRepository[Tx],
  service: NodeUpgradeExecution[Tx], remote: RemnawaveNodeImageRemote[IO], verifier: RemnawaveNodeReleaseVerifier,
  refresh: (UUID, UUID, UUID, Instant) => IO[Unit], runner: TransactionRunner[IO, Tx], logger: Logger[IO],
  owner: UUID = UUID.randomUUID()) {
  import NodeUpgradeAdmission._
  private val settings = service.settings
  def run: IO[Nothing] = (tick.handleErrorWith(_ => logger.warn("remnawave.fleet.upgrade.poll_failed")) *>
    IO.sleep(settings.pollInterval)).foreverM
  def tick: IO[Unit] = if (!settings.enabled) IO.unit else for {
    now <- IO.realTimeInstant
    token <- IO(UUID.randomUUID())
    runs <- runner.run(repo.claim(owner, token, now, now.plusMillis(settings.lease.toMillis), 2))
    _ <- runs.parTraverseN(2)(r => IO.race(step(r, token), heartbeat(r, token)).void.handleErrorWith {
      case NodeUpgradeLeaseLost => logger.warn(s"remnawave.fleet.upgrade.lease_lost upgradeId=${r.id}")
      case _ => logger.warn(s"remnawave.fleet.upgrade.step_failed upgradeId=${r.id}")
    })
  } yield ()
  private def heartbeat(r: RemnawaveFleetUpgradeRun, token: UUID): IO[Unit] = (for {
    _ <- IO.sleep(settings.lease / 3)
    now <- IO.realTimeInstant
    ok <- runner.run(repo.renew(r.id, token, now, now.plusMillis(settings.lease.toMillis)))
    _ <- IO.raiseUnless(ok)(NodeUpgradeLeaseLost)
  } yield ()).foreverM
  private def fence(r: RemnawaveFleetUpgradeRun, token: UUID): IO[Unit] = IO.realTimeInstant.flatMap(now =>
    runner.run(repo.owns(r.id, token, now)).flatMap(ok => IO.raiseUnless(ok)(NodeUpgradeLeaseLost)))
  private def admission(r: RemnawaveFleetUpgradeRun, token: UUID, atomicMember: Option[UUID] = None): IO[Unit] =
    fence(r, token) *> service.admission(r, atomicMember).flatMap(_.traverse_(c => IO.raiseError[Unit](NodeUpgradeAdmissionDenied(c)))) *> fence(r, token)
  private def save(r: RemnawaveFleetUpgradeRun, token: UUID): IO[Unit] = IO.realTimeInstant.flatMap(now => runner.run(for {
    ok <- repo.save(r, token, now, release = true)
    _ <- MonadThrow[Tx].raiseUnless(ok)(NodeUpgradeLeaseLost)
    _ <- if (r.state.terminal) service.recordOutcome(r) else ().pure[Tx]
  } yield ()))
  private def member(m: RemnawaveFleetUpgradeMember, token: UUID): IO[Unit] = IO.realTimeInstant.flatMap(now =>
    runner.run(repo.saveMember(m, token, now)).flatMap(ok => IO.raiseUnless(ok)(NodeUpgradeLeaseLost)))
  private def action(a: RemnawaveFleetUpgradeAction, token: UUID): IO[Unit] = IO.realTimeInstant.flatMap(now =>
    runner.run(repo.saveAction(a, token, now)).flatMap(ok => IO.raiseUnless(ok)(NodeUpgradeLeaseLost)))
  private def advance(r: RemnawaveFleetUpgradeRun, phase: NodeUpgradePhase, now: Instant, token: UUID,
    wave: Option[Int] = None): IO[Unit] = save(r.copy(state = if (phase == NodeUpgradePhase.Rollback) FleetRolloutState.RollingBack else FleetRolloutState.Running,
      startedAt = r.startedAt.orElse(Some(now)), phase = phase, currentWave = wave.getOrElse(r.currentWave),
      phaseStartedAt = now, nextRunAt = now), token)
  private def later(r: RemnawaveFleetUpgradeRun, now: Instant, token: UUID): IO[Unit] =
    save(r.copy(nextRunAt = now.plusMillis(settings.pollInterval.toMillis)), token)
  private def terminal(r: RemnawaveFleetUpgradeRun, state: FleetRolloutState, code: Option[String], now: Instant, token: UUID): IO[Unit] =
    (if (state != FleetRolloutState.Unknown) IO.unit else for {
      members <- runner.run(repo.members(r.id))
      actions <- runner.run(repo.actions(r.id))
      _ <- members.filter(m => m.state == FleetRolloutMemberState.Running || m.state == FleetRolloutMemberState.RollingBack)
        .traverse_(m => member(m.copy(state = FleetRolloutMemberState.Unknown, failureCode = code, finishedAt = Some(now)), token))
      _ <- actions.filter(_.state == FleetActionState.Running).traverse_(a =>
        action(a.copy(state = FleetActionState.Unknown, failureCode = code, finishedAt = Some(now)), token))
    } yield ()) *> save(r.copy(state = state, phase = NodeUpgradePhase.Complete, failureCode = code,
      finishedAt = Some(now), nextRunAt = now), token)
  private def deny(r: RemnawaveFleetUpgradeRun, code: String, now: Instant, token: UUID): IO[Unit] =
    if (code == PlanChanged) terminal(r, FleetRolloutState.Failed, Some(code), now, token)
    else save(r.copy(state = FleetRolloutState.Paused, pauseReason = Some(if (code == RefreshRequired) "PAUSED_REFRESH_REQUIRED" else "PAUSED_HEALTH_GATE"),
      failureCode = Some(code), pausedAt = Some(now)), token)
  private def failed(r: RemnawaveFleetUpgradeRun, code: String, now: Instant, token: UUID): IO[Unit] = for {
    actions <- runner.run(repo.actions(r.id))
    members <- runner.run(repo.members(r.id))
    _ <- members.filter(_.state == FleetRolloutMemberState.Running).traverse_(m =>
      member(m.copy(state = FleetRolloutMemberState.Failed, failureCode = Some(code), finishedAt = Some(now)), token))
    successful = actions.exists(a => !a.rollback && a.kind == "SWITCH" && a.state == FleetActionState.Succeeded)
    _ <- if (r.snapshot.automaticRollback && successful) advance(r.copy(failureCode = Some(code)), NodeUpgradePhase.Rollback, now, token)
      else terminal(r, FleetRolloutState.Failed, Some(code), now, token)
  } yield ()
  private def step(claimed: RemnawaveFleetUpgradeRun, token: UUID): IO[Unit] = for {
    current <- runner.run(repo.byId(claimed.id)).flatMap(_.liftTo[IO](NodeUpgradeLeaseLost))
    now <- IO.realTimeInstant
    members <- runner.run(repo.members(current.id))
    _ <- if (current.phase == NodeUpgradePhase.Rollback) rollback(current, members, now, token)
      else if (current.rollbackRequestedAt.nonEmpty) advance(current, NodeUpgradePhase.Rollback, now, token)
      else if (current.pauseRequestedAt.nonEmpty) save(current.copy(state = FleetRolloutState.Paused,
        pausedAt = Some(now), pauseReason = Some("OPERATOR")), token)
      else if (members.exists(_.state == FleetRolloutMemberState.Unknown))
        terminal(current, FleetRolloutState.Unknown, Some("NODE_UPGRADE_RESULT_UNKNOWN"), now, token)
      else if (members.exists(_.state == FleetRolloutMemberState.Failed))
        failed(current, members.find(_.state == FleetRolloutMemberState.Failed).flatMap(_.failureCode).getOrElse("NODE_UPGRADE_MEMBER_FAILED"), now, token)
      else forward(current, members, now, token).handleErrorWith {
        case NodeUpgradeAdmissionDenied(code) => deny(current, code, now, token)
        case NodeUpgradeLeaseLost => IO.raiseError(NodeUpgradeLeaseLost)
        case e: NodeImageRemoteFailure if e.uncertain => terminal(current, FleetRolloutState.Unknown, Some(e.code), now, token)
        case e: NodeImageRemoteFailure => failed(current, e.code, now, token)
        case IntegrationError(code, _) => deny(current, code, now, token)
        case _ => terminal(current, FleetRolloutState.Unknown, Some("NODE_UPGRADE_RESULT_UNKNOWN"), now, token)
      }
  } yield ()
  private def forward(r: RemnawaveFleetUpgradeRun, all: List[RemnawaveFleetUpgradeMember], now: Instant, token: UUID): IO[Unit] = r.phase match {
    case NodeUpgradePhase.Validate => admission(r, token) *> advance(r,
      if (r.waveCount == 0) NodeUpgradePhase.FinalVerify else NodeUpgradePhase.PrepareCanary, now, token)
    case NodeUpgradePhase.PrepareCanary => admission(r, token) *> advance(r, NodeUpgradePhase.PrefetchCanary, now, token)
    case NodeUpgradePhase.PrefetchCanary | NodeUpgradePhase.PrefetchWave => prefetch(r, all, now, token)
    case NodeUpgradePhase.UpgradeCanary | NodeUpgradePhase.UpgradeWave => upgrade(r, all, now, token)
    case NodeUpgradePhase.VerifyCanary | NodeUpgradePhase.VerifyWave | NodeUpgradePhase.FinalVerify => verify(r, all, now, token)
    case _ => IO.raiseError(NodeImageRemoteFailure("NODE_UPGRADE_PHASE_INVALID"))
  }
  private def plan(r: RemnawaveFleetUpgradeRun, m: RemnawaveFleetUpgradeMember): NodeUpgradeMemberPlan =
    r.snapshot.members.find(_.membershipId == m.membershipId).get
  private def reference(release: NodeRelease, m: NodeUpgradeMemberPlan): String =
    s"${release.imageRepository}@${release.forPlatform(m.baseline.platform.get).get.manifestDigest}"
  private def journal(r: RemnawaveFleetUpgradeRun, m: RemnawaveFleetUpgradeMember, kind: String,
    rollback: Boolean, ref: String, now: Instant): RemnawaveFleetUpgradeAction =
    RemnawaveFleetUpgradeAction(UUID.nameUUIDFromBytes(s"${r.id}/${m.id}/$kind/$rollback".getBytes("UTF-8")),
      r.organizationId, r.id, m.id, kind, rollback, FleetActionState.Running, ref, None, now, None)
  private def prefetch(r: RemnawaveFleetUpgradeRun, all: List[RemnawaveFleetUpgradeMember], now: Instant, token: UUID): IO[Unit] = for {
    _ <- admission(r, token)
    actions <- runner.run(repo.actions(r.id))
    pending = all.filter(m => m.wave == r.currentWave && m.state != FleetRolloutMemberState.Skipped &&
      !actions.exists(a => a.memberId == m.id && !a.rollback && a.kind == "PREFETCH" && a.state == FleetActionState.Succeeded)).sortBy(_.position)
    _ <- pending.headOption match {
      case None => advance(r, if (r.currentWave == 0) NodeUpgradePhase.UpgradeCanary else NodeUpgradePhase.UpgradeWave, now, token)
      case Some(m) =>
        val p = plan(r, m)
        val a = actions.find(a => a.memberId == m.id && !a.rollback && a.kind == "PREFETCH").getOrElse(journal(r, m, "PREFETCH", false, reference(r.snapshot.target, p), now))
        for {
          _ <- if (a.state == FleetActionState.Unknown) IO.raiseError[Unit](NodeImageRemoteFailure(a.failureCode.getOrElse("NODE_UPGRADE_PREFETCH_UNKNOWN"), true))
            else if (a.state == FleetActionState.Failed) IO.raiseError[Unit](NodeImageRemoteFailure(a.failureCode.getOrElse("NODE_UPGRADE_PREFETCH_FAILED"))) else IO.unit
          _ <- action(a, token)
          connection <- service.connection(r.organizationId, p)
          platform = r.snapshot.target.forPlatform(p.baseline.platform.get).get
          _ <- verifier.verify(r.snapshot.target, platform)
          // Recovery re-proves the store before issuing a pull in the typed remote operation.
          _ <- remote.prefetchImage(connection, service.spec(r.snapshot, p), r.snapshot.target, platform, p.baseline, admission(r, token))
            .handleErrorWith(e => action(a.copy(state = e match { case n: NodeImageRemoteFailure if !n.uncertain => FleetActionState.Failed; case _ => FleetActionState.Unknown },
              failureCode = Some(safeCode(e)), finishedAt = Some(now)), token) *> IO.raiseError(e))
          at <- IO.realTimeInstant
          _ <- action(a.copy(state = FleetActionState.Succeeded, finishedAt = Some(at)), token)
          _ <- later(r, at, token)
        } yield ()
    }
  } yield ()
  private def upgrade(r: RemnawaveFleetUpgradeRun, all: List[RemnawaveFleetUpgradeMember], now: Instant, token: UUID): IO[Unit] =
    all.filter(m => m.wave == r.currentWave && (m.state == FleetRolloutMemberState.Pending || m.state == FleetRolloutMemberState.Running)).sortBy(_.position).headOption match {
      case None => advance(r, if (r.currentWave == 0) NodeUpgradePhase.VerifyCanary else NodeUpgradePhase.VerifyWave, now, token)
      case Some(m) => for {
        actions <- runner.run(repo.actions(r.id))
        p = plan(r, m)
        previous = actions.find(a => a.memberId == m.id && !a.rollback && a.kind == "SWITCH")
        _ <- if (previous.nonEmpty) IO.unit else admission(r, token)
        running = m.copy(state = FleetRolloutMemberState.Running, startedAt = m.startedAt.orElse(Some(now)))
        _ <- member(running, token)
        a = previous.getOrElse(journal(r, m, "SWITCH", false, reference(r.snapshot.target, p), now))
        _ <- if (previous.isEmpty) action(a, token) else IO.unit
        connection <- service.connection(r.organizationId, p)
        spec = service.spec(r.snapshot, p)
        observation <- remote.observeImage(connection, spec)
        targetId = r.snapshot.target.forPlatform(p.baseline.platform.get).get.configDigest
        _ <- a.state match {
          case FleetActionState.Unknown => IO.raiseError(NodeImageRemoteFailure(a.failureCode.getOrElse("NODE_UPGRADE_SWITCH_UNKNOWN"), true))
          case FleetActionState.Failed => IO.raiseError(NodeImageRemoteFailure(a.failureCode.getOrElse("NODE_UPGRADE_SWITCH_FAILED")))
          case FleetActionState.Succeeded => IO.unit
          case _ if observation.matches(r.snapshot.target) && observation.managedFiles =>
            action(a.copy(state = FleetActionState.Succeeded, finishedAt = Some(now)), token)
          case _ =>
            val knownBaseline = NodeUpgradeAdmission.sameBaseline(observation, p.baseline)
            val committedOnly = observation.managedFiles && observation.configuredImage.contains(a.targetReference) &&
              observation.markerHash == p.baseline.markerHash && observation.actualImageId == p.baseline.actualImageId &&
              observation.containerId == p.baseline.containerId && observation.containerCreatedAt == p.baseline.containerCreatedAt &&
              observation.containerStartedAt == p.baseline.containerStartedAt
            val authority = admission(r, token, Some(p.membershipId))
            val mutate = if (knownBaseline) authority *> remote.switchImage(connection, spec, a.targetReference, targetId,
              p.baseline.composeHash.get, p.baseline.markerHash.get, authority)
              else if (committedOnly) authority *> remote.activateImage(connection, spec, a.targetReference, targetId, p.baseline.markerHash.get, authority)
              else IO.raiseError[Unit](NodeImageRemoteFailure("NODE_UPGRADE_SWITCH_UNKNOWN", true))
            mutate.attempt.flatMap {
              case Right(_) => IO.realTimeInstant.flatMap(at => action(a.copy(state = FleetActionState.Succeeded, finishedAt = Some(at)), token))
              case Left(NodeUpgradeLeaseLost) => IO.raiseError(NodeUpgradeLeaseLost)
              case Left(e: NodeUpgradeAdmissionDenied) => IO.raiseError(e)
              case Left(e) => remote.observeImage(connection, spec).attempt.flatMap {
                case Right(actual) if actual.managedFiles && actual.matches(r.snapshot.target) =>
                  IO.realTimeInstant.flatMap(at => action(a.copy(state = FleetActionState.Succeeded, finishedAt = Some(at)), token))
                case Right(actual) if NodeUpgradeAdmission.sameBaseline(actual, p.baseline) && !uncertain(e) =>
                  action(a.copy(state = FleetActionState.Failed, failureCode = Some(safeCode(e)), finishedAt = Some(now)), token) *> IO.raiseError(e)
                case _ => action(a.copy(state = FleetActionState.Unknown, failureCode = Some("NODE_UPGRADE_SWITCH_UNKNOWN"), finishedAt = Some(now)), token) *>
                  IO.raiseError(NodeImageRemoteFailure("NODE_UPGRADE_SWITCH_UNKNOWN", true))
              }
            }
        }
        switched <- runner.run(repo.actions(r.id)).map(_.find(a => a.memberId == m.id && !a.rollback && a.kind == "SWITCH").get)
        current <- remote.observeImage(connection, spec)
        at <- IO.realTimeInstant
        _ <- if (!current.managedFiles || !current.matches(r.snapshot.target) || current.markerHash != p.baseline.markerHash)
          IO.raiseError(NodeImageRemoteFailure("NODE_UPGRADE_RUNTIME_IMAGE_MISMATCH", true))
          else if (!current.healthy && !current.containerRunning) IO.raiseError(NodeImageRemoteFailure("NODE_UPGRADE_LOCAL_HEALTH_FAILED"))
          else if (!current.healthy && at.isAfter(switched.finishedAt.get.plusMillis(settings.verificationTimeout.toMillis)))
            IO.raiseError(NodeImageRemoteFailure("NODE_UPGRADE_LOCAL_HEALTH_FAILED"))
          else if (!current.healthy) later(r, at, token)
          else for {
            local <- IO.pure(journal(r, m, "LOCAL_VERIFY", false, switched.targetReference, at).copy(state = FleetActionState.Succeeded, finishedAt = Some(at)))
            _ <- if (actions.exists(a => a.memberId == m.id && a.kind == "LOCAL_VERIFY" && !a.rollback)) IO.unit else action(local, token)
            _ <- member(running.copy(localVerifiedAt = Some(at), lastObservation = Some(current)), token)
            _ <- refresh(r.organizationId, r.integrationId, r.fleetId, at)
            // A local success is deliberately still RUNNING until fresh Panel and Fleet evidence passes.
            code <- service.verification(r, p, r.snapshot.target, switched.finishedAt.get)
            _ <- code match {
              case None =>
                val panel = journal(r, m, "PANEL_VERIFY", false, switched.targetReference, at).copy(state = FleetActionState.Succeeded, finishedAt = Some(at))
                (if (actions.exists(a => a.memberId == m.id && a.kind == "PANEL_VERIFY" && !a.rollback && a.state == FleetActionState.Succeeded)) IO.unit
                  else action(panel, token)) *> member(running.copy(state = FleetRolloutMemberState.Succeeded, localVerifiedAt = Some(at),
                  panelVerifiedAt = Some(at), lastObservation = Some(current), finishedAt = Some(at)), token)
              case Some(c) if c == RefreshRequired && !at.isAfter(switched.finishedAt.get.plusMillis(settings.verificationTimeout.toMillis)) => IO.unit
              case Some(c) => IO.raiseError(NodeImageRemoteFailure(if (c == RefreshRequired) "NODE_UPGRADE_VERIFY_TIMEOUT" else c))
            }
            _ <- later(r, at, token)
          } yield ()
      } yield ()
    }
  private def verify(r: RemnawaveFleetUpgradeRun, all: List[RemnawaveFleetUpgradeMember], now: Instant, token: UUID): IO[Unit] = for {
    actions <- runner.run(repo.actions(r.id))
    relevant = if (r.phase == NodeUpgradePhase.FinalVerify) all else all.filter(_.wave <= r.currentWave)
    codes <- relevant.traverse { m =>
      val p = plan(r, m)
      val after = if (m.state == FleetRolloutMemberState.Skipped) r.createdAt else actions.find(a => a.memberId == m.id && a.kind == "SWITCH" && !a.rollback).flatMap(_.finishedAt).getOrElse(r.createdAt)
      service.verification(r, p, r.snapshot.target, after)
    }
    _ <- codes.flatten.headOption match {
      case Some(c) if c == RefreshRequired && !now.isAfter(r.phaseStartedAt.plusMillis(settings.verificationTimeout.toMillis)) =>
        refresh(r.organizationId, r.integrationId, r.fleetId, now) *> later(r, now, token)
      case Some(c) => IO.raiseError(NodeImageRemoteFailure(if (c == RefreshRequired) "NODE_UPGRADE_VERIFY_TIMEOUT" else c))
      case None if r.phase == NodeUpgradePhase.FinalVerify => admission(r, token) *> terminal(r, FleetRolloutState.Succeeded, None, now, token)
      case None if r.currentWave == 0 && r.snapshot.pauseAfterCanary =>
        save(r.copy(state = FleetRolloutState.Paused, phase = if (r.waveCount > 1) NodeUpgradePhase.PrefetchWave else NodeUpgradePhase.FinalVerify,
          currentWave = math.min(1, r.waveCount), pauseReason = Some("CANARY"), pausedAt = Some(now), phaseStartedAt = now), token)
      case None if r.currentWave + 1 >= r.waveCount => advance(r, NodeUpgradePhase.FinalVerify, now, token)
      case None => admission(r.copy(currentWave = r.currentWave + 1), token) *> advance(r, NodeUpgradePhase.PrefetchWave, now, token, Some(r.currentWave + 1))
    }
  } yield ()
  private def rollback(r: RemnawaveFleetUpgradeRun, all: List[RemnawaveFleetUpgradeMember], now: Instant, token: UUID): IO[Unit] = for {
    actions <- runner.run(repo.actions(r.id))
    candidates = all.filter(m => (r.rollbackScope == FleetRollbackScope.AllCompleted || m.wave == r.currentWave) &&
      actions.exists(a => a.memberId == m.id && !a.rollback && a.kind == "SWITCH" && a.state == FleetActionState.Succeeded))
      .sortBy(m => -m.position)
    _ <- candidates.find(m => m.state != FleetRolloutMemberState.RolledBack) match {
      case None => terminal(r, FleetRolloutState.RolledBack, r.failureCode, now, token)
      case Some(m) =>
        val p = plan(r, m)
        val release = p.previousReleaseId.flatMap(RemnawaveNodeReleaseCatalog.find)
        val rollback = for {
          previous <- release.filter(r => r.status != "BLOCKED" && p.rollbackAvailable).liftTo[IO](NodeImageRemoteFailure("NODE_UPGRADE_NO_PREVIOUS_IMAGE"))
          authority = fence(r, token) *> service.panel(r.organizationId, r.integrationId).flatMap(api =>
            IO.raiseUnless(api == r.snapshot.panel && RemnawaveNodeReleaseCatalog.compatibility(previous, api, p.baseline.platform).compatible)(NodeImageRemoteFailure(PlanChanged))) *>
            service.connection(r.organizationId, p).void *> fence(r, token)
          _ <- authority
          connection <- service.connection(r.organizationId, p)
          spec = service.spec(r.snapshot, p)
          current <- remote.observeImage(connection, spec)
          _ <- IO.raiseUnless(current.managedFiles && current.markerHash == p.baseline.markerHash)(NodeImageRemoteFailure("NODE_UPGRADE_ROLLBACK_UNKNOWN", true))
          old = actions.find(a => a.memberId == m.id && a.rollback && a.kind == "SWITCH")
          a = old.getOrElse(journal(r, m, "SWITCH", true, p.previousImageReference.get, now))
          _ <- IO.raiseWhen(old.exists(v => v.state == FleetActionState.Unknown || v.state == FleetActionState.Failed ||
            (v.state == FleetActionState.Succeeded && !current.matches(previous))))(NodeImageRemoteFailure("NODE_UPGRADE_ROLLBACK_UNKNOWN", true))
          _ <- old.fold(action(a, token))(_ => IO.unit)
          _ <- member(m.copy(state = FleetRolloutMemberState.RollingBack), token)
          _ <- if (current.matches(previous)) IO.unit
            else if (current.matches(r.snapshot.target)) authority *> remote.switchImage(connection, spec, a.targetReference,
              previous.forPlatform(p.baseline.platform.get).get.configDigest, current.composeHash.get, p.baseline.markerHash.get, authority)
            else if (current.configuredImage.contains(a.targetReference) && current.actualImageId.contains(r.snapshot.target.forPlatform(p.baseline.platform.get).get.configDigest))
              authority *> remote.activateImage(connection, spec, a.targetReference,
                previous.forPlatform(p.baseline.platform.get).get.configDigest, p.baseline.markerHash.get, authority)
            else IO.raiseError(NodeImageRemoteFailure("NODE_UPGRADE_ROLLBACK_UNKNOWN", true))
          at <- IO.realTimeInstant
          _ <- if (old.exists(_.state == FleetActionState.Succeeded)) IO.unit else action(a.copy(state = FleetActionState.Succeeded, finishedAt = Some(at)), token)
          observed <- remote.observeImage(connection, spec)
          completed <- runner.run(repo.actions(r.id)).map(_.find(x => x.memberId == m.id && x.rollback && x.kind == "SWITCH").get)
          code <- if (observed.healthy && observed.matches(previous)) service.verification(r, p, previous, completed.finishedAt.get)
            else IO.pure(Some(RefreshRequired))
          _ <- code match {
            case None => List("LOCAL_VERIFY", "PANEL_VERIFY").traverse_(kind =>
              if (actions.exists(v => v.memberId == m.id && v.rollback && v.kind == kind && v.state == FleetActionState.Succeeded)) IO.unit
              else action(journal(r, m, kind, true, a.targetReference, at).copy(state = FleetActionState.Succeeded, finishedAt = Some(at)), token)) *>
              member(m.copy(state = FleetRolloutMemberState.RolledBack, localVerifiedAt = Some(at), panelVerifiedAt = Some(at),
              lastObservation = Some(observed), finishedAt = Some(at)), token) *> later(r, at, token)
            case Some(_) if !at.isAfter(completed.finishedAt.get.plusMillis(settings.verificationTimeout.toMillis)) =>
              refresh(r.organizationId, r.integrationId, r.fleetId, at) *> later(r, at, token)
            case Some(c) => terminal(r.copy(rollbackIncomplete = true), FleetRolloutState.Failed, Some(c), at, token)
          }
        } yield ()
        rollback.handleErrorWith {
          case NodeUpgradeLeaseLost => IO.raiseError(NodeUpgradeLeaseLost)
          case e => terminal(r.copy(rollbackIncomplete = true), if (uncertain(e)) FleetRolloutState.Unknown else FleetRolloutState.Failed,
            Some(safeCode(e)), now, token)
        }
    }
  } yield ()
  private def uncertain(e: Throwable): Boolean = e match { case n: NodeImageRemoteFailure => n.uncertain; case _ => true }
  private def safeCode(e: Throwable): String = e match {
    case n: NodeImageRemoteFailure => n.code
    case IntegrationError(code, _) => code
    case n: NodeUpgradeAdmissionDenied => n.code
    case _ => "NODE_UPGRADE_RESULT_UNKNOWN"
  }
}
