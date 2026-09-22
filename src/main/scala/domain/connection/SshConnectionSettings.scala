package ru.bitec.app.ops
package domain.connection

import cats.syntax.all._

/** Non-secret SSH configuration shared by the use case and the SSH adapter. */
final case class SshConnectionSettings(
  host: String,
  port: Int,
  username: String,
  hostKeyFingerprint: Option[String],
  connectTimeoutSeconds: Int,
  commandTimeoutSeconds: Int
)

object SshConnectionSettings {
  def from(config: ConnectionConfig): Either[IllegalArgumentException, SshConnectionSettings] =
    for {
      host <- required(config, "host")
      username <- required(config, "username")
      port <- positiveInt(config, "port", 22)
      connectTimeout <- positiveInt(config, "connectTimeoutSeconds", 10)
      commandTimeout <- positiveInt(config, "commandTimeoutSeconds", 30)
    } yield SshConnectionSettings(host, port, username,
      config.get("hostKeyFingerprint").map(_.trim).filter(_.nonEmpty), connectTimeout, commandTimeout)

  def toConnectionConfig(value: SshConnectionSettings): ConnectionConfig =
    ConnectionConfig(Map(
      "host" -> value.host,
      "port" -> value.port.toString,
      "username" -> value.username,
      "connectTimeoutSeconds" -> value.connectTimeoutSeconds.toString,
      "commandTimeoutSeconds" -> value.commandTimeoutSeconds.toString
    ) ++ value.hostKeyFingerprint.map("hostKeyFingerprint" -> _))

  private def required(config: ConnectionConfig, key: String): Either[IllegalArgumentException, String] =
    config.get(key).map(_.trim).filter(_.nonEmpty)
      .toRight(new IllegalArgumentException(s"SSH connection config requires '$key'"))

  private def positiveInt(config: ConnectionConfig, key: String, default: Int): Either[IllegalArgumentException, Int] =
    config.get(key) match {
      case None => Right(default)
      case Some(value) =>
        Either.catchOnly[NumberFormatException](value.trim.toInt)
          .leftMap(_ => new IllegalArgumentException(s"SSH connection config '$key' must be an integer"))
          .flatMap { parsed =>
            if (parsed > 0) Right(parsed)
            else Left(new IllegalArgumentException(s"SSH connection config '$key' must be greater than zero"))
          }
    }
}
