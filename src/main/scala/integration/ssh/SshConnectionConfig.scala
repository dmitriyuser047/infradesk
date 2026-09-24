package ru.bitec.app.ops
package integration.ssh

import domain.connection.{ConnectionConfig, SshAuthenticationType, SshConnectionSettings}

final case class SshConnectionConfig(
  host: String,
  port: Int,
  username: String,
  hostKeyFingerprint: Option[String],
  connectTimeoutSeconds: Int,
  commandTimeoutSeconds: Int,
  authenticationType: SshAuthenticationType = SshAuthenticationType.Password
)

object SshConnectionConfig {
  val HostKeyFingerprintKey = "hostKeyFingerprint"

  def from(config: ConnectionConfig): Either[IllegalArgumentException, SshConnectionConfig] =
    SshConnectionSettings.from(config).map(fromSettings)

  def fromSettings(value: SshConnectionSettings): SshConnectionConfig =
    SshConnectionConfig(value.host, value.port, value.username, value.hostKeyFingerprint,
      value.connectTimeoutSeconds, value.commandTimeoutSeconds, value.authenticationType)

  def toConnectionConfig(value: SshConnectionConfig): ConnectionConfig =
    SshConnectionSettings.toConnectionConfig(SshConnectionSettings(
      value.host, value.port, value.username, value.hostKeyFingerprint,
      value.connectTimeoutSeconds, value.commandTimeoutSeconds, value.authenticationType))
}
