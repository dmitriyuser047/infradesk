package ru.bitec.app.ops
package integration.ssh

import application.port.{SshConnectionProbe, SshProbeError}
import cats.effect.IO
import domain.connection.{SshConnectionSettings, SshCredential}

/** The onboarding side of the same SSH stack the runtime uses.
  *
  * Probing reads the host identity and nothing else; verifying authenticates, and only against a
  * host the settings already pin. Both go through the one client, so there is no second, laxer
  * SSH path anywhere in the application.
  */
final class SshConnectionProbeAdapter(client: SshClient[IO]) extends SshConnectionProbe[IO] {
  private val command = "printf 'infradesk-ok\\n'"

  override def probeHostKey(settings: SshConnectionSettings): IO[String] =
    client.probeHostKey(SshConnectionConfig.fromSettings(settings)).adaptError {
      case error => probeError(error)
    }

  override def verify(settings: SshConnectionSettings, credential: SshCredential): IO[String] =
    client.withSession(SshConnectionConfig.fromSettings(settings), SshAuthentication.from(credential))(
      _.execute(command)
    )
      .attempt.flatMap {
        case Right(result) if result.exitCode == 0 && result.stdout == "infradesk-ok\n" =>
          IO.pure(result.hostKeyFingerprint)
        case Left(error) => IO.raiseError(probeError(error))
        case Right(_) => IO.raiseError(SshProbeError.ConnectionFailed)
      }

  /** Operator-actionable reasons stay distinct; everything else is a connection failure. */
  private def probeError(error: Throwable): Throwable = error match {
    case _: SshTransportFailure.HostKeyMismatch | _: SshHostKeyMismatch => SshProbeError.HostKeyMismatch
    case _: SshTransportFailure.HostKeyNotTrusted => SshProbeError.HostKeyNotTrusted
    case _: SshTransportFailure.AuthenticationFailed => SshProbeError.AuthenticationFailed
    case _: SshTransportFailure.PrivateKeyInvalid => SshProbeError.PrivateKeyInvalid
    case _: SshTransportFailure.PrivateKeyPassphraseInvalid => SshProbeError.PrivateKeyPassphraseInvalid
    case _ => SshProbeError.ConnectionFailed
  }
}
