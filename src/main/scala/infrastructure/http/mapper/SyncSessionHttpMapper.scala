package ru.bitec.app.ops
package infrastructure.http.mapper

import domain.sync.SyncSession
import infrastructure.http.dto.SyncSessionResponse

object SyncSessionHttpMapper {
  def toResponse(session: SyncSession): SyncSessionResponse =
    SyncSessionResponse(session.id, session.status.code, session.startedAt, session.finishedAt,
      session.errorCode, session.errorMessage)
}
