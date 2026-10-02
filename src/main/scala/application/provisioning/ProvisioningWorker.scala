package ru.bitec.app.ops
package application.provisioning

import application.port._
import cats.effect.IO
import cats.MonadThrow
import cats.syntax.all._
import domain.provisioning._
import org.typelevel.log4cats.Logger
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** Small bounded worker. Remote observation is outside transactions; each boundary is lease-fenced. */
final class ProvisioningWorker[Tx[_]: MonadThrow](runs: ProvisioningRunRepository[Tx], targets: ProvisioningTargetQuery[Tx],
  transport: ProvisioningTransport[IO], runner: TransactionRunner[IO, Tx], settings: ProvisioningSettings,
  logger: Logger[IO], owner: UUID = UUID.randomUUID(), clock: IO[Instant] = IO.realTimeInstant,
  scope: Option[UUID] = None, profileHandler: Option[ProvisioningProfileApplyHandler[IO,Tx]] = None) {

  def run: IO[Nothing] = if (!settings.enabled) IO.never else loop
  def tick: IO[Unit] = if (!settings.enabled) IO.unit else pollOnce

  private def loop: IO[Nothing] = IO.defer(
    (pollOnce.handleErrorWith(_ => logger.warn("provisioning.worker.poll_failed")) *>
      IO.sleep(settings.pollInterval)) *> loop)

  private def pollOnce: IO[Unit] = for {
    now <- clock
    token <- IO(UUID.randomUUID())
    limit = math.min(settings.batchSize, settings.maxConcurrency)
    claimed <- runner.run(runs.claim(owner, token, now, now.plusMillis(settings.leaseDuration.toMillis), limit, scope))
    _ <- claimed.parTraverse_(process)
  } yield ()

  private def process(claimed: ProvisioningRun): IO[Unit] = claimed.claimToken match {
    case None => IO.unit
    case Some(token) =>
      val loopSteps = claimed.input.steps.foldLeft(IO.pure(true)) { (previous, kind) =>
        previous.flatMap {
          case false => IO.pure(false)
          case true => executeStep(claimed, token, kind)
        }
      }
      loopSteps.handleErrorWith { case LostLease => IO.pure(false) }.flatMap {
        case false => IO.unit
        case true => clock.flatMap(now => runner.run(runs.finish(claimed.organizationId,
          claimed.id, token, ProvisioningRunState.Succeeded, None, now))).void
      }
  }

  private def executeStep(run: ProvisioningRun, token: UUID, kind: ProvisioningStepKind): IO[Boolean] = for {
    before <- clock
    lease <- runner.run(runs.renew(run.organizationId, run.id, token, before,
      before.plusMillis(settings.leaseDuration.toMillis)))
    target <- if (!lease) IO.pure(Left("LEASE_LOST")) else runner.run(targets.eligible(run.organizationId, run.resourceId))
    selected = target.toOption.filter(t => t.connectionId == run.input.connectionId &&
      t.connectionUpdatedAt == run.input.connectionUpdatedAt && t.resourceType == run.input.resourceType &&
      t.resourceKind == run.input.resourceKind)
    startedAt <- clock
    began <- if (!lease) IO.pure(false) else runner.run(runs.beginStep(run.organizationId, run.id, token, kind, startedAt))
    completed <- if (!lease || !began) IO.pure(false)
      else if (target.isLeft || selected.isEmpty) completeFailure(run, token, kind,
      ProvisioningStepResult(Map.empty, Some(target.swap.toOption.getOrElse("PROVISIONING_SOURCE_CHANGED")), None), uncertain = false).as(false)
      else {
        val connection = selected.get.connection
        val isProfile = run.input.runKind == ProvisioningRunKind.ServerProfileApply
        val observation: IO[ProvisioningStepResult] = if (isProfile) {
          if (run.input.profileApply.isEmpty || profileHandler.isEmpty)
            IO.pure(ProvisioningStepResult(Map.empty, Some("PROVISIONING_PROFILE_SNAPSHOT_INVALID"), None))
          else profileHandler.get.validateBoundary(run,connection,kind).flatMap {
            case Some(result) => IO.pure(result)
            case None if kind == ProvisioningStepKind.Preflight => transport.preflight(connection)
            case None if kind == ProvisioningStepKind.Verify => transport.verify(connection).flatMap { readiness =>
              if (readiness.failureCode.nonEmpty || readiness.uncertain || readiness.outputTruncated || readiness.verificationResult != Some(true))
                IO.pure(readiness.copy(verificationResult=Some(false),failureCode=readiness.failureCode.orElse(Some("PROVISIONING_VERIFICATION_FAILED"))))
              else profileHandler.get.execute(run,connection,kind)
            }
            case None => profileHandler.get.execute(run,connection,kind)
          }
        } else kind match {
          case ProvisioningStepKind.Preflight => transport.preflight(connection)
          case ProvisioningStepKind.Verify => transport.verify(connection)
          case _ => IO.pure(ProvisioningStepResult(Map.empty,Some("PROVISIONING_STEP_UNSUPPORTED"),None))
        }
        withHeartbeat(run, token)(observation.timeoutTo(settings.stepTimeout,
          IO.pure(ProvisioningStepResult(Map.empty, Some("PROVISIONING_REMOTE_TIMEOUT"), None, uncertain = true))))
          .handleErrorWith {
            case LostLease => IO.raiseError(LostLease)
            case _ => IO.pure(ProvisioningStepResult(Map.empty,
              Some("PROVISIONING_REMOTE_UNAVAILABLE"), None, uncertain = true))
          }
          .flatMap { result =>
            val normalized = if (result.failureCode.isEmpty && result.verificationResult.contains(false))
              result.copy(failureCode = Some(if (kind == ProvisioningStepKind.Verify)
                "PROVISIONING_VERIFICATION_FAILED" else "PROVISIONING_PREFLIGHT_FAILED"))
            else if (kind == ProvisioningStepKind.Verify && result.failureCode.isEmpty && result.verificationResult != Some(true))
              result.copy(failureCode = Some("PROVISIONING_VERIFICATION_FAILED"))
            else result
            val uncertain = normalized.uncertain || normalized.outputTruncated
            val invalidSkip = normalized.skipped && (kind == ProvisioningStepKind.Preflight || kind == ProvisioningStepKind.Verify ||
              !ProvisioningStepKind.ProfileApply.contains(kind) ||
              !Set("NOT_MANAGED","ALREADY_COMPLIANT")(normalized.facts.getOrElse("skipReason","")))
            if (invalidSkip)
              completeFailure(run,token,kind,normalized.copy(failureCode=Some("PROVISIONING_INVALID_SKIP_REASON")),uncertain=false).as(false)
            else if (isProfile && kind == ProvisioningStepKind.Verify && normalized.profileObservation.nonEmpty)
              recordProfileVerification(run,token,connection,normalized,uncertain)
            else if (normalized.failureCode.isDefined || uncertain) completeFailure(run, token, kind, normalized, uncertain).as(false)
            else completeSuccess(run, token, kind, normalized)
          }
      }
  } yield completed

  private def recordProfileVerification(run: ProvisioningRun, token: UUID, connection: domain.connection.Connection,
    result: ProvisioningStepResult, uncertain: Boolean): IO[Boolean] = for {
    now <- clock
    state: ProvisioningStepState = if (uncertain) ProvisioningStepState.Unknown else result.failureCode.fold[ProvisioningStepState](ProvisioningStepState.Succeeded)(_ => ProvisioningStepState.Failed)
    terminal: Option[ProvisioningRunState] = if (uncertain) Some(ProvisioningRunState.Unknown) else result.failureCode.map(_ => ProvisioningRunState.Failed)
    saved <- runner.run(profileHandler.get.recordVerification(run,token,connection,result,state,result.failureCode,terminal,now))
    _ <- if (saved && terminal.nonEmpty) logger.info(s"provisioning.step.failed runId=${run.id} step=VERIFY code=${result.failureCode.getOrElse("PROVISIONING_UNKNOWN")}") else IO.unit
  } yield saved && terminal.isEmpty

  private def completeSuccess(run: ProvisioningRun, token: UUID, kind: ProvisioningStepKind,
    result: ProvisioningStepResult): IO[Boolean] = for {
    now <- clock
    state = if (result.skipped) ProvisioningStepState.Skipped else ProvisioningStepState.Succeeded
    persisted <- runner.run(runs.finishStep(run.organizationId, run.id, token, kind,
      state, result.facts, None, result.outputTruncated, now, result.verificationResult))
  } yield persisted

  private def completeFailure(run: ProvisioningRun, token: UUID, kind: ProvisioningStepKind,
    result: ProvisioningStepResult, uncertain: Boolean): IO[Unit] = for {
    now <- clock
    state = if (uncertain) ProvisioningStepState.Unknown else ProvisioningStepState.Failed
    skippedAt <- clock
    endedAt <- clock
    finalState = if (uncertain) ProvisioningRunState.Unknown else ProvisioningRunState.Failed
    _ <- runner.run(for {
      stepSaved <- runs.finishStep(run.organizationId, run.id, token, kind, state, result.facts, result.failureCode,
        result.outputTruncated, now, result.verificationResult)
      _ <- MonadThrow[Tx].raiseUnless(stepSaved)(LostLease)
      pendingSkipped <- runs.skipPending(run.organizationId, run.id, token, skippedAt)
      _ <- MonadThrow[Tx].raiseUnless(pendingSkipped)(LostLease)
      runFinished <- runs.finish(run.organizationId, run.id, token, finalState, result.failureCode, endedAt)
      _ <- MonadThrow[Tx].raiseUnless(runFinished)(LostLease)
    } yield ()).void
    _ <- logger.info(s"provisioning.step.failed runId=${run.id} step=${kind.code} code=${result.failureCode.getOrElse("PROVISIONING_UNKNOWN")}")
  } yield ()

  private def withHeartbeat[A](run: ProvisioningRun, token: UUID)(operation: IO[A]): IO[A] = {
    def heartbeat: IO[Unit] = IO.sleep(settings.heartbeat) *> clock.flatMap { now =>
      runner.run(runs.renew(run.organizationId, run.id, token, now, now.plusMillis(settings.leaseDuration.toMillis)))
        .flatMap(ok => if (ok) heartbeat else IO.raiseError(LostLease))
    }
    operation.race(heartbeat).flatMap {
      case Left(result) => IO.pure(result)
      case Right(_) => IO.raiseError(LostLease)
    }
  }

  private case object LostLease extends RuntimeException

}
