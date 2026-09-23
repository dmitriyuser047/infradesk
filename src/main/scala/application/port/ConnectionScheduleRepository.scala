package ru.bitec.app.ops
package application.port

import domain.connection.ConnectionSchedule
import application.scheduler.ClaimedConnectionSchedule

import java.time.Instant
import java.util.UUID

trait ConnectionScheduleRepository[F[_]] {
  def save(schedule: ConnectionSchedule): F[Unit]
  def findByConnection(
    organizationId: UUID,
    connectionId: UUID
  ): F[Option[ConnectionSchedule]]

  def claimDue(claimedBy: UUID, limit: Int, leaseSeconds: Long): F[List[ClaimedConnectionSchedule]]

  def completeClaimedRun(
                          organizationId: UUID,
                          connectionId: UUID,
                          claimedBy: UUID,
                          nextRunAt: Instant,
                          consecutiveFailures: Long
                        ): F[Boolean]
}
