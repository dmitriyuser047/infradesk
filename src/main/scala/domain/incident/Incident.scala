package ru.bitec.app.ops
package domain.incident

import java.time.Instant
import java.util.UUID

final case class Incident(
                           id: UUID,
                           organizationId: UUID,
                           monitorRuleId: UUID,
                           resourceId: UUID,
                           status: IncidentStatus,
                           reason: IncidentReason,
                           startedAt: Instant,
                           openedAt: Instant,
                           resolvedAt: Option[Instant],
                           createdAt: Instant,
                           updatedAt: Instant,
                           /** Fixed when the incident opens: one opened during maintenance is never notified, nor its resolution. */
                           notificationsSilenced: Boolean = false,
                           acknowledgedAt: Option[Instant] = None,
                           acknowledgedBy: Option[UUID] = None
                         )
