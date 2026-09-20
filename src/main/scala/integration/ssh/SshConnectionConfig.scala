package ru.bitec.app.ops
package integration.ssh

import cats.syntax.all._
import domain.connection.ConnectionConfig

final case class SshConnectionConfig(
                                      host: String,
                                      port: Int,
                                      username: String,
                                      hostKeyFingerprint: Option[String],
                                      connectTimeoutSeconds: Int,
                                      commandTimeoutSeconds: Int
                                    )

object SshConnectionConfig {

  private val HostKey = "host"
  private val PortKey = "port"
  private val UsernameKey = "username"
  private val ConnectTimeoutSecondsKey = "connectTimeoutSeconds"

  private val DefaultPort = 22
  private val DefaultConnectTimeoutSeconds = 10
  val HostKeyFingerprintKey = "hostKeyFingerprint"
  private val CommandTimeoutSecondsKey = "commandTimeoutSeconds"

  private val DefaultCommandTimeoutSeconds = 30

  def from(
            config: ConnectionConfig
          ): Either[IllegalArgumentException, SshConnectionConfig] =
    for {
      host <- required(config, HostKey)
      username <- required(config, UsernameKey)
      port <- positiveInt(config, PortKey, DefaultPort)
      connectTimeout <- positiveInt(
        config,
        ConnectTimeoutSecondsKey,
        DefaultConnectTimeoutSeconds
      )
      commandTimeout <- positiveInt(
        config,
        CommandTimeoutSecondsKey,
        DefaultCommandTimeoutSeconds
      )
    } yield SshConnectionConfig(
      host = host,
      port = port,
      username = username,
      hostKeyFingerprint = config
        .get(HostKeyFingerprintKey)
        .map(_.trim)
        .filter(_.nonEmpty),
      connectTimeoutSeconds = connectTimeout,
      commandTimeoutSeconds = commandTimeout
    )

  private def required(
                        config: ConnectionConfig,
                        key: String
                      ): Either[IllegalArgumentException, String] =
    config
      .get(key)
      .map(_.trim)
      .filter(_.nonEmpty)
      .toRight(
        new IllegalArgumentException(
          s"SSH connection config requires '$key'"
        )
      )

  private def positiveInt(
                           config: ConnectionConfig,
                           key: String,
                           default: Int
                         ): Either[IllegalArgumentException, Int] =
    config.get(key) match {
      case None =>
        Right(default)

      case Some(value) =>
        Either
          .catchOnly[NumberFormatException](value.trim.toInt)
          .leftMap { _ =>
            new IllegalArgumentException(
              s"SSH connection config '$key' must be an integer"
            )
          }
          .flatMap { parsed =>
            if (parsed > 0)
              Right(parsed)
            else
              Left(
                new IllegalArgumentException(
                  s"SSH connection config '$key' must be greater than zero"
                )
              )
          }
    }
}