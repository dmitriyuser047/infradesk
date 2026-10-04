package ru.bitec.app.ops
package application.port

import domain.integration._
import java.time.Instant
import java.util.UUID

trait RemnawaveFleetRolloutRepository[F[_]] {
  def insertPlan(rollout: RemnawaveFleetRollout, members: List[RemnawaveFleetRolloutMember]): F[Unit]
  def rollout(org: UUID, fleetId: UUID, id: UUID): F[Option[RemnawaveFleetRollout]]
  def rolloutForUpdate(org: UUID, fleetId: UUID, id: UUID): F[Option[RemnawaveFleetRollout]]
  def rolloutById(id: UUID): F[Option[RemnawaveFleetRollout]]
  def byRequest(org: UUID, requestId: UUID): F[Option[RemnawaveFleetRollout]]
  def history(org: UUID, fleetId: UUID, limit: Int): F[List[RemnawaveFleetRollout]]
  def activeOf(org: UUID, fleetId: UUID): F[Option[RemnawaveFleetRollout]]
  /** PLANNED to QUEUED. False when the plan is gone, expired, already started or the fleet is busy. */
  def start(org: UUID, id: UUID, requestId: UUID, now: Instant): F[Boolean]
  def members(rolloutId: UUID): F[List[RemnawaveFleetRolloutMember]]
  def actions(rolloutId: UUID): F[List[RemnawaveFleetRolloutAction]]
  def requestPause(org: UUID, id: UUID, by: UUID, now: Instant): F[Boolean]
  def resume(org: UUID, id: UUID, now: Instant): F[Boolean]
  def requestRollback(org: UUID, id: UUID, by: UUID, scope: FleetRollbackScope, now: Instant): F[Boolean]

  def claimDue(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): F[List[RemnawaveFleetRollout]]
  def renewClaim(id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]
  /** Writes only worker-owned columns, and only while the claim is still ours. */
  def save(rollout: RemnawaveFleetRollout, token: UUID, now: Instant, clearPauseRequest: Boolean,
    release: Boolean): F[Boolean]
  def saveMember(member: RemnawaveFleetRolloutMember, token: UUID, now: Instant): F[Boolean]
  def saveAction(action: RemnawaveFleetRolloutAction, token: UUID, now: Instant): F[Boolean]
  def purgeExpired(now: Instant, limit: Int): F[Int]
}
