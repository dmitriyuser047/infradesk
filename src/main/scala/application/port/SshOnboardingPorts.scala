package ru.bitec.app.ops
package application.port

import domain.connection.{Connection, SshCredential, SshConnectionSettings}

import java.util.UUID

sealed trait SshProbeError extends RuntimeException
object SshProbeError {
  case object HostKeyMismatch extends RuntimeException("SSH host key mismatch") with SshProbeError
  case object HostKeyNotTrusted extends RuntimeException("SSH host key is not trusted") with SshProbeError
  case object AuthenticationFailed extends RuntimeException("SSH authentication failed") with SshProbeError
  case object PrivateKeyInvalid extends RuntimeException("SSH private key is invalid") with SshProbeError
  case object PrivateKeyPassphraseInvalid
    extends RuntimeException("SSH private key passphrase is invalid") with SshProbeError
  case object ConnectionFailed extends RuntimeException("SSH connection failed") with SshProbeError
}

/** Two distinct steps, kept apart on purpose.
  *
  * Asking a host who it is must never involve a credential: the answer is what decides whether
  * the host may be given one at all.
  */
trait SshConnectionProbe[F[_]] {

  /** Opens a transport far enough to read the host key and stops there. No authentication. */
  def probeHostKey(settings: SshConnectionSettings): F[String]

  /** Authenticates against a host whose identity the settings already pin. */
  def verify(settings: SshConnectionSettings, credential: SshCredential): F[String]
}

trait SshCredentialResolver[F[_]] {
  def resolveCredential(connection: Connection): F[SshCredential]
}

trait ConnectionSecretCryptography {
  def encrypt(id: UUID, organizationId: UUID, credential: SshCredential): ConnectionSecret
  def decrypt(secret: ConnectionSecret): SshCredential
}
