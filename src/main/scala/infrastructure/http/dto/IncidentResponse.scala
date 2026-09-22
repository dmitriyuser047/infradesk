package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

final case class IncidentResponse(id: UUID, monitorRuleId: UUID, resourceId: UUID, status: String, startedAt: Instant, openedAt: Instant, resolvedAt: Option[Instant], createdAt: Instant, updatedAt: Instant)
