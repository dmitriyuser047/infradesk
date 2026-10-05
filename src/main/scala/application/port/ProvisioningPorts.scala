package ru.bitec.app.ops
package application.port

import domain.provisioning._
import java.time.Instant
import java.util.UUID

trait ProvisioningRunRepository[F[_]] {
  def insertPlan(run: ProvisioningRun): F[Unit]
  /** Serializes approval with cleanup and any resource-scoped desired-state change. */
  def lockApproval(organizationId: UUID, planId: UUID, requestId: UUID): F[Option[ProvisioningRun]]
  def start(organizationId: UUID, planId: UUID, requestId: UUID, actorId: UUID,
    now: Instant): F[Option[(ProvisioningRun, Boolean)]]
  def deleteExpiredPlans(before: Instant, limit: Int, scope: Option[UUID] = None): F[Int]
  def find(organizationId: UUID, id: UUID): F[Option[(ProvisioningRun, List[ProvisioningStep])]]
  def findRequest(organizationId: UUID, requestId: UUID): F[Option[ProvisioningRun]]
  def history(organizationId: UUID, resourceId: Option[UUID], limit: Int): F[List[ProvisioningRun]]
  /** Latest profile apply for the exact current pin and SSH source; readiness runs cannot mask it. */
  def latestProfileApply(organizationId: UUID, resourceId: UUID, assignmentId: UUID,
    assignmentVersion: Long, revisionId: UUID, connectionId: UUID, connectionUpdatedAt: Instant): F[Option[ProvisioningRun]]
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int,
    scope: Option[UUID] = None): F[List[ProvisioningRun]]
  def renew(organizationId: UUID, id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]
  def beginStep(organizationId: UUID, id: UUID, token: UUID, kind: ProvisioningStepKind,
    now: Instant): F[Boolean]
  def finishStep(organizationId: UUID, id: UUID, token: UUID, kind: ProvisioningStepKind,
    state: ProvisioningStepState, facts: Map[String, String], failureCode: Option[String],
    outputTruncated: Boolean, now: Instant, verificationResult: Option[Boolean] = None): F[Boolean]
  def skipPending(organizationId: UUID, id: UUID, token: UUID, now: Instant): F[Boolean]
  def finish(organizationId: UUID, id: UUID, token: UUID, state: ProvisioningRunState,
    failureCode: Option[String], now: Instant): F[Boolean]
}

final case class ProvisioningTarget(resourceType: String, resourceKind: String, connectionId: UUID,
  connectionUpdatedAt: Instant, connection: domain.connection.Connection)
trait ProvisioningTargetQuery[F[_]] {
  def eligibleBatch(organizationId: UUID, resourceIds: List[UUID]): F[Map[UUID, Either[String, ProvisioningTarget]]]
  def eligible(organizationId: UUID, resourceId: UUID): F[Either[String, ProvisioningTarget]]
  def unchanged(snapshot: ProvisioningInputSnapshot): F[Boolean]
}

trait ProvisioningTransport[F[_]] {
  def preflight(connection: domain.connection.Connection): F[ProvisioningStepResult]
  def verify(connection: domain.connection.Connection): F[ProvisioningStepResult]
}
final case class ProvisioningStepResult(facts: Map[String, String], failureCode: Option[String],
  verificationResult: Option[Boolean], uncertain: Boolean = false, outputTruncated: Boolean = false,
  skipped: Boolean = false, profileObservation: Option[ServerProfileRemoteObservation] = None)

/** Validates pinned assignment/source state at every remote boundary and runs only a typed fixed step. */
trait ProvisioningProfileApplyHandler[F[_], Tx[_]] {
  def validateBoundary(run: ProvisioningRun, connection: domain.connection.Connection,
    kind: ProvisioningStepKind): F[Option[ProvisioningStepResult]]
  def execute(run: ProvisioningRun, connection: domain.connection.Connection,
    kind: ProvisioningStepKind): F[ProvisioningStepResult]
  /** Persists VERIFY step and its linked observation under resource lock then lease fence. */
  def recordVerification(run: ProvisioningRun, token: UUID, connection: domain.connection.Connection,
    result: ProvisioningStepResult, state: ProvisioningStepState, failureCode: Option[String],
    terminalState: Option[ProvisioningRunState], now: Instant): Tx[Boolean]
}

trait ProvisioningApplyPlanValidator[F[_]] {
  /** Recheck assignment id/version, pinned revision and latest observation under the approval lock. */
  def valid(organizationId: UUID, resourceId: UUID, snapshot: ServerProfileApplySnapshot): F[Boolean]
}
