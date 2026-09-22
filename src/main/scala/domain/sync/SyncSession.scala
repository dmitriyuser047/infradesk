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

  def fromCode(code: String): Either[IllegalArgumentException, SyncSessionStatus] =
    code match {
      case Running.code => Right(Running)
      case Completed.code => Right(Completed)
      case Failed.code => Right(Failed)
      case unknown => Left(new IllegalArgumentException(s"Unsupported sync session status '$unknown'"))
    }
}

final case class SyncSession(
                              id: UUID,
                              organizationId: UUID,
                              connectionId: UUID,
                              startedAt: Instant,
                              finishedAt: Option[Instant],
                              status: SyncSessionStatus
                            )
