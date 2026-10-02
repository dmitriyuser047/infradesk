package ru.bitec.app.ops
package application.provisioning

import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.provisioning._
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** Bridges immutable apply snapshots to the shared fenced provisioning worker. */
final class ServerProfileApplyCoordinator[Tx[_]: MonadThrow](
  profiles: ServerProfileRepository[Tx], runs: ProvisioningRunRepository[Tx],
  targets: ProvisioningTargetQuery[Tx], remote: ServerProfileRemote[cats.effect.IO],
  reads: TransactionRunner[cats.effect.IO,Tx], leaseDuration: FiniteDuration
) extends ProvisioningProfileApplyHandler[cats.effect.IO,Tx] {
  override def validateBoundary(run: ProvisioningRun, connection: domain.connection.Connection,
    kind: ProvisioningStepKind): cats.effect.IO[Option[ProvisioningStepResult]] = {
      val expected = run.input.profileApply
      val snapshot = ProvisioningInputSnapshot(run.input.schemaVersion,run.input.runKind,run.organizationId,run.resourceId,
        run.input.resourceType,run.input.resourceKind,run.input.connectionId,run.input.connectionUpdatedAt,run.input.steps,expected)
      val check = for {
        _ <- MonadThrow[Tx].raiseUnless(expected.exists(_.blockingProblems.isEmpty))(ProvisioningError.ProfilePlanChanged)
        p = expected.get
        assignment <- profiles.assignment(run.organizationId,run.resourceId)
        observation <- profiles.observation(run.organizationId,run.resourceId)
        revision <- profiles.revision(run.organizationId,p.profileId,p.revisionNumber)
        history <- runs.find(run.organizationId,run.id)
        alreadyChanged = history.toList.flatMap(_._2).exists(s => ProvisioningStepKind.ProfileApply.contains(s.kind) &&
          s.kind != ProvisioningStepKind.Preflight && s.kind != ProvisioningStepKind.Verify &&
          s.position < run.input.steps.indexOf(kind) && s.state == ProvisioningStepState.Succeeded)
        currentSource <- targets.unchanged(snapshot)
        assignmentOk = assignment.exists(a => a.id == p.assignmentId && a.version == p.assignmentVersion &&
          a.profileId == p.profileId && a.revisionId == p.revisionId && a.revisionNumber == p.revisionNumber)
        observationOk = observation.exists(o => o.id == p.observationId && o.contentHash == p.observationHash &&
          o.assignmentId.contains(p.assignmentId) && o.assignmentVersion.contains(p.assignmentVersion) &&
          o.revisionId.contains(p.revisionId) && ServerProfileDiff.reviewedHash(ServerProfileDiff.assess(p.content,o.content)) == p.reviewedDiffHash)
        pinned = revision.exists(r => r.id == p.revisionId && r.contentHash == p.revisionHash && r.content.hash == p.revisionHash)
        _ <- MonadThrow[Tx].raiseUnless(assignmentOk && pinned && currentSource)(ProvisioningError.ProfilePlanChanged)
      } yield alreadyChanged -> observationOk
      reads.run(check).attempt.flatMap {
        case Left(e) => cats.effect.IO.pure(Some(ProvisioningStepResult(Map.empty,Some(e match {
        case p: ProvisioningError => p.code
        case _ => "PROVISIONING_PROFILE_PLAN_CHANGED"
        }),None)))
        case Right((true,_)) => cats.effect.IO.pure(None)
        case Right((false,false)) => cats.effect.IO.pure(Some(ProvisioningStepResult(Map.empty,Some(ProvisioningError.ProfilePlanChanged.code),None)))
        // The independent readiness preflight diagnoses OS, architecture and privileges first.
        // The first module boundary still refreshes the reviewed observation before any mutation.
        case Right(_) if kind==ProvisioningStepKind.Preflight => cats.effect.IO.pure(None)
        case Right(_) => remote.observe(connection,run.resourceId,expected.map(_.content)).map { fresh =>
          fresh.failureCode match {
            case Some(code) => Some(ProvisioningStepResult(Map.empty,Some(code),None,
              uncertain=Set("PROVISIONING_REMOTE_TIMEOUT","PROVISIONING_REMOTE_UNAVAILABLE","PROVISIONING_REMOTE_OUTPUT_LIMIT")(code),
              outputTruncated=code=="PROVISIONING_REMOTE_OUTPUT_LIMIT"))
            case None if fresh.blockingProblems.nonEmpty || fresh.contentHash != expected.get.observationHash ||
              ServerProfileDiff.hashObservation(fresh.content) != fresh.contentHash =>
              Some(ProvisioningStepResult(Map.empty,Some(ProvisioningError.ProfilePlanChanged.code),None))
            case None => None
          }
        }.handleError(_ => Some(ProvisioningStepResult(Map.empty,Some("PROVISIONING_REMOTE_UNAVAILABLE"),None,uncertain=true)))
      }
  }

  override def execute(run: ProvisioningRun, connection: domain.connection.Connection,
    kind: ProvisioningStepKind): cats.effect.IO[ProvisioningStepResult] = run.input.profileApply match {
    case None => cats.effect.IO.pure(ProvisioningStepResult(Map.empty,Some("PROVISIONING_PROFILE_SNAPSHOT_INVALID"),None))
    case Some(snapshot) =>
      val context = reads.run(for {
        journal <- runs.find(run.organizationId,run.id)
        observation <- profiles.observation(run.organizationId,run.resourceId)
      } yield journal.flatMap(j => observation.filter(o => o.id == snapshot.observationId && o.contentHash == snapshot.observationHash)
        .map(o => ProfileExecutionContext(run.id,j._2,o.content))))
      context.flatMap {
        case Some(value) => remote.applyModule(connection,run.resourceId,snapshot,kind,value)
        case None => cats.effect.IO.pure(ProvisioningStepResult(Map.empty,Some("PROVISIONING_PROFILE_PLAN_CHANGED"),None))
      }
  }

  override def recordVerification(run: ProvisioningRun, token: UUID, connection: domain.connection.Connection,
    result: ProvisioningStepResult, state: ProvisioningStepState, failureCode: Option[String],
    terminalState: Option[ProvisioningRunState], now: Instant): Tx[Boolean] = {
    val snapshot = run.input.profileApply
    val observation = result.profileObservation
    (snapshot,observation) match {
      case (Some(p),Some(remoteResult)) => for {
        _ <- profiles.lockResource(run.organizationId,run.resourceId)
        leaseBefore <- runs.renew(run.organizationId,run.id,token,now,now.plusMillis(leaseDuration.toMillis))
        _ <- MonadThrow[Tx].raiseUnless(leaseBefore)(new IllegalStateException("lease lost"))
        stepSaved <- runs.finishStep(run.organizationId,run.id,token,ProvisioningStepKind.Verify,state,result.facts,
          failureCode,result.outputTruncated,now,result.verificationResult)
        _ <- MonadThrow[Tx].raiseUnless(stepSaved)(new IllegalStateException("lease lost"))
        assignment <- profiles.assignment(run.organizationId,run.resourceId)
        currentTarget <- targets.unchanged(run.input)
        validPayload = ServerProfileObservationCodec.validate(remoteResult.content).isRight &&
          ServerProfileDiff.hashObservation(remoteResult.content) == remoteResult.contentHash
        validPin = assignment.exists(a => a.id == p.assignmentId && a.version == p.assignmentVersion &&
          a.revisionId == p.revisionId && a.profileId == p.profileId)
        _ <- MonadThrow[Tx].raiseUnless(validPayload && validPin && currentTarget)(ProvisioningError.ProfilePlanChanged)
        observationId <- MonadThrow[Tx].pure(UUID.nameUUIDFromBytes((run.id.toString + ":verified-observation").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        stored = ServerProfileObservation(observationId,run.organizationId,run.resourceId,run.input.connectionId,
          run.input.connectionUpdatedAt,Some(p.assignmentId),Some(p.assignmentVersion),Some(p.revisionId),
          remoteResult.content,remoteResult.contentHash,now,Some(run.id))
        _ <- profiles.saveObservation(stored)
        leaseAfter <- runs.renew(run.organizationId,run.id,token,now,now.plusMillis(leaseDuration.toMillis))
        _ <- MonadThrow[Tx].raiseUnless(leaseAfter)(new IllegalStateException("lease lost"))
        skipped <- if (terminalState.nonEmpty) runs.skipPending(run.organizationId,run.id,token,now) else true.pure[Tx]
        _ <- MonadThrow[Tx].raiseUnless(skipped)(new IllegalStateException("lease lost"))
        finished <- terminalState.traverse(s => runs.finish(run.organizationId,run.id,token,s,failureCode,now)).map(_.getOrElse(true))
        _ <- MonadThrow[Tx].raiseUnless(finished)(new IllegalStateException("lease lost"))
      } yield true
      case _ => false.pure[Tx]
    }
  }
}
