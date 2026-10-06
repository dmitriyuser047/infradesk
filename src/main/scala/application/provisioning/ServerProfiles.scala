package ru.bitec.app.ops
package application.provisioning

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction,AuditTargetType}
import domain.provisioning._
import io.circe.Json
import java.time.Instant
import java.util.UUID

final case class ServerProfileError(code: String, override val getMessage: String) extends RuntimeException(getMessage)
object ServerProfileError {
  val notFound = ServerProfileError("SERVER_PROFILE_NOT_FOUND","Server profile was not found")
  val duplicate = ServerProfileError("SERVER_PROFILE_CODE_EXISTS","A profile with this code already exists")
  val inUse = ServerProfileError("SERVER_PROFILE_IN_USE","Unassign servers before archiving this profile")
  val archived = ServerProfileError("SERVER_PROFILE_ARCHIVED","Archived profiles cannot be changed or assigned")
  val revisionNotFound = ServerProfileError("SERVER_PROFILE_REVISION_NOT_FOUND","Server profile revision was not found")
  val invalid = ServerProfileError("SERVER_PROFILE_INVALID","Server profile input is invalid")
  val resourceNotFound = ServerProfileError("SERVER_PROFILE_RESOURCE_NOT_FOUND","An active Node server was not found")
  val alreadyActive = ProvisioningError.AlreadyActive
  val disabled = ProvisioningError.Disabled
  val targetNotFound = ProvisioningError.TargetNotFound
  val targetAmbiguous = ProvisioningError.Ambiguous
  val sourceChanged = ProvisioningError.SourceChanged
}

final case class ServerProfilePlan(run: ProvisioningRun, connectionName: String,
  assessment: ServerProfileAssessment, warnings: List[String], blockingProblems: List[String],
  profileName: String, dependencyPackages: List[String])
final case class ServerProfileAutomation(assignment: Option[ServerProfileAssignment], profile: Option[ServerProfile],
  revision: Option[ServerProfileRevision], observation: Option[ServerProfileObservation], state: String,
  assessment: Option[ServerProfileAssessment], activeRun: Option[ProvisioningRun], operationsBlocked: Boolean)

/** Versioned profiles are desired state only. This service never writes remotely except via the explicit apply worker. */
final class ServerProfiles[F[_]: MonadThrow, Tx[_]: MonadThrow](
  repository: ServerProfileRepository[Tx], targets: ProvisioningTargetQuery[Tx],
  runs: ProvisioningRunRepository[Tx], remote: ServerProfileRemote[F], ids: IdGenerator[Tx],
  time: TimeProvider[Tx], audit: AuditRecorder[Tx], reads: TransactionRunner[F,Tx],
  writes: TransactionRunner[F,Tx], settings: ProvisioningSettings
) extends ProvisioningApplyPlanValidator[Tx] {
  import ServerProfileError._

  private val ProfileSteps = ProvisioningStepKind.ProfileApply
  private val CodePattern = "[a-z0-9][a-z0-9_-]{0,63}"

  def list(organizationId: UUID, archived: Boolean, limit: Int): F[List[ServerProfileSummary]] =
    reads.run(repository.profiles(organizationId,archived,limit.max(1).min(200)))

  def detail(organizationId: UUID, profileId: UUID): F[(ServerProfile,List[ServerProfileRevision],List[ServerProfileAssignmentView])] =
    reads.run(for {
      profile <- repository.profile(organizationId,profileId).flatMap(_.liftTo[Tx](notFound))
      revisions <- repository.revisions(organizationId,profileId)
      assignments <- repository.assignmentsForProfile(organizationId,profileId)
    } yield (profile,revisions,assignments))

  def create(actor: ActorContext, codeRaw: String, nameRaw: String, descriptionRaw: Option[String], content: ServerProfileContent): F[(ServerProfile,ServerProfileRevision)] = {
    val code = codeRaw.trim
    val name = nameRaw.trim
    val description = descriptionRaw.map(_.trim).filter(_.nonEmpty)
    MonadThrow[F].raiseUnless(code.matches(CodePattern) && name.nonEmpty && name.length <= 255 &&
      description.forall(_.length <= 4000) && content.schemaVersion == 1)(invalid) *>
      writes.run(for {
        profileId <- ids.nextId; revisionId <- ids.nextId; now <- time.now
        profile = ServerProfile(profileId,actor.organizationId,name,code,description,archived=false,1,actor.userId,now,now)
        revision = ServerProfileRevision(revisionId,actor.organizationId,profileId,1,content,content.hash,actor.userId,now)
        inserted <- repository.insertProfile(profile)
        _ <- MonadThrow[Tx].raiseUnless(inserted)(duplicate)
        _ <- repository.insertRevision(revision)
        _ <- audit.record(actor,AuditAction.ServerProfileCreated,AuditTargetType.ServerProfile,Some(profileId))
      } yield profile -> revision)
  }

  def appendRevision(actor: ActorContext, profileId: UUID, content: ServerProfileContent): F[ServerProfileRevision] =
    writes.run(for {
      profile <- repository.profileForUpdate(actor.organizationId,profileId).flatMap(_.liftTo[Tx](notFound))
      _ <- MonadThrow[Tx].raiseWhen(profile.archived)(archived)
      latest <- repository.revision(actor.organizationId,profileId,profile.latestRevision)
        .flatMap(_.liftTo[Tx](revisionNotFound))
      result <- if (latest.contentHash == content.hash && latest.content.canonical == content.canonical) latest.pure[Tx]
      else for {
        id <- ids.nextId; now <- time.now
        revision = ServerProfileRevision(id,actor.organizationId,profileId,profile.latestRevision+1,content,content.hash,actor.userId,now)
        _ <- repository.insertRevision(revision)
        _ <- repository.updateProfile(profile.copy(latestRevision=revision.number,updatedAt=now))
        _ <- audit.record(actor,AuditAction.ServerProfileRevisionCreated,AuditTargetType.ServerProfile,Some(profileId))
      } yield revision
    } yield result)

  /** Profile-row lock serializes with assign's resource-then-profile order without taking a resource lock. */
  def archive(actor: ActorContext, profileId: UUID): F[ServerProfile] = writes.run(for {
    profile <- repository.profileForUpdate(actor.organizationId,profileId).flatMap(_.liftTo[Tx](notFound))
    assignments <- repository.assignmentsForProfile(actor.organizationId,profileId)
    _ <- MonadThrow[Tx].raiseWhen(assignments.nonEmpty)(inUse)
    result <- if (profile.archived) profile.pure[Tx] else for {
      now <- time.now; next = profile.copy(archived=true,updatedAt=now)
      _ <- repository.updateProfile(next)
      _ <- audit.record(actor,AuditAction.ServerProfileArchived,AuditTargetType.ServerProfile,Some(profileId))
    } yield next
  } yield result)

  /** Assignment is metadata only. It requires an active tenant Node, but no SSH source or contact. */
  def assign(actor: ActorContext, resourceId: UUID, profileId: UUID, revisionNumber: Int): F[ServerProfileAssignment] = writes.run(for {
    _ <- repository.lockResource(actor.organizationId,resourceId)
    activeNode <- repository.assignableNode(actor.organizationId,resourceId)
    _ <- MonadThrow[Tx].raiseUnless(activeNode)(resourceNotFound)
    activeRun <- repository.hasActiveRun(actor.organizationId,resourceId)
    _ <- MonadThrow[Tx].raiseWhen(activeRun)(alreadyActive)
    profile <- repository.profileForUpdate(actor.organizationId,profileId).flatMap(_.liftTo[Tx](notFound))
    _ <- MonadThrow[Tx].raiseWhen(profile.archived)(archived)
    revision <- repository.revision(actor.organizationId,profileId,revisionNumber).flatMap(_.liftTo[Tx](revisionNotFound))
    current <- repository.assignment(actor.organizationId,resourceId)
    result <- if (current.exists(a => a.profileId == profileId && a.revisionId == revision.id && a.revisionNumber == revisionNumber))
      current.get.pure[Tx]
    else for {
      id <- current.fold(ids.nextId)(a => a.id.pure[Tx]); now <- time.now
      next = ServerProfileAssignment(id,actor.organizationId,resourceId,profileId,revision.id,revisionNumber,
        current.fold(1L)(_.version+1),actor.userId,now)
      _ <- repository.storeAssignment(next)
      _ <- audit.record(actor,AuditAction.ServerProfileAssigned,AuditTargetType.Resource,Some(resourceId))
    } yield next
  } yield result)

  /** Removal is allowed for a now-inactive Node, so operators can clean up stale intent. */
  def unassign(actor: ActorContext, resourceId: UUID): F[Option[ServerProfileAssignment]] = writes.run(for {
    _ <- repository.lockResource(actor.organizationId,resourceId)
    active <- repository.hasActiveRun(actor.organizationId,resourceId)
    _ <- MonadThrow[Tx].raiseWhen(active)(alreadyActive)
    removed <- repository.removeAssignment(actor.organizationId,resourceId)
    _ <- removed.traverse_(_ => audit.record(actor,AuditAction.ServerProfileUnassigned,AuditTargetType.Resource,Some(resourceId)))
  } yield removed)

  def observe(actor: ActorContext, resourceId: UUID): F[ServerProfileObservation] = for {
    _ <- ensureEnabled
    initial <- prepareRead(actor.organizationId,resourceId)
    target = initial._1
    assignment = initial._2
    generation = initial._3
    revision = initial._4
    observed <- remote.observe(target.connection,resourceId,revision.map(_.content))
    _ <- MonadThrow[F].raiseWhen(observed.failureCode.nonEmpty)(remoteError(observed.failureCode.getOrElse("PROVISIONING_REMOTE_UNAVAILABLE")))
    _ <- validateObservation(observed)
    observationId <- writes.run(ids.nextId)
    observedAt <- writes.run(time.now)
    observation = makeObservation(observationId,actor.organizationId,resourceId,target,assignment,observed,observedAt,None)
    stored <- writes.run(for {
      _ <- repository.lockResource(actor.organizationId,resourceId)
      active <- repository.hasActiveRun(actor.organizationId,resourceId)
      _ <- MonadThrow[Tx].raiseWhen(active)(alreadyActive)
      currentGeneration <- repository.executionGeneration(actor.organizationId,resourceId)
      _ <- MonadThrow[Tx].raiseUnless(currentGeneration == generation)(ProvisioningError.ProfilePlanChanged)
      unchanged <- targets.unchanged(ProvisioningInputSnapshot(1,ProvisioningRunKind.ServerBaselineCheck,actor.organizationId,
        resourceId,target.resourceType,target.resourceKind,target.connectionId,target.connectionUpdatedAt,ProvisioningStepKind.Baseline))
      _ <- MonadThrow[Tx].raiseUnless(unchanged)(sourceChanged)
      current <- repository.assignment(actor.organizationId,resourceId)
      _ <- MonadThrow[Tx].raiseUnless(sameAssignment(current,assignment))(ProvisioningError.ProfilePlanChanged)
      _ <- repository.saveObservation(observation)
    } yield observation)
  } yield stored

  /** The review snapshot, latest sanitized observation and PLANNED row commit under one resource lock. */
  def preview(actor: ActorContext, resourceId: UUID): F[ServerProfilePlan] = for {
    _ <- ensureEnabled
    initial <- prepareRead(actor.organizationId,resourceId)
    target = initial._1
    assignment = initial._2
    generation = initial._3
    revision = initial._4
    pinned <- (assignment,revision) match {
      case (Some(a),Some(r)) => (a,r).pure[F]
      case _ => MonadThrow[F].raiseError[(ServerProfileAssignment,ServerProfileRevision)](invalid)
    }
    a = pinned._1
    r = pinned._2
    observed <- remote.observe(target.connection,resourceId,Some(r.content))
    _ <- MonadThrow[F].raiseWhen(observed.failureCode.nonEmpty)(remoteError(observed.failureCode.getOrElse("PROVISIONING_REMOTE_UNAVAILABLE")))
    _ <- validateObservation(observed)
    observationId <- writes.run(ids.nextId)
    observedAt <- writes.run(time.now)
    observation = makeObservation(observationId,actor.organizationId,resourceId,target,Some(a),observed,observedAt,None)
    assessment = ServerProfileDiff.assess(r.content,observed.content)
    blocks = observed.blockingProblems.distinct
    diffHash = ServerProfileDiff.reviewedHash(assessment)
    plan <- writes.run(for {
      _ <- repository.lockResource(actor.organizationId,resourceId)
      active <- repository.hasActiveRun(actor.organizationId,resourceId)
      _ <- MonadThrow[Tx].raiseWhen(active)(alreadyActive)
      currentGeneration <- repository.executionGeneration(actor.organizationId,resourceId)
      _ <- MonadThrow[Tx].raiseUnless(currentGeneration == generation)(ProvisioningError.ProfilePlanChanged)
      current <- repository.assignment(actor.organizationId,resourceId)
      _ <- MonadThrow[Tx].raiseUnless(sameAssignment(current,Some(a)))(ProvisioningError.ProfilePlanChanged)
      currentRevision <- repository.revision(actor.organizationId,a.profileId,a.revisionNumber)
      _ <- MonadThrow[Tx].raiseUnless(currentRevision.exists(_.contentHash == r.contentHash))(ProvisioningError.ProfilePlanChanged)
      unchanged <- targets.unchanged(ProvisioningInputSnapshot(1,ProvisioningRunKind.ServerBaselineCheck,actor.organizationId,
        resourceId,target.resourceType,target.resourceKind,target.connectionId,target.connectionUpdatedAt,ProvisioningStepKind.Baseline))
      _ <- MonadThrow[Tx].raiseUnless(unchanged)(sourceChanged)
      _ <- repository.saveObservation(observation)
      id <- ids.nextId; now <- time.now
      snapshot = ServerProfileApplySnapshot(a.id,a.version,a.profileId,r.id,r.number,r.contentHash,r.content,
        observation.id,observation.contentHash,diffHash,blocks)
      input = ProvisioningInputSnapshot(1,ProvisioningRunKind.ServerProfileApply,actor.organizationId,resourceId,target.resourceType,
        target.resourceKind,target.connectionId,target.connectionUpdatedAt,ProfileSteps,Some(snapshot))
      run = ProvisioningRun(id,actor.organizationId,resourceId,None,None,input,ProvisioningRunState.Planned,now,now)
      _ <- runs.insertPlan(run)
      profile <- repository.profile(actor.organizationId,a.profileId).flatMap(_.liftTo[Tx](notFound))
    } yield ServerProfilePlan(run,target.connection.name,assessment,observed.warnings,blocks,profile.name,
      ServerProfileDiff.requiredPackages(r.content,observed.content)))
  } yield plan

  def automation(organizationId: UUID, resourceId: UUID): F[ServerProfileAutomation] = for {
    stateData <- reads.run(for {
      blocked <- repository.hasActiveRun(organizationId,resourceId)
      assignment <- repository.assignment(organizationId,resourceId)
      profile <- assignment.traverse(a => repository.profile(organizationId,a.profileId)).map(_.flatten)
      revision <- assignment.traverse(a => repository.revision(organizationId,a.profileId,a.revisionNumber)).map(_.flatten)
      stored <- repository.observation(organizationId,resourceId)
      target <- targets.eligible(organizationId,resourceId)
      latest <- (for { a <- assignment; t <- target.toOption } yield runs.latestProfileApply(organizationId,resourceId,
        a.id,a.version,a.revisionId,t.connectionId,t.connectionUpdatedAt)).getOrElse(Option.empty[ProvisioningRun].pure[Tx])
    } yield (blocked,assignment,profile,revision,stored,target,latest))
    (blocked,assignment,profile,revision,stored,target,latestRun) = stateData
    sourceOk = target.exists(t => stored.exists(o => o.sourceConnectionId == t.connectionId && o.sourceUpdatedAt == t.connectionUpdatedAt))
    observation = stored.filter(o => sourceOk && assignment.exists(a => o.assignmentId.contains(a.id) &&
      o.assignmentVersion.contains(a.version) && o.revisionId.contains(a.revisionId)))
    assessment = for { r <- revision; o <- observation } yield ServerProfileDiff.assess(r.content,o.content)
    activeRun = latestRun.filterNot(_.state.terminal)
    linkedToLatest = latestRun.exists(r => observation.exists(_.verifiedRunId.contains(r.id)))
    laterManualObservation = latestRun.exists(r => observation.exists(o => o.verifiedRunId.isEmpty &&
      r.finishedAt.exists(f => o.observedAt.isAfter(f))))
    state = ServerProfileAutomationState.assess(assignment.nonEmpty && profile.nonEmpty && revision.nonEmpty,
      observation.nonEmpty, assessment.exists(_.compliant), latestRun.map(_.state), linkedToLatest, laterManualObservation)
  } yield ServerProfileAutomation(assignment,profile,revision,observation,state,assessment,activeRun,blocked)

  override def valid(organizationId: UUID, resourceId: UUID, snapshot: ServerProfileApplySnapshot): Tx[Boolean] = for {
    current <- repository.assignment(organizationId,resourceId)
    observation <- repository.observation(organizationId,resourceId)
    revision <- repository.revision(organizationId,snapshot.profileId,snapshot.revisionNumber)
    valid = snapshot.blockingProblems.isEmpty && snapshot.content.hash == snapshot.revisionHash &&
      current.exists(a => a.id == snapshot.assignmentId && a.version == snapshot.assignmentVersion &&
        a.profileId == snapshot.profileId && a.revisionId == snapshot.revisionId && a.revisionNumber == snapshot.revisionNumber) &&
      revision.exists(r => r.id == snapshot.revisionId && r.contentHash == snapshot.revisionHash && r.content.hash == snapshot.revisionHash) &&
      observation.exists(o => o.id == snapshot.observationId && o.contentHash == snapshot.observationHash &&
        o.assignmentId.contains(snapshot.assignmentId) && o.assignmentVersion.contains(snapshot.assignmentVersion) &&
        o.revisionId.contains(snapshot.revisionId) && ServerProfileDiff.reviewedHash(ServerProfileDiff.assess(snapshot.content,o.content)) == snapshot.reviewedDiffHash)
  } yield valid

  private def prepareRead(org: UUID, resourceId: UUID): F[(ProvisioningTarget,Option[ServerProfileAssignment],Long,Option[ServerProfileRevision])] = writes.run(for {
    _ <- repository.lockResource(org,resourceId)
    active <- repository.hasActiveRun(org,resourceId)
    _ <- MonadThrow[Tx].raiseWhen(active)(alreadyActive)
    targetResult <- targets.eligible(org,resourceId)
    target <- targetResult.leftMap(targetError).liftTo[Tx]
    assignment <- repository.assignment(org,resourceId)
    revision <- assignment.traverse(a => repository.revision(org,a.profileId,a.revisionNumber)).map(_.flatten)
    generation <- repository.executionGeneration(org,resourceId)
    _ <- MonadThrow[Tx].raiseUnless(assignment.isEmpty || revision.nonEmpty)(revisionNotFound)
  } yield (target,assignment,generation,revision))

  private def makeObservation(id: UUID,org: UUID,resource: UUID,target: ProvisioningTarget,assignment: Option[ServerProfileAssignment],
    remote: ServerProfileRemoteObservation,at: Instant,verifiedRun: Option[UUID]): ServerProfileObservation =
    ServerProfileObservation(id,org,resource,target.connectionId,target.connectionUpdatedAt,assignment.map(_.id),
      assignment.map(_.version),assignment.map(_.revisionId),remote.content,ServerProfileDiff.hashObservation(remote.content),at,verifiedRun)

  private def validateObservation(remote: ServerProfileRemoteObservation): F[Unit] =
    MonadThrow[F].raiseUnless(ServerProfileObservationCodec.validate(remote.content).isRight &&
      ServerProfileDiff.hashObservation(remote.content) == remote.contentHash)(remoteError("PROVISIONING_OBSERVATION_INVALID"))

  private def sameAssignment(left: Option[ServerProfileAssignment], right: Option[ServerProfileAssignment]): Boolean =
    left.map(a => (a.id,a.version,a.revisionId,a.profileId)) == right.map(a => (a.id,a.version,a.revisionId,a.profileId))
  private def ensureEnabled: F[Unit] = MonadThrow[F].raiseUnless(settings.enabled)(disabled)
  private def targetError(code: String): Throwable = code match {
    case "PROVISIONING_TARGET_AMBIGUOUS" => targetAmbiguous
    case "PROVISIONING_TARGET_NOT_FOUND" => targetNotFound
    case _ => ProvisioningError.UnsupportedTarget
  }
  private def remoteError(code: String): Throwable = ServerProfileError(code,"The server observation could not be completed safely")
}
