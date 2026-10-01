package ru.bitec.app.ops
package application.port

import domain.provisioning._
import java.time.Instant
import java.util.UUID

trait ProvisioningRunRepository[F[_]] {
  def insertPlan(run: ProvisioningRun): F[Unit]
  def start(organizationId: UUID, planId: UUID, requestId: UUID, actorId: UUID,
    now: Instant): F[Option[(ProvisioningRun, Boolean)]]
  def find(organizationId: UUID, id: UUID): F[Option[(ProvisioningRun, List[ProvisioningStep])]]
  def findRequest(organizationId: UUID, requestId: UUID): F[Option[ProvisioningRun]]
  def history(organizationId: UUID, resourceId: Option[UUID], limit: Int): F[List[ProvisioningRun]]
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int,
    scope: Option[UUID] = None): F[List[ProvisioningRun]]
  def renew(organizationId: UUID, id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]
  def beginStep(organizationId: UUID, id: UUID, token: UUID, kind: ProvisioningStepKind,
    now: Instant): F[Boolean]
  def finishStep(organizationId: UUID, id: UUID, token: UUID, kind: ProvisioningStepKind,
    state: ProvisioningStepState, facts: Map[String, String], failureCode: Option[String],
    outputTruncated: Boolean, now: Instant): F[Boolean]
  def skipPending(organizationId: UUID, id: UUID, token: UUID, now: Instant): F[Boolean]
  def finish(organizationId: UUID, id: UUID, token: UUID, state: ProvisioningRunState,
    failureCode: Option[String], now: Instant): F[Boolean]
}

final case class ProvisioningTarget(resourceType: String, resourceKind: String, connectionId: UUID,
  connectionUpdatedAt: Instant, connection: domain.connection.Connection)
trait ProvisioningTargetQuery[F[_]] {
  def eligible(organizationId: UUID, resourceId: UUID): F[Either[String, ProvisioningTarget]]
  def unchanged(snapshot: ProvisioningInputSnapshot): F[Boolean]
}

trait ProvisioningTransport[F[_]] {
  def preflight(connection: domain.connection.Connection): F[ProvisioningStepResult]
  def verify(connection: domain.connection.Connection): F[ProvisioningStepResult]
}
final case class ProvisioningStepResult(facts: Map[String, String], failureCode: Option[String],
  verificationResult: Option[Boolean], uncertain: Boolean = false, outputTruncated: Boolean = false)
