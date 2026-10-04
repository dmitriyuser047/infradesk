package ru.bitec.app.ops
package application.integration

import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.effect.syntax.all._
import cats.syntax.all._
import domain.integration._
import java.time.Instant
import java.util.UUID
import org.typelevel.log4cats.Logger
import scala.concurrent.duration._

final case class RemnawaveFleetRolloutWorkerSettings(enabled: Boolean = true, pollInterval: FiniteDuration = 3.seconds,
  batchSize: Int = 5, claimLease: FiniteDuration = 60.seconds, verifyTimeout: FiniteDuration = 20.minutes,
  maxMemberConcurrency: Int = 2, cleanupBatch: Int = 100) {
  require(pollInterval > Duration.Zero && batchSize > 0 && claimLease > pollInterval && maxMemberConcurrency > 0)
}

/** Advances claimed rollouts one bounded unit per tick.
  *
  * Every remote call runs outside a database transaction, every write is fenced by the claim token, and
  * a worker whose lease lapsed stops without writing. Compensation is new, journaled work; it is only
  * ever derived from forward actions that are recorded as SUCCEEDED.
  */
final class RemnawaveFleetRolloutWorker[Tx[_]: MonadThrow](rollouts: RemnawaveFleetRolloutRepository[Tx],
  query: RemnawaveFleetQuery[Tx], children: FleetRolloutChildren, members: FleetRolloutMemberRunner[Tx],
  service: RemnawaveFleetRollouts[Tx], runner: TransactionRunner[IO, Tx], logger: Logger[IO],
  settings: RemnawaveFleetRolloutWorkerSettings, owner: UUID = UUID.randomUUID()) {

  def run: IO[Nothing] = (tick.handleErrorWith(_ => logger.warn("remnawave.fleet.rollout.poll_failed")) *>
    IO.sleep(settings.pollInterval)).foreverM

  def tick: IO[Unit] = if (!settings.enabled) IO.unit else for {
    now <- IO.realTimeInstant
    token <- IO(UUID.randomUUID())
    claimed <- runner.run(rollouts.claimDue(owner, token, now, now.plusMillis(settings.claimLease.toMillis),
      settings.batchSize))
    _ <- claimed.parTraverse_(r => guarded(r, token).handleErrorWith {
      case FleetRolloutLeaseLost => logger.warn(s"remnawave.fleet.rollout.lease_lost rolloutId=${r.id}")
      case _ => logger.warn(s"remnawave.fleet.rollout.step_failed rolloutId=${r.id}")
    })
    _ <- service.cleanup(now, settings.cleanupBatch).void.handleError(_ => ())
  } yield ()

  private def heartbeat(id: UUID, token: UUID): IO[Unit] = (for {
    _ <- IO.sleep(settings.claimLease / 3)
    now <- IO.realTimeInstant
    ok <- runner.run(rollouts.renewClaim(id, token, now, now.plusMillis(settings.claimLease.toMillis)))
  } yield ok).iterateWhile(identity).void

  private def guarded(r: RemnawaveFleetRollout, token: UUID): IO[Unit] =
    IO.race(step(r, token), heartbeat(r.id, token).productR(IO.raiseError[Unit](FleetRolloutLeaseLost))).void

  private def write(r: RemnawaveFleetRollout, token: UUID, release: Boolean, clearPause: Boolean = false): IO[Unit] = for {
    now <- IO.realTimeInstant
    ok <- runner.run(rollouts.save(r, token, now, clearPause, release))
    _ <- IO.raiseUnless(ok)(FleetRolloutLeaseLost)
  } yield ()

  private def writeMember(m: RemnawaveFleetRolloutMember, token: UUID): IO[RemnawaveFleetRolloutMember] = for {
    now <- IO.realTimeInstant
    ok <- runner.run(rollouts.saveMember(m, token, now))
    _ <- IO.raiseUnless(ok)(FleetRolloutLeaseLost)
  } yield m

  private def later(r: RemnawaveFleetRollout, now: Instant, wait: FiniteDuration = settings.pollInterval) =
    r.copy(nextRunAt = now.plusMillis(wait.toMillis))

  private def terminal(r: RemnawaveFleetRollout, state: FleetRolloutState, code: Option[String], now: Instant,
    incomplete: Boolean = false) = r.copy(state = state,
      phase = if (state == FleetRolloutState.Succeeded || state == FleetRolloutState.RolledBack) FleetRolloutPhase.Complete else r.phase,
      failureCode = code,
    finishedAt = Some(now), rollbackIncomplete = incomplete, nextRunAt = now, phaseStartedAt = now)

  private def finalize(r: RemnawaveFleetRollout, token: UUID): IO[Unit] =
    write(r, token, release = true, clearPause = true) *> service.recordOutcome(r).handleError(_ => ()) *>
      logger.info(s"remnawave.fleet.rollout.finished rolloutId=${r.id} fleetId=${r.fleetId} state=${r.state.code}")

  private def planOf(r: RemnawaveFleetRollout, m: RemnawaveFleetRolloutMember) =
    r.snapshot.members.find(_.membershipId == m.membershipId)

  private def step(claimed: RemnawaveFleetRollout, token: UUID): IO[Unit] = for {
    now <- IO.realTimeInstant
    fresh <- runner.run(rollouts.rolloutById(claimed.id))
    r <- IO.fromOption(fresh)(FleetRolloutLeaseLost)
    all <- runner.run(rollouts.members(r.id))
    _ <- r.state match {
      case FleetRolloutState.RollingBack => rollback(r, all, now, token)
      case _ if r.rollbackRequestedAt.nonEmpty => write(later(r.copy(state = FleetRolloutState.RollingBack,
          phase = FleetRolloutPhase.Rollback, phaseStartedAt = now, startedAt = r.startedAt.orElse(Some(now)),
          rollbackScope = r.rollbackRequestedScope.getOrElse(r.rollbackScope)), now, Duration.Zero), token,
          release = true, clearPause = true)
      case _ if r.pauseRequestedAt.nonEmpty => write(r.copy(state = FleetRolloutState.Paused, pausedAt = Some(now),
          pauseReason = Some("OPERATOR")), token, release = true, clearPause = true)
      case _ => forward(r, all, now, token)
    }
  } yield ()

  private def forward(r: RemnawaveFleetRollout, all: List[RemnawaveFleetRolloutMember], now: Instant,
    token: UUID): IO[Unit] = {
    val running = r.copy(state = FleetRolloutState.Running, startedAt = r.startedAt.orElse(Some(now)))
    r.phase match {
      case FleetRolloutPhase.Validate => service.drift(r.organizationId, r, now).pipe(runner.run(_)).flatMap {
        case Some(code) => finalize(terminal(running, FleetRolloutState.Failed, Some(code), now), token)
        case None =>
          val next = if (r.snapshot.shared.required) FleetRolloutPhase.ApplySharedConfig else firstApply(r)
          write(later(running.copy(phase = next, phaseStartedAt = now), now, Duration.Zero), token, release = true)
      }
      case FleetRolloutPhase.ApplySharedConfig => sharedConfig(running, now, token)
      case FleetRolloutPhase.VerifySharedConfig => gate(running, all, now, token, FleetRolloutPhase.VerifySharedConfig)
      case FleetRolloutPhase.ApplyCanary | FleetRolloutPhase.ApplyWaves => applyWave(running, all, now, token)
      case FleetRolloutPhase.VerifyCanary | FleetRolloutPhase.VerifyWave | FleetRolloutPhase.FinalVerify =>
        gate(running, all, now, token, r.phase)
      case _ => write(later(running, now), token, release = true)
    }
  }

  private def firstApply(r: RemnawaveFleetRollout) =
    if (r.waveCount == 0) FleetRolloutPhase.FinalVerify else FleetRolloutPhase.ApplyCanary

  private def sharedConfig(r: RemnawaveFleetRollout, now: Instant, token: UUID): IO[Unit] = {
    val child = children.owned(r.id, token)
    val shared = r.snapshot.shared
    val actor = ActorContext(r.createdBy, r.organizationId)
    val id = UUID.nameUUIDFromBytes(s"${r.id}/shared".getBytes("UTF-8"))
    def action(rollback: Boolean, kind: Int) = RemnawaveFleetRolloutAction(id, r.organizationId, r.id, None, rollback,
      FleetActionKind.ConfigRollout, 0, FleetActionState.Running, UUID.nameUUIDFromBytes(s"${r.id}/shared/child".getBytes("UTF-8")),
      None, None, None, None, None, None, Some(now), None, 1L, now)
    for {
      existing <- runner.run(rollouts.actions(r.id)).map(_.find(a => a.memberId.isEmpty && a.kind == FleetActionKind.ConfigRollout && !a.rollback))
      outcome <- existing match {
        case Some(a) if a.state == FleetActionState.Succeeded || a.state == FleetActionState.Skipped =>
          IO.pure(ChildOutcome.Succeeded)
        case Some(a) if a.state == FleetActionState.Failed =>
          IO.pure(ChildOutcome.Failed(a.failureCode.getOrElse("REMNAWAVE_FLEET_ROLLOUT_CHILD_FAILED")))
        case Some(a) if a.state == FleetActionState.Unknown =>
          IO.pure(ChildOutcome.Unknown(a.failureCode.getOrElse("REMNAWAVE_FLEET_ROLLOUT_CHILD_UNKNOWN")))
        case _ =>
          val base = existing.getOrElse(action(rollback = false, 0))
          for {
            recovered <- if (base.configRolloutId.nonEmpty) IO.pure(base.configRolloutId)
              else child.configByRequest(r.organizationId, base.childRequestId)
            started <- recovered match {
              case Some(cid) => IO.pure(Right(cid))
              case None => for {
                impact <- child.sharedImpact(r.organizationId, r.integrationId, shared.inventoryConfigProfileId,
                  shared.revisionNumber).attempt
                checkAt <- IO.realTimeInstant
                admission <- impact.traverse(value => runner.run(service.sharedAdmission(r.organizationId, r, value, checkAt)))
                result <- admission match {
                  case Left(e) => IO.pure(Left(ChildOutcome.Failed(FleetRolloutChildren.codeOf(e))))
                  case Right(Some(code)) => IO.pure(Left(ChildOutcome.Failed(code)))
                  case Right(None) => saveAction(base, token) *> child.startConfig(actor, r.integrationId,
                    shared.inventoryConfigProfileId, shared.revisionNumber, base.childRequestId).attempt
                    .map(_.leftMap(e => ChildOutcome.Failed(FleetRolloutChildren.codeOf(e))))
                }
              } yield result
            }
            result <- started match {
              case Left(done) => IO.pure(done)
              case Right(cid) => saveAction(base.copy(configRolloutId = Some(cid)), token) *>
                pollConfig(r.organizationId, cid)
            }
            finished <- IO.realTimeInstant
            state = result match {
              case ChildOutcome.Succeeded => if (base.configRolloutId.isEmpty && started.isLeft) FleetActionState.Skipped
                else FleetActionState.Succeeded
              case ChildOutcome.Unknown(_) => FleetActionState.Unknown
              case _ => FleetActionState.Failed
            }
            _ <- saveAction(base.copy(state = state, configRolloutId = started.toOption.orElse(base.configRolloutId),
              finishedAt = Some(finished), failureCode = result match {
                case ChildOutcome.Failed(c) => Some(c)
                case ChildOutcome.Unknown(c) => Some(c)
                case _ => None
              }), token)
          } yield result
      }
      _ <- outcome match {
        case ChildOutcome.Succeeded => IO.realTimeInstant.flatMap(done =>
          children.refresh(r.organizationId, r.integrationId, r.fleetId, done) *>
            write(later(r.copy(phase = FleetRolloutPhase.VerifySharedConfig, phaseStartedAt = done), done), token,
              release = true))
        case ChildOutcome.Unknown(code) => finalize(terminal(r, FleetRolloutState.Unknown, Some(code), now), token)
        case ChildOutcome.Failed(code) => finalize(terminal(r, FleetRolloutState.Failed, Some(code), now), token)
        case ChildOutcome.Running => write(later(r, now), token, release = true)
      }
    } yield ()
  }

  private def saveAction(a: RemnawaveFleetRolloutAction, token: UUID): IO[Unit] = for {
    now <- IO.realTimeInstant
    ok <- runner.run(rollouts.saveAction(a, token, now))
    _ <- IO.raiseUnless(ok)(FleetRolloutLeaseLost)
  } yield ()

  private def pollConfig(org: UUID, id: UUID): IO[ChildOutcome] = {
    def loop: IO[ChildOutcome] = children.configOutcome(org, id).flatMap {
      case ChildOutcome.Running => IO.sleep(settings.pollInterval) *> loop
      case other => IO.pure(other)
    }
    loop.timeoutTo(settings.verifyTimeout, IO.pure(ChildOutcome.Unknown("REMNAWAVE_FLEET_ROLLOUT_ACTION_TIMEOUT")))
  }

  private def waveMembers(r: RemnawaveFleetRollout, all: List[RemnawaveFleetRolloutMember], wave: Int) =
    all.filter(m => m.wave == wave && m.state != FleetRolloutMemberState.Skipped).sortBy(_.position)

  private def applyWave(r: RemnawaveFleetRollout, all: List[RemnawaveFleetRolloutMember], now: Instant,
    token: UUID): IO[Unit] = {
    val todo = waveMembers(r, all, r.currentWave).filter(m => m.state == FleetRolloutMemberState.Pending ||
      m.state == FleetRolloutMemberState.Running)
    val admission = IO.realTimeInstant.flatMap(at => runner.run(service.drift(r.organizationId, r, at)))
    for {
      _ <- write(r, token, release = false)
      results <- todo.parTraverseN(settings.maxMemberConcurrency) { m =>
        planOf(r, m).fold(IO.pure[MemberResult](MemberResult.Failed("REMNAWAVE_FLEET_ROLLOUT_SNAPSHOT_INVALID"))) { plan =>
          for {
            control <- runner.run(rollouts.rolloutById(r.id))
            result <- if (control.exists(value => value.pauseRequestedAt.nonEmpty || value.rollbackRequestedAt.nonEmpty))
              IO.pure[MemberResult](MemberResult.Stopped)
            else for {
              denied <- if (m.state == FleetRolloutMemberState.Pending) admission else IO.pure(None)
              result <- denied match {
                case Some(code) => IO.pure[MemberResult](MemberResult.AdmissionDenied(code))
                case None => for {
                    started <- IO.realTimeInstant
                    running <- writeMember(m.copy(state = FleetRolloutMemberState.Running, startedAt = m.startedAt.orElse(Some(started))), token)
                    result <- members.forward(r, running, plan, token, admission)
                    finished <- IO.realTimeInstant
                    _ <- writeMember(running.copy(finishedAt = if (result == MemberResult.Stopped || result.isInstanceOf[MemberResult.AdmissionDenied]) None else Some(finished), state = result match {
                      case MemberResult.Done => FleetRolloutMemberState.Succeeded
                      case MemberResult.Stopped => FleetRolloutMemberState.Running
                      case MemberResult.AdmissionDenied(_) => FleetRolloutMemberState.Running
                      case MemberResult.Unknown(_) => FleetRolloutMemberState.Unknown
                      case _ => FleetRolloutMemberState.Failed
                    }, failureCode = result match {
                      case MemberResult.Failed(c) => Some(c)
                      case MemberResult.Unknown(c) => Some(c)
                      case MemberResult.Incomplete(c) => Some(c)
                      case MemberResult.Done => None
                      case MemberResult.Waiting => None
                      case MemberResult.Stopped => None
                      case MemberResult.AdmissionDenied(_) => None
                    }), token)
                } yield result
              }
            } yield result
          } yield result
        }
      }
      finished <- IO.realTimeInstant
      unknown = results.collectFirst { case MemberResult.Unknown(c) => c }
      failed = results.collectFirst { case MemberResult.Failed(c) => c }
      denied = results.collectFirst { case MemberResult.AdmissionDenied(c) => c }
      _ <- (unknown, failed) match {
        case (Some(code), _) => finalize(terminal(r, FleetRolloutState.Unknown, Some(code), finished), token)
        case (None, Some(code)) => failure(r, code, finished, token)
        case _ if denied.nonEmpty =>
          if (denied.contains(FleetRolloutPreconditions.RefreshRequired))
            write(r.copy(state = FleetRolloutState.Paused, pausedAt = Some(finished),
              pauseReason = Some("PAUSED_REFRESH_REQUIRED"), failureCode = denied), token,
              release = true, clearPause = true)
          else finalize(terminal(r, FleetRolloutState.Failed, denied, finished), token)
        case _ if results.contains(MemberResult.Stopped) =>
          runner.run(rollouts.rolloutById(r.id)).flatMap {
            case Some(latest) if latest.rollbackRequestedAt.nonEmpty =>
              write(later(r, finished, Duration.Zero), token, release = true)
            case _ => write(r.copy(state = FleetRolloutState.Paused, pausedAt = Some(finished),
              pauseReason = Some("OPERATOR")), token, release = true, clearPause = true)
          }
        case _ =>
          val phase = if (r.currentWave == 0 && r.snapshot.policy.canaryMembershipIds.nonEmpty)
            FleetRolloutPhase.VerifyCanary else FleetRolloutPhase.VerifyWave
          children.refresh(r.organizationId, r.integrationId, r.fleetId, finished) *>
            write(later(r.copy(phase = phase, phaseStartedAt = finished), finished), token, release = true)
      }
    } yield ()
  }

  private def failure(r: RemnawaveFleetRollout, code: String, now: Instant, token: UUID): IO[Unit] =
    if (r.automaticRollback) write(later(r.copy(state = FleetRolloutState.RollingBack, phase = FleetRolloutPhase.Rollback,
      failureCode = Some(code), phaseStartedAt = now, rollbackScope = r.snapshot.policy.rollbackScope), now,
      Duration.Zero), token, release = true)
    else finalize(terminal(r, FleetRolloutState.Failed, Some(code), now), token)

  private def scopeOf(r: RemnawaveFleetRollout, all: List[RemnawaveFleetRolloutMember], phase: FleetRolloutPhase) =
    phase match {
      case FleetRolloutPhase.FinalVerify | FleetRolloutPhase.VerifySharedConfig => all
      case _ => all.filter(m => m.state == FleetRolloutMemberState.Skipped || m.wave <= r.currentWave)
    }

  private def gate(r: RemnawaveFleetRollout, all: List[RemnawaveFleetRolloutMember], now: Instant, token: UUID,
    phase: FleetRolloutPhase): IO[Unit] = {
    val scope = scopeOf(r, all, phase)
    val mutationEnd = all.flatMap(_.finishedAt).sortBy(_.toEpochMilli).lastOption.getOrElse(r.phaseStartedAt)
    for {
      rows <- runner.run(query.memberRows(r.organizationId, r.fleetId))
      verdict = FleetRolloutGate.evaluate(rows, scope.map(m => m.membershipId -> m.membershipVersion),
        r.snapshot.revisionId, r.phaseStartedAt, mutationEnd, requireHealthy = true,
        sharedOnly = phase == FleetRolloutPhase.VerifySharedConfig,
        localMembers = rows.filter(_.localManaged).map(_.membership.id).toSet,
        serverMembers = scope.filter(m => m.state == FleetRolloutMemberState.Succeeded &&
          m.plannedActions.contains(FleetActionKind.ServerProfileApply)).map(_.membershipId).toSet,
        serverAfter = r.startedAt)
      _ <- verdict match {
        case GateResult.Waiting =>
          if (now.isAfter(r.phaseStartedAt.plusMillis(settings.verifyTimeout.toMillis)))
            write(r.copy(state = FleetRolloutState.Paused, pausedAt = Some(now), pauseReason = Some("PAUSED_EVIDENCE_TIMEOUT")),
              token, release = true, clearPause = true)
          else write(later(r, now), token, release = true)
        case GateResult.Degraded(_) =>
          write(r.copy(state = FleetRolloutState.Paused, pausedAt = Some(now), pauseReason = Some("PAUSED_HEALTH_GATE"),
            phase = phase),
            token, release = true, clearPause = true)
        case GateResult.Failed(ids, code) =>
          scope.filter(m => ids.contains(m.membershipId) && m.state == FleetRolloutMemberState.Succeeded)
            .traverse_(m => writeMember(m.copy(state = FleetRolloutMemberState.Failed, failureCode = Some(code),
              finishedAt = Some(now)), token)) *> failure(r, code, now, token)
        case GateResult.Passed => phase match {
          case FleetRolloutPhase.FinalVerify => finalize(terminal(r, FleetRolloutState.Succeeded, None, now), token)
          case FleetRolloutPhase.VerifySharedConfig =>
            write(later(r.copy(phase = firstApply(r), phaseStartedAt = now), now, Duration.Zero), token, release = true)
          case _ =>
            val (next, wave) = nextAfterGate(r, phase, now)
            val pause = phase == FleetRolloutPhase.VerifyCanary && r.pauseAfterCanary && next != FleetRolloutPhase.FinalVerify
            val updated = r.copy(phase = next, currentWave = wave, phaseStartedAt = now)
            if (next == FleetRolloutPhase.FinalVerify) children.refresh(r.organizationId, r.integrationId, r.fleetId, now) *>
              write(later(updated, now), token, release = true)
            else if (pause) write(updated.copy(state = FleetRolloutState.Paused, pausedAt = Some(now),
              pauseReason = Some("PAUSED_AFTER_CANARY")), token, release = true, clearPause = true)
            else write(later(updated, now, Duration.Zero), token, release = true)
        }
      }
    } yield ()
  }

  private def nextAfterGate(r: RemnawaveFleetRollout, phase: FleetRolloutPhase, now: Instant) = phase match {
    case FleetRolloutPhase.VerifyCanary | FleetRolloutPhase.VerifyWave =>
      if (r.currentWave + 1 >= r.waveCount) (FleetRolloutPhase.FinalVerify, r.currentWave)
      else (FleetRolloutPhase.ApplyWaves, r.currentWave + 1)
    case other => (other, r.currentWave)
  }

  private def rollback(r: RemnawaveFleetRollout, all: List[RemnawaveFleetRolloutMember], now: Instant,
    token: UUID): IO[Unit] = {
    val inScope = (if (r.rollbackScope == FleetRollbackScope.AllCompleted) all.filter(_.wave <= r.currentWave)
    else all.filter(_.wave == r.currentWave)).filter(m => m.state != FleetRolloutMemberState.Skipped &&
      m.state != FleetRolloutMemberState.Pending && m.state != FleetRolloutMemberState.RolledBack)
    val byWave = inScope.groupBy(_.wave).toList.sortBy(-_._1).map(_._2.sortBy(-_.position))
    for {
      _ <- write(r, token, release = false)
      results <- byWave.flatTraverse { wave =>
        wave.parTraverseN(settings.maxMemberConcurrency) { m =>
          planOf(r, m).fold(IO.pure[(RemnawaveFleetRolloutMember, MemberResult)](m -> MemberResult.Incomplete(
            "REMNAWAVE_FLEET_ROLLOUT_SNAPSHOT_INVALID"))) { plan =>
            members.compensate(r, m, plan, token).map(m -> _)
          }
        }
      }
      finished <- IO.realTimeInstant
      _ <- results.traverse_ { case (m, result) =>
        writeMember(m.copy(finishedAt = Some(finished), state = result match {
          case MemberResult.Done => FleetRolloutMemberState.RolledBack
          case MemberResult.Waiting => FleetRolloutMemberState.RollingBack
          case _ => m.state
        }, rollbackFailureCode = result match {
          case MemberResult.Failed(c) => Some(c)
          case MemberResult.Incomplete(c) => Some(c)
          case MemberResult.Unknown(c) => Some(c)
          case MemberResult.Done => None
          case MemberResult.Waiting => None
          case MemberResult.Stopped => None
          case MemberResult.AdmissionDenied(c) => Some(c)
        }), token)
      }
      unknown = results.collectFirst { case (_, MemberResult.Unknown(c)) => c }
      incomplete = results.exists { case (_, x) => x.isInstanceOf[MemberResult.Failed] || x.isInstanceOf[MemberResult.Incomplete] }
      _ <- unknown match {
        case Some(code) => finalize(terminal(r, FleetRolloutState.Unknown, Some(code), finished, incomplete), token)
        case None if results.exists(_._2 == MemberResult.Waiting) => write(later(r, finished), token, release = true)
        case None =>
          rollbackShared(r, token).flatMap {
            case ChildOutcome.Unknown(code) => finalize(terminal(r, FleetRolloutState.Unknown, Some(code), finished, true), token)
            case ChildOutcome.Failed(code) => finalize(terminal(r, FleetRolloutState.Failed, Some(code), finished, true), token)
            case ChildOutcome.Running => write(later(r, finished), token, release = true)
            case ChildOutcome.Succeeded =>
              val state = if (incomplete) FleetRolloutState.Failed else FleetRolloutState.RolledBack
              finalize(terminal(r, state, r.failureCode.orElse(Option.when(incomplete)("REMNAWAVE_FLEET_ROLLBACK_INCOMPLETE")),
                finished, incomplete), token)
          }
      }
    } yield ()
  }

  private def rollbackShared(r: RemnawaveFleetRollout, token: UUID): IO[ChildOutcome] = for {
    actions <- runner.run(rollouts.actions(r.id))
    forward = actions.find(a => a.memberId.isEmpty && !a.rollback && a.kind == FleetActionKind.ConfigRollout)
    outcome <- forward match {
      case None => IO.pure[ChildOutcome](ChildOutcome.Succeeded)
      case Some(a) if a.state == FleetActionState.Skipped => IO.pure[ChildOutcome](ChildOutcome.Succeeded)
      case Some(a) if a.state != FleetActionState.Succeeded =>
        IO.pure[ChildOutcome](ChildOutcome.Unknown("REMNAWAVE_FLEET_ROLLBACK_SHARED_FORWARD_UNCERTAIN"))
      case Some(_) if r.currentWave > 0 && r.rollbackScope == FleetRollbackScope.CurrentWave =>
        IO.pure[ChildOutcome](ChildOutcome.Failed("REMNAWAVE_FLEET_ROLLBACK_SHARED_SCOPE_UNSAFE"))
      case Some(_) => r.snapshot.shared.baselineRevisionNumber match {
        case None => IO.pure[ChildOutcome](ChildOutcome.Failed("REMNAWAVE_FLEET_ROLLBACK_NO_SHARED_BASELINE"))
        case Some(number) =>
          val child = children.owned(r.id, token)
          for {
            now <- IO.realTimeInstant
            existing = actions.find(a => a.memberId.isEmpty && a.rollback && a.kind == FleetActionKind.ConfigRollout)
            action = existing.getOrElse(RemnawaveFleetRolloutAction(
              UUID.nameUUIDFromBytes(s"${r.id}/shared/rollback".getBytes("UTF-8")), r.organizationId, r.id, None, true,
              FleetActionKind.ConfigRollout, 0, FleetActionState.Running,
              UUID.nameUUIDFromBytes(s"${r.id}/shared/rollback/child".getBytes("UTF-8")), None, None, None, None,
              None, None, Some(now), None, 1L, now))
            result <- if (action.state == FleetActionState.Succeeded) IO.pure[ChildOutcome](ChildOutcome.Succeeded)
              else if (action.state == FleetActionState.Failed) IO.pure[ChildOutcome](ChildOutcome.Failed(
                action.failureCode.getOrElse("REMNAWAVE_FLEET_ROLLBACK_SHARED_FAILED")))
              else if (action.state == FleetActionState.Unknown) IO.pure[ChildOutcome](ChildOutcome.Unknown(
                action.failureCode.getOrElse("REMNAWAVE_FLEET_ROLLBACK_SHARED_UNKNOWN")))
              else for {
                _ <- saveAction(action, token)
                recovered <- child.configByRequest(r.organizationId, action.childRequestId)
                impact <- child.sharedImpact(r.organizationId, r.integrationId, r.snapshot.shared.inventoryConfigProfileId, number)
                started <- action.configRolloutId.orElse(recovered) match {
                  case Some(id) => IO.pure(Some(id))
                  case None if impact.isEmpty => IO.pure(Option.empty[UUID])
                  case None => child.startConfig(ActorContext(r.createdBy, r.organizationId), r.integrationId,
                    r.snapshot.shared.inventoryConfigProfileId, number, action.childRequestId).map(Some(_))
                }
                withId = action.copy(configRolloutId = started)
                _ <- saveAction(withId, token)
                observed <- started.fold(IO.pure[ChildOutcome](ChildOutcome.Succeeded))(id => pollConfig(r.organizationId, id))
                end <- IO.realTimeInstant
                _ <- saveAction(withId.copy(state = observed match {
                  case ChildOutcome.Succeeded => FleetActionState.Succeeded
                  case ChildOutcome.Unknown(_) => FleetActionState.Unknown
                  case _ => FleetActionState.Failed
                }, finishedAt = Some(end), failureCode = observed match {
                  case ChildOutcome.Failed(code) => Some(code)
                  case ChildOutcome.Unknown(code) => Some(code)
                  case _ => None
                }), token)
              } yield observed
          } yield result
      }
    }
  } yield outcome

  private implicit class PipeOps[A](value: A) { def pipe[B](f: A => B): B = f(value) }
}
