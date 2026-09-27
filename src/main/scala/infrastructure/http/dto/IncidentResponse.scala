package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

final case class IncidentResponse(id: UUID, monitorRuleId: UUID, resourceId: UUID, status: String, reason: String, startedAt: Instant, openedAt: Instant, resolvedAt: Option[Instant], createdAt: Instant, updatedAt: Instant)

/** The resource an incident is about, as much as a list needs to name it and link to it. */
final case class IncidentResourceResponse(id: UUID, name: String, resourceTypeCode: String)
