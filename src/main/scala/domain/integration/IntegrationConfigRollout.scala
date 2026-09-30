package ru.bitec.app.ops
package domain.integration

import java.time.Instant
import java.util.UUID

sealed trait IntegrationConfigDeploymentSource { def code: String }
object IntegrationConfigDeploymentSource {
  case object Manual extends IntegrationConfigDeploymentSource { val code = "MANUAL" }
  case object RolloutTarget extends IntegrationConfigDeploymentSource { val code = "ROLLOUT_TARGET" }
  case object RolloutRollback extends IntegrationConfigDeploymentSource { val code = "ROLLOUT_ROLLBACK" }
  val All = List(Manual, RolloutTarget, RolloutRollback)
  def fromCode(value: String): Either[IllegalArgumentException, IntegrationConfigDeploymentSource] =
    All.find(_.code == value).toRight(new IllegalArgumentException("Unknown integration config deployment source"))
}

sealed trait IntegrationConfigRolloutStatus { def code: String; def terminal: Boolean }
object IntegrationConfigRolloutStatus {
  case object Preparing extends IntegrationConfigRolloutStatus { val code = "PREPARING"; val terminal = false }
  case object Applying extends IntegrationConfigRolloutStatus { val code = "APPLYING"; val terminal = false }
  case object Verifying extends IntegrationConfigRolloutStatus { val code = "VERIFYING"; val terminal = false }
  case object RollbackApplying extends IntegrationConfigRolloutStatus { val code = "ROLLBACK_APPLYING"; val terminal = false }
  case object RollbackVerifying extends IntegrationConfigRolloutStatus { val code = "ROLLBACK_VERIFYING"; val terminal = false }
  case object Succeeded extends IntegrationConfigRolloutStatus { val code = "SUCCEEDED"; val terminal = true }
  case object RolledBack extends IntegrationConfigRolloutStatus { val code = "ROLLED_BACK"; val terminal = true }
  case object Failed extends IntegrationConfigRolloutStatus { val code = "FAILED"; val terminal = true }
  case object Unknown extends IntegrationConfigRolloutStatus { val code = "UNKNOWN"; val terminal = true }
  case object Cancelled extends IntegrationConfigRolloutStatus { val code = "CANCELLED"; val terminal = true }
  val All = List(Preparing, Applying, Verifying, RollbackApplying, RollbackVerifying,
    Succeeded, RolledBack, Failed, Unknown, Cancelled)
  def fromCode(value: String): Either[IllegalArgumentException, IntegrationConfigRolloutStatus] =
    All.find(_.code == value).toRight(new IllegalArgumentException("Unknown integration config rollout status"))
}

final case class IntegrationConfigRollout(id: UUID, organizationId: UUID, integrationId: UUID,
  inventoryObjectId: UUID, bindingId: UUID, configurationProfileId: UUID,
  baselineRevisionId: Option[UUID], baselineRevisionNumber: Option[Int], baselineSha256: Option[String],
  targetRevisionId: UUID, targetRevisionNumber: Int, targetSha256: String,
  requestId: UUID, requestedByUserId: UUID, automaticRollback: Boolean,
  status: IntegrationConfigRolloutStatus, baselineSyncSessionId: Option[UUID] = None,
  targetVerificationSyncSessionId: Option[UUID] = None,
  rollbackVerificationSyncSessionId: Option[UUID] = None,
  targetDeploymentId: Option[UUID] = None, rollbackDeploymentId: Option[UUID] = None,
  verificationDeadlineAt: Option[Instant] = None, claimedBy: Option[UUID] = None,
  claimToken: Option[UUID] = None, claimUntil: Option[Instant] = None,
  errorCode: Option[String] = None, errorMessage: Option[String] = None,
  createdAt: Instant, startedAt: Option[Instant] = None, finishedAt: Option[Instant] = None,
  updatedAt: Instant, affectedNodeCount: Int = 0, baselineHealthyNodeCount: Int = 0,
  preexistingUnhealthyNodeCount: Int = 0)

final case class IntegrationConfigRolloutPreview(baselineRevisionNumber: Int, targetRevisionNumber: Int,
  baselineSha256: String, targetSha256: String, affectedNodes: Int, healthyNodes: Int,
  preexistingUnhealthyNodes: Int, automaticRollbackSupported: Boolean = true,
  nodeCanarySupported: Boolean = false)

final case class IntegrationConfigRolloutNodeHealth(externalId: String, displayName: String,
  before: String, after: Option[String], result: String)

final case class IntegrationConfigRolloutInspection(sessionId: UUID, observedSha256: Option[String],
  affectedNodes: Int, healthyNodes: Int, preexistingUnhealthyNodes: Int,
  regressionNodes: Int, planDrift: Boolean, nodes: List[IntegrationConfigRolloutNodeHealth])
