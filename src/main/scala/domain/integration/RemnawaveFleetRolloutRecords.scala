package ru.bitec.app.ops
package domain.integration

import io.circe.Json
import java.time.Instant
import java.util.UUID

final case class RemnawaveFleetRollout(id: UUID, organizationId: UUID, integrationId: UUID, fleetId: UUID,
  fleetRevisionId: UUID, requestId: Option[UUID], state: FleetRolloutState, phase: FleetRolloutPhase,
  snapshot: FleetRolloutSnapshot, snapshotHash: String, currentWave: Int, waveCount: Int,
  pauseAfterCanary: Boolean, automaticRollback: Boolean, rollbackScope: FleetRollbackScope, createdBy: UUID,
  createdAt: Instant, expiresAt: Instant, startedAt: Option[Instant], finishedAt: Option[Instant],
  failureCode: Option[String], safeMessage: Option[String], rollbackIncomplete: Boolean,
  pauseReason: Option[String], pauseRequestedAt: Option[Instant], pausedAt: Option[Instant],
  rollbackRequestedAt: Option[Instant], rollbackRequestedBy: Option[UUID],
  rollbackRequestedScope: Option[FleetRollbackScope], nextRunAt: Instant, phaseStartedAt: Instant,
  claimToken: Option[UUID], version: Long, updatedAt: Instant)

final case class RemnawaveFleetRolloutMember(id: UUID, organizationId: UUID, rolloutId: UUID, fleetId: UUID,
  membershipId: UUID, membershipVersion: Long, inventoryNodeId: UUID, resourceId: UUID, externalNodeId: String,
  wave: Int, position: Int, state: FleetRolloutMemberState, skipReason: Option[String],
  plannedActions: List[FleetActionKind], failureCode: Option[String], safeMessage: Option[String],
  rollbackFailureCode: Option[String], startedAt: Option[Instant], finishedAt: Option[Instant],
  version: Long, updatedAt: Instant)

final case class RemnawaveFleetRolloutAction(id: UUID, organizationId: UUID, rolloutId: UUID,
  memberId: Option[UUID], rollback: Boolean, kind: FleetActionKind, sequence: Int, state: FleetActionState,
  childRequestId: UUID, serverProfilePlanId: Option[UUID], serverProfileRunId: Option[UUID],
  configRolloutId: Option[UUID], intent: Option[Json], failureCode: Option[String], safeMessage: Option[String],
  startedAt: Option[Instant], finishedAt: Option[Instant], version: Long, updatedAt: Instant,
  desiredStateActionId: Option[UUID] = None)
