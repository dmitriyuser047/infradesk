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

trait SshClient[F[_]] {

  def execute(
               config: SshConnectionConfig,
               authentication: SshAuthentication,
               command: String
             ): F[SshCommandResult]
}