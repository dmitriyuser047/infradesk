package ru.bitec.app.ops
package application.port

import domain.provisioning._
import io.circe.Json
import java.time.Instant
import java.util.UUID

final case class ServerProfileSummary(profile: ServerProfile, assignments: Int)
final case class ServerProfileAssignmentView(assignment: ServerProfileAssignment, resourceName: String, resourceActive: Boolean)
final case class ServerProfileRemoteObservation(content: Json, contentHash: String,
  failureCode: Option[String] = None, warnings: List[String] = Nil, blockingProblems: List[String] = Nil)
final case class ProfileExecutionContext(runId: UUID, priorSteps: List[ProvisioningStep], reviewedObservation: Json) {
  def installFacts: Option[Map[String,String]] = priorSteps.find(s =>
    s.kind == ProvisioningStepKind.InstallPackages && s.state == ProvisioningStepState.Succeeded).map(_.facts)
}
trait ServerProfileRemote[F[_]] {
  def observe(connection: domain.connection.Connection, resourceId: UUID,
    desired: Option[ServerProfileContent]): F[ServerProfileRemoteObservation]
  def applyModule(connection: domain.connection.Connection, resourceId: UUID,
    snapshot: ServerProfileApplySnapshot, kind: ProvisioningStepKind,
    context: ProfileExecutionContext): F[ProvisioningStepResult]
}

trait ServerProfileRepository[F[_]] {
  def insertProfile(profile: ServerProfile): F[Boolean]
  def profileForUpdate(org: UUID, id: UUID): F[Option[ServerProfile]]
  def profile(org: UUID, id: UUID): F[Option[ServerProfile]]
  def profiles(org: UUID, archived: Boolean, limit: Int): F[List[ServerProfileSummary]]
  def insertRevision(revision: ServerProfileRevision): F[Unit]
  def updateProfile(profile: ServerProfile): F[Unit]
  def revision(org: UUID, profileId: UUID, number: Int): F[Option[ServerProfileRevision]]
  def revisions(org: UUID, profileId: UUID): F[List[ServerProfileRevision]]
  def lockResource(org: UUID, resourceId: UUID): F[Unit]
  def assignableNode(org: UUID, resourceId: UUID): F[Boolean]
  def hasActiveRun(org: UUID, resourceId: UUID): F[Boolean]
  def executionGeneration(org: UUID, resourceId: UUID): F[Long]
  def assignment(org: UUID, resourceId: UUID): F[Option[ServerProfileAssignment]]
  def assignmentsForProfile(org: UUID, profileId: UUID): F[List[ServerProfileAssignmentView]]
  def storeAssignment(assignment: ServerProfileAssignment): F[Unit]
  def removeAssignment(org: UUID, resourceId: UUID): F[Option[ServerProfileAssignment]]
  def saveObservation(observation: ServerProfileObservation): F[Unit]
  def observation(org: UUID, resourceId: UUID): F[Option[ServerProfileObservation]]
}
