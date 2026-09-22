package ru.bitec.app.ops
package integration.ssh

sealed trait SshAuthentication

object SshAuthentication {

  final case class Password(
                             value: String
                           ) extends SshAuthentication

  final case class PrivateKey(
                               pem: String,
                               passphrase: Option[String]
                             ) extends SshAuthentication
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
  def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                    (use: SshSession[F] => F[A]): F[A]
}
