package ru.bitec.app.ops
package application.port

import domain.connection.{Connection, SshConnectionSettings}

import java.util.UUID

sealed trait SshProbeError extends RuntimeException
object SshProbeError {
  case object HostKeyMismatch extends RuntimeException("SSH host key mismatch") with SshProbeError
  case object ConnectionFailed extends RuntimeException("SSH connection failed") with SshProbeError
}

trait SshConnectionProbe[F[_]] {
  def probe(settings: SshConnectionSettings, password: String): F[String]
}

trait SshPasswordResolver[F[_]] {
  def resolvePassword(connection: Connection): F[String]
}

trait ConnectionSecretCryptography {
  def encrypt(id: UUID, organizationId: UUID, password: String): ConnectionSecret
  def decrypt(secret: ConnectionSecret): String
}
