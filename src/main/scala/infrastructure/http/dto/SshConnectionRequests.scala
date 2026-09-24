package ru.bitec.app.ops
package infrastructure.http.dto

import java.util.UUID

final case class ConnectionScopeRequest(`type`: String, projectId: Option[UUID], environmentId: Option[UUID])
final case class SshSettingsRequest(
  host: String,
  port: Option[Int],
  username: String,
  authenticationType: String,
  /** The host identity the caller confirmed after probing it. */
  hostKeyFingerprint: Option[String] = None
)

/** A credential as submitted. The discriminator decides which field is required; nothing here is
  * ever echoed back by the API.
  */
final case class SshCredentialsRequest(
  `type`: String,
  password: Option[String],
  privateKey: Option[String],
  passphrase: Option[String]
)
final case class ConnectionScheduleRequest(enabled: Boolean, intervalSeconds: Long)
final case class SaveSshConnectionRequest(
  connectorType: String,
  code: String,
  name: String,
  scope: ConnectionScopeRequest,
  ssh: SshSettingsRequest,
  credentials: Option[SshCredentialsRequest],
  schedule: ConnectionScheduleRequest
)
final case class TestSshConnectionRequest(
  host: String,
  port: Option[Int],
  username: String,
  hostKeyFingerprint: Option[String],
  credentials: SshCredentialsRequest
)

/** Asking a host who it is; deliberately has no credential field at all. */
final case class ProbeSshHostRequest(host: String, port: Option[Int], username: String)
final case class ProbeSshHostResponse(hostKeyFingerprint: String)
final case class TestSshConnectionResponse(success: Boolean, hostKeyFingerprint: String)
