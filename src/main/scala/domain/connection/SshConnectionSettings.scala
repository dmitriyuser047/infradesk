package ru.bitec.app.ops
package domain.connection

import cats.syntax.all._

/** Non-secret SSH configuration shared by the use case and the SSH adapter. */
final case class SshConnectionSettings(
  host: String,
  port: Int,
  username: String,
  /** The host identity this connection trusts. Empty means the host has not been confirmed yet,
    * and no credential may be sent to it.
    */
  hostKeyFingerprint: Option[String],
  connectTimeoutSeconds: Int,
  commandTimeoutSeconds: Int,
  authenticationType: SshAuthenticationType = SshAuthenticationType.Password
) {
  def hostTrusted: Boolean = hostKeyFingerprint.isDefined

  require(connectTimeoutSeconds > 0 &&
    connectTimeoutSeconds <= SshConnectionSettings.MaxConnectTimeoutSeconds,
    s"connectTimeoutSeconds must be between 1 and ${SshConnectionSettings.MaxConnectTimeoutSeconds}")
  require(commandTimeoutSeconds > 0 &&
    commandTimeoutSeconds <= SshConnectionSettings.MaxCommandTimeoutSeconds,
    s"commandTimeoutSeconds must be between 1 and ${SshConnectionSettings.MaxCommandTimeoutSeconds}")
}

object SshConnectionSettings {
  val DefaultConnectTimeoutSeconds = 10
  val DefaultCommandTimeoutSeconds = 30

  /** Production bounds used by both SSH execution and the reverse-proxy budget. */
  val MaxConnectTimeoutSeconds = 60
  val MaxCommandTimeoutSeconds = 1200

  def from(config: ConnectionConfig): Either[IllegalArgumentException, SshConnectionSettings] =
    for {
      host <- required(config, "host")
      username <- required(config, "username")
      port <- positiveInt(config, "port", 22)
      connectTimeout <- boundedPositiveInt(config, "connectTimeoutSeconds",
        DefaultConnectTimeoutSeconds, MaxConnectTimeoutSeconds)
      commandTimeout <- boundedPositiveInt(config, "commandTimeoutSeconds",
        DefaultCommandTimeoutSeconds, MaxCommandTimeoutSeconds)
      // Connections stored before authentication became configurable are password connections.
      authenticationType <- config.get("authenticationType").map(_.trim).filter(_.nonEmpty)
        .fold[Either[IllegalArgumentException, SshAuthenticationType]](
          Right(SshAuthenticationType.Password))(SshAuthenticationType.fromCode)
    } yield SshConnectionSettings(host, port, username,
      config.get("hostKeyFingerprint").map(_.trim).filter(_.nonEmpty), connectTimeout, commandTimeout,
      authenticationType)

  def toConnectionConfig(value: SshConnectionSettings): ConnectionConfig =
    ConnectionConfig(Map(
      "host" -> value.host,
      "port" -> value.port.toString,
      "username" -> value.username,
      "connectTimeoutSeconds" -> value.connectTimeoutSeconds.toString,
      "commandTimeoutSeconds" -> value.commandTimeoutSeconds.toString,
      "authenticationType" -> value.authenticationType.code
    ) ++ value.hostKeyFingerprint.map("hostKeyFingerprint" -> _))

  private def required(config: ConnectionConfig, key: String): Either[IllegalArgumentException, String] =
    config.get(key).map(_.trim).filter(_.nonEmpty)
      .toRight(new IllegalArgumentException(s"SSH connection config requires '$key'"))

  private def positiveInt(config: ConnectionConfig, key: String, default: Int): Either[IllegalArgumentException, Int] =
    boundedPositiveInt(config, key, default, Int.MaxValue)

  private def boundedPositiveInt(
    config: ConnectionConfig,
    key: String,
    default: Int,
    maximum: Int
  ): Either[IllegalArgumentException, Int] =
    config.get(key) match {
      case None => Right(default)
      case Some(value) =>
        Either.catchOnly[NumberFormatException](value.trim.toInt)
          .leftMap(_ => new IllegalArgumentException(s"SSH connection config '$key' must be an integer"))
          .flatMap { parsed =>
            if (parsed <= 0)
              Left(new IllegalArgumentException(s"SSH connection config '$key' must be greater than zero"))
            else if (parsed > maximum)
              Left(new IllegalArgumentException(
                s"SSH connection config '$key' must not exceed $maximum"))
            else Right(parsed)
          }
    }
}
