package ru.bitec.app.ops
package infrastructure.http.dto

import java.util.UUID

final case class ConnectionScopeRequest(`type`: String, projectId: Option[UUID], environmentId: Option[UUID])
final case class SshSettingsRequest(host: String, port: Option[Int], username: String)
final case class PasswordCredentialsRequest(`type`: String, password: String)
final case class ConnectionScheduleRequest(enabled: Boolean, intervalSeconds: Long)
final case class SaveSshConnectionRequest(
  connectorType: String,
  code: String,
  name: String,
  scope: ConnectionScopeRequest,
  ssh: SshSettingsRequest,
  credentials: Option[PasswordCredentialsRequest],
  schedule: ConnectionScheduleRequest
)
final case class TestSshConnectionRequest(
  host: String,
  port: Option[Int],
  username: String,
  credentials: PasswordCredentialsRequest
)
final case class TestSshConnectionResponse(success: Boolean, hostKeyFingerprint: String)
