package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

/** One journal row as the API returns it: identifiers only, resolved by the client. */
final case class AuditEventResponse(
  id: UUID,
  actorUserId: UUID,
  action: String,
  targetType: String,
  targetId: Option[UUID],
  occurredAt: Instant
)
