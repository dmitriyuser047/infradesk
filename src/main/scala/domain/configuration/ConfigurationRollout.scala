package ru.bitec.app.ops
package domain.configuration

import java.time.Instant
import java.util.UUID

sealed trait ConfigurationRolloutState { def code: String; def terminal: Boolean }
object ConfigurationRolloutState {
  case object Queued extends ConfigurationRolloutState { val code = "QUEUED"; val terminal = false }
  case object Running extends ConfigurationRolloutState { val code = "RUNNING"; val terminal = false }
  case object Paused extends ConfigurationRolloutState { val code = "PAUSED"; val terminal = false }
  case object Failed extends ConfigurationRolloutState { val code = "FAILED"; val terminal = true }
  case object RollingBack extends ConfigurationRolloutState { val code = "ROLLING_BACK"; val terminal = false }
  case object RolledBack extends ConfigurationRolloutState { val code = "ROLLED_BACK"; val terminal = true }
  case object Succeeded extends ConfigurationRolloutState { val code = "SUCCEEDED"; val terminal = true }
  case object Cancelled extends ConfigurationRolloutState { val code = "CANCELLED"; val terminal = true }
  val all: List[ConfigurationRolloutState] =
    List(Queued, Running, Paused, Failed, RollingBack, RolledBack, Succeeded, Cancelled)
  def fromCode(code: String): ConfigurationRolloutState =
    all.find(_.code == code).getOrElse(throw new IllegalArgumentException("Invalid rollout state"))
}

sealed trait ConfigurationRolloutItemState { def code: String; def terminal: Boolean }
object ConfigurationRolloutItemState {
  case object Pending extends ConfigurationRolloutItemState { val code = "PENDING"; val terminal = false }
  case object Deploying extends ConfigurationRolloutItemState { val code = "DEPLOYING"; val terminal = false }
  case object Succeeded extends ConfigurationRolloutItemState { val code = "SUCCEEDED"; val terminal = true }
  case object Failed extends ConfigurationRolloutItemState { val code = "FAILED"; val terminal = true }
  case object RolledBack extends ConfigurationRolloutItemState { val code = "ROLLED_BACK"; val terminal = true }
  case object Skipped extends ConfigurationRolloutItemState { val code = "SKIPPED"; val terminal = true }
  val all: List[ConfigurationRolloutItemState] = List(Pending, Deploying, Succeeded, Failed, RolledBack, Skipped)
  def fromCode(code: String): ConfigurationRolloutItemState =
    all.find(_.code == code).getOrElse(throw new IllegalArgumentException("Invalid rollout item state"))
}

sealed trait ConfigurationRollbackMode { def code: String }
object ConfigurationRollbackMode {
  case object FailedTargetOnly extends ConfigurationRollbackMode { val code = "FAILED_TARGET_ONLY" }
  case object AllApplied extends ConfigurationRollbackMode { val code = "ALL_APPLIED" }
  val all: List[ConfigurationRollbackMode] = List(FailedTargetOnly, AllApplied)
  def fromCode(code: String): ConfigurationRollbackMode =
    all.find(_.code == code).getOrElse(throw new IllegalArgumentException("Invalid rollback mode"))
}

final case class ConfigurationRolloutStrategy(canaryCount: Int, batchSize: Int, pauseSeconds: Int,
                                               stopOnFailure: Boolean, rollbackMode: ConfigurationRollbackMode)
object ConfigurationRolloutStrategy {
  val Default: ConfigurationRolloutStrategy =
    ConfigurationRolloutStrategy(1, 1, 0, stopOnFailure = true, ConfigurationRollbackMode.FailedTargetOnly)
  def valid(value: ConfigurationRolloutStrategy): Boolean =
    value.canaryCount >= 0 && value.canaryCount <= 20 &&
      value.batchSize >= 1 && value.batchSize <= 20 && value.pauseSeconds >= 0 && value.pauseSeconds <= 3600
}

final case class ConfigurationRollout(
  id: UUID, organizationId: UUID, requestId: UUID, profileId: UUID, profileRevisionNumber: Int,
  state: ConfigurationRolloutState, strategy: ConfigurationRolloutStrategy,
  leaseOwner: Option[UUID], leaseToken: Option[UUID], leaseExpiresAt: Option[Instant],
  cancelRequested: Boolean, actorUserId: UUID, createdAt: Instant,
  startedAt: Option[Instant], finishedAt: Option[Instant], nextActionAt: Option[Instant],
  lastPausedPosition: Int
)

final case class ConfigurationRolloutItem(
  id: UUID, organizationId: UUID, rolloutId: UUID, position: Int, assignmentId: UUID,
  assignmentVersion: Int, resourceId: UUID, targetPath: String,
  connectionId: UUID, connectionUpdatedAt: Instant,
  desiredSha256: String, expectedRemoteState: ExpectedRemoteState,
  policy: ConfigurationExecutionPolicy, values: List[ConfigurationVariableValue],
  state: ConfigurationRolloutItemState, deploymentId: Option[UUID],
  createdAt: Instant, updatedAt: Instant
)
