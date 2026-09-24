package ru.bitec.app.ops
package integration.ssh

import java.io.IOException
import java.net.{ConnectException, SocketTimeoutException}
import java.util.concurrent.TimeoutException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException

sealed abstract class SshTransportFailure(message: String, cause: Throwable)
  extends RuntimeException(message, cause)

object SshTransportFailure {
  final class ConnectTimeout(cause: Throwable)
    extends SshTransportFailure("SSH connection timed out", cause)
  final class ConnectionRefused(cause: Throwable)
    extends SshTransportFailure("SSH connection was refused", cause)
  final class AuthenticationFailed(cause: Throwable)
    extends SshTransportFailure("SSH authentication failed", cause)
  final class HostKeyMismatch(cause: Throwable)
    extends SshTransportFailure("SSH host key has changed", cause)
  final class CommandTimeout(cause: Throwable)
    extends SshTransportFailure("SSH command timed out", cause)
  final class CommandOutputLimitExceeded(cause: Throwable)
    extends SshTransportFailure("SSH command output exceeded the allowed limit", cause)
  final class ConnectionFailed(cause: Throwable)
    extends SshTransportFailure("SSH connection failed", cause)

  private[ssh] def fromConnect(error: IOException, hostKeyMismatch: Boolean): SshTransportFailure =
    if (hostKeyMismatch) new HostKeyMismatch(new SshHostKeyMismatch(error))
    else if (hasCause(error)(_.isInstanceOf[SocketTimeoutException])) new ConnectTimeout(error)
    else if (hasCause(error)(_.isInstanceOf[ConnectException])) new ConnectionRefused(error)
    else new ConnectionFailed(error)

  private[ssh] def fromAuthentication(error: UserAuthException): SshTransportFailure =
    if (hasNestedNetworkCause(error)) new ConnectionFailed(error)
    else new AuthenticationFailed(error)

  private[ssh] def fromAuthenticationTransport(error: TransportException): SshTransportFailure =
    new ConnectionFailed(error)

  private[ssh] def fromCommandTransport(error: IOException): SshTransportFailure =
    new ConnectionFailed(error)

  private[ssh] def commandTimeout(): SshTransportFailure =
    new CommandTimeout(new TimeoutException("SSH command timed out"))

  private[ssh] def commandOutputLimitExceeded(stream: String, limitBytes: Int): SshTransportFailure =
    new CommandOutputLimitExceeded(
      new IOException(s"SSH $stream exceeded its $limitBytes byte limit")
    )

  private def hasNestedNetworkCause(error: Throwable): Boolean =
    Option(error.getCause).exists { cause =>
      hasCause(cause)(_.isInstanceOf[IOException]) ||
        hasCause(cause)(_.isInstanceOf[TransportException])
    }

  private def hasCause(error: Throwable)(matches: Throwable => Boolean): Boolean = {
    var current = error
    var depth = 0
    while (current != null && depth < 16) {
      if (matches(current)) return true
      current = current.getCause
      depth += 1
    }
    false
  }
}
