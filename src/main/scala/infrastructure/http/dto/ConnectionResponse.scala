package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

sealed trait ConnectionScopeResponse

final case class OrganizationConnectionScopeResponse(
  scopeType: String
) extends ConnectionScopeResponse

final case class ProjectConnectionScopeResponse(
  scopeType: String,
  projectId: UUID
) extends ConnectionScopeResponse

final case class EnvironmentConnectionScopeResponse(
  scopeType: String,
  projectId: UUID,
  environmentId: UUID
) extends ConnectionScopeResponse

final case class SyncSessionResponse(
  id: UUID,
  status: String,
  startedAt: Instant,
  finishedAt: Option[Instant]
)

final case class ConnectionScheduleResponse(
  enabled: Boolean,
  intervalSeconds: Long,
  nextRunAt: Instant
)

final case class ConnectionResponse(
  id: UUID,
  connectorType: String,
  code: String,
  name: String,
  scope: ConnectionScopeResponse,
  active: Boolean,
  schedule: Option[ConnectionScheduleResponse],
  lastSync: Option[SyncSessionResponse],
  createdAt: Instant,
  updatedAt: Instant
)
