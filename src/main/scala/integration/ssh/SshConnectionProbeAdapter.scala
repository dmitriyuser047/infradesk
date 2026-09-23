package ru.bitec.app.ops
package integration.ssh

import application.port.{SshConnectionProbe, SshProbeError}
import cats.effect.IO
import domain.connection.SshConnectionSettings

final class SshConnectionProbeAdapter(client: SshClient[IO]) extends SshConnectionProbe[IO] {
  private val command = "printf 'infradesk-ok\\n'"

  override def probe(settings: SshConnectionSettings, password: String): IO[String] =
    client.withSession(SshConnectionConfig.fromSettings(settings), SshAuthentication.Password(password))(
      _.execute(command)
    )
      .attempt.flatMap {
        case Right(result) if result.exitCode == 0 && result.stdout == "infradesk-ok\n" =>
          IO.pure(result.hostKeyFingerprint)
        case Left(_: SshTransportFailure.HostKeyMismatch) | Left(_: SshHostKeyMismatch) =>
          IO.raiseError(SshProbeError.HostKeyMismatch)
        case _ => IO.raiseError(SshProbeError.ConnectionFailed)
      }
}
