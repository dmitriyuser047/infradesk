package ru.bitec.app.ops
package integration.ssh

sealed trait SshAuthentication

object SshAuthentication {

  /** The one place where a stored credential becomes transport authentication. */
  def from(credential: domain.connection.SshCredential): SshAuthentication = credential match {
    case domain.connection.SshCredential.Password(value) => Password(value)
    case domain.connection.SshCredential.PrivateKey(pem, passphrase) => PrivateKey(pem, passphrase)
  }

  final case class Password(
                             value: String
                           ) extends SshAuthentication {
    override def toString: String = "SshAuthentication.Password(<redacted>)"
  }

  final case class PrivateKey(
                               pem: String,
                               passphrase: Option[String]
                             ) extends SshAuthentication {
    override def toString: String =
      s"SshAuthentication.PrivateKey(<redacted>, passphrase=${if (passphrase.isDefined) "set" else "none"})"
  }
}

final case class SshCommandResult(
                                   exitCode: Int,
                                   stdout: String,
                                   stderr: String,
                                   hostKeyFingerprint: String
                                 ) {
  def isSuccess: Boolean =
    exitCode == 0
}

trait SshSession[F[_]] {
  def execute(command: String): F[SshCommandResult]
}

trait SshClient[F[_]] {

  /** Reads the host identity without authenticating; the only call allowed against an
    * unconfirmed host.
    */
  def probeHostKey(config: SshConnectionConfig): F[String]

  /** Authenticates and runs commands. The configuration must pin a trusted host key. */
  def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                    (use: SshSession[F] => F[A]): F[A]
}
