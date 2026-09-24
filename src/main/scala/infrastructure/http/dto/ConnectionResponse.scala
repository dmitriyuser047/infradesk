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
  finishedAt: Option[Instant],
  errorCode: Option[String],
  errorMessage: Option[String]
)

final case class ConnectionScheduleResponse(
  enabled: Boolean,
  intervalSeconds: Long,
  nextRunAt: Instant
)

/** Safe metadata only: how the connection authenticates, whether a credential exists, and which
  * host identity it trusts. The credential itself is never part of a response.
  */
final case class SshConnectionResponse(
  host: String,
  port: Int,
  username: String,
  hostKeyFingerprint: Option[String],
  credentialConfigured: Boolean,
  authenticationType: String,
  hostTrusted: Boolean
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
  updatedAt: Instant,
  ssh: Option[SshConnectionResponse]
)
