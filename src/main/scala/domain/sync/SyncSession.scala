package ru.bitec.app.ops
package domain.sync

import java.time.Instant
import java.util.UUID

sealed trait SyncSessionStatus {
  def code: String
}

object SyncSessionStatus {
  case object Running extends SyncSessionStatus { override val code = "RUNNING" }
  case object Completed extends SyncSessionStatus { override val code = "COMPLETED" }
  case object Failed extends SyncSessionStatus { override val code = "FAILED" }
}

final case class SyncSession(
                              id: UUID,
                              organizationId: UUID,
                              connectionId: UUID,
                              startedAt: Instant,
                              finishedAt: Option[Instant],
                              status: SyncSessionStatus
                            )
