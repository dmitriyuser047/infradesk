package ru.bitec.app.ops
package domain.integration

import cats.syntax.all._
import domain.provisioning.ServerProfileDiff
import io.circe.Json
import java.time.Instant
import java.util.UUID

sealed abstract class FleetRolloutState(val code: String) {
  def active: Boolean = FleetRolloutState.Active.contains(this)
  def terminal: Boolean = FleetRolloutState.Terminal.contains(this)
}
object FleetRolloutState {
  case object Planned extends FleetRolloutState("PLANNED")
  case object Queued extends FleetRolloutState("QUEUED")
  case object Running extends FleetRolloutState("RUNNING")
  case object Paused extends FleetRolloutState("PAUSED")
  case object Succeeded extends FleetRolloutState("SUCCEEDED")
  case object Failed extends FleetRolloutState("FAILED")
  case object Unknown extends FleetRolloutState("UNKNOWN")
  case object RollingBack extends FleetRolloutState("ROLLING_BACK")
  case object RolledBack extends FleetRolloutState("ROLLED_BACK")
  val All: List[FleetRolloutState] =
    List(Planned, Queued, Running, Paused, Succeeded, Failed, Unknown, RollingBack, RolledBack)
  val Active: Set[FleetRolloutState] = Set(Queued, Running, Paused, RollingBack)
  val Terminal: Set[FleetRolloutState] = Set(Succeeded, Failed, Unknown, RolledBack)
  def fromCode(code: String): Option[FleetRolloutState] = All.find(_.code == code)
}

sealed abstract class FleetRolloutPhase(val code: String)
object FleetRolloutPhase {
  case object Validate extends FleetRolloutPhase("VALIDATE")
  case object PrepareSharedConfig extends FleetRolloutPhase("PREPARE_SHARED_CONFIG")
  case object ApplySharedConfig extends FleetRolloutPhase("APPLY_SHARED_CONFIG")
  case object VerifySharedConfig extends FleetRolloutPhase("VERIFY_SHARED_CONFIG")
  case object PrepareCanary extends FleetRolloutPhase("PREPARE_CANARY")
  case object ApplyCanary extends FleetRolloutPhase("APPLY_CANARY")
  case object VerifyCanary extends FleetRolloutPhase("VERIFY_CANARY")
  case object ApplyWaves extends FleetRolloutPhase("APPLY_WAVES")
  case object VerifyWave extends FleetRolloutPhase("VERIFY_WAVE")
  case object FinalVerify extends FleetRolloutPhase("FINAL_VERIFY")
  case object Rollback extends FleetRolloutPhase("ROLLBACK")
  case object Complete extends FleetRolloutPhase("COMPLETE")
  val All: List[FleetRolloutPhase] = List(Validate, PrepareSharedConfig, ApplySharedConfig, VerifySharedConfig,
    PrepareCanary, ApplyCanary, VerifyCanary, ApplyWaves, VerifyWave, FinalVerify, Rollback, Complete)
  def fromCode(code: String): Option[FleetRolloutPhase] = All.find(_.code == code)
}

sealed abstract class FleetRollbackScope(val code: String)
object FleetRollbackScope {
  case object CurrentWave extends FleetRollbackScope("CURRENT_WAVE")
  case object AllCompleted extends FleetRollbackScope("ALL_COMPLETED")
  val All: List[FleetRollbackScope] = List(CurrentWave, AllCompleted)
  def fromCode(code: String): Option[FleetRollbackScope] = All.find(_.code == code)
}

sealed abstract class FleetRolloutMemberState(val code: String)
object FleetRolloutMemberState {
  case object Pending extends FleetRolloutMemberState("PENDING")
  case object Running extends FleetRolloutMemberState("RUNNING")
  case object Succeeded extends FleetRolloutMemberState("SUCCEEDED")
  case object Failed extends FleetRolloutMemberState("FAILED")
  case object Unknown extends FleetRolloutMemberState("UNKNOWN")
  case object Skipped extends FleetRolloutMemberState("SKIPPED")
  case object RollingBack extends FleetRolloutMemberState("ROLLING_BACK")
  case object RolledBack extends FleetRolloutMemberState("ROLLED_BACK")
  val All: List[FleetRolloutMemberState] =
    List(Pending, Running, Succeeded, Failed, Unknown, Skipped, RollingBack, RolledBack)
  def fromCode(code: String): Option[FleetRolloutMemberState] = All.find(_.code == code)
}

sealed abstract class FleetActionKind(val code: String)
object FleetActionKind {
  case object ServerProfileAssign extends FleetActionKind("SERVER_PROFILE_ASSIGN")
  case object ServerProfileApply extends FleetActionKind("SERVER_PROFILE_APPLY")
  case object NetworkFirewall extends FleetActionKind("NETWORK_FIREWALL")
  case object NodePort extends FleetActionKind("NODE_PORT")
  case object DesiredState extends FleetActionKind("DESIRED_STATE")
  case object Verify extends FleetActionKind("VERIFY")
  case object ConfigRollout extends FleetActionKind("CONFIG_ROLLOUT")
  val All: List[FleetActionKind] = List(ServerProfileAssign, ServerProfileApply, NetworkFirewall, NodePort,
    DesiredState, Verify, ConfigRollout)
  def fromCode(code: String): Option[FleetActionKind] = All.find(_.code == code)
}

sealed abstract class FleetActionState(val code: String)
object FleetActionState {
  case object Pending extends FleetActionState("PENDING")
  case object Running extends FleetActionState("RUNNING")
  case object Succeeded extends FleetActionState("SUCCEEDED")
  case object Failed extends FleetActionState("FAILED")
  case object Unknown extends FleetActionState("UNKNOWN")
  case object Skipped extends FleetActionState("SKIPPED")
  val All: List[FleetActionState] = List(Pending, Running, Succeeded, Failed, Unknown, Skipped)
  def fromCode(code: String): Option[FleetActionState] = All.find(_.code == code)
}

/** What was true of a member before the rollout touched it. Compensation reads only this. */
final case class FleetMemberBaseline(assignmentProfileId: Option[UUID], assignmentRevisionNumber: Option[Int],
  desiredState: Option[IntegrationDesiredNodeState], compliance: String, health: String,
  observedDisabled: Boolean = false, sourceConnectionId: Option[UUID] = None, sourceUpdatedAt: Option[Instant] = None)

final case class FleetRolloutMemberPlan(membershipId: UUID, membershipVersion: Long, inventoryNodeId: UUID,
  resourceId: UUID, externalNodeId: String, nodeName: String, wave: Int, position: Int,
  skipReason: Option[String], actions: List[FleetActionKind], baseline: FleetMemberBaseline) {
  def mutable: Boolean = skipReason.isEmpty
}

final case class FleetRolloutPolicy(waveSize: Int, canaryMembershipIds: List[UUID], pauseAfterCanary: Boolean,
  automaticRollback: Boolean, rollbackScope: FleetRollbackScope)

/** The shared Remnawave Config deployment. It is applied once, never per node. */
final case class FleetRolloutConfigConsumer(inventoryNodeId: UUID, externalNodeId: String, nodeName: String,
  disabled: Boolean, connected: Boolean, observedAt: Instant)

final case class FleetRolloutSharedConfig(required: Boolean, inventoryConfigProfileId: UUID, revisionNumber: Int,
  revisionHash: String, baselineRevisionNumber: Option[Int], baselineHash: Option[String],
  externalNodes: Int, externalUnhealthyNodes: Int, consumers: List[FleetRolloutConfigConsumer] = Nil)

/** The immutable preview. It names exact revisions and hashes and carries no secret, no script and no
  * configuration body.
  */
final case class FleetRolloutSnapshot(fleetId: UUID, integrationId: UUID, integrationPin: Instant,
  revisionId: UUID, revisionNumber: Int, revisionHash: String, content: FleetDesiredContent,
  policy: FleetRolloutPolicy, shared: FleetRolloutSharedConfig, members: List[FleetRolloutMemberPlan]) {
  def waveCount: Int = members.filter(_.mutable).map(_.wave).distinct.size
  def json: Json = FleetRolloutSnapshotCodec.encode(this)
  def hash: String = ServerProfileDiff.hashText(json.noSpaces)
}
