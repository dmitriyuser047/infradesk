package ru.bitec.app.ops
package application.port

import domain.connection.ConnectionSchedule

import java.time.Instant
import java.util.UUID

trait ConnectionScheduleRepository[F[_]] {
  def findDue(now: Instant, limit: Int): F[List[ConnectionSchedule]]

  def scheduleNext(
                    organizationId: UUID,
                    connectionId: UUID,
                    nextRunAt: Instant
                  ): F[Unit]
}
