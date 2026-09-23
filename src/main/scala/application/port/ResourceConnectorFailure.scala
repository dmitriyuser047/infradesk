package ru.bitec.app.ops
package application.port

final case class ResourceConnectorFailure(code: String, safeMessage: String, underlying: Throwable)
  extends RuntimeException(safeMessage, underlying)

object ResourceConnectorFailureCode {
  val SshConnectTimeout = "SSH_CONNECT_TIMEOUT"
  val SshConnectionRefused = "SSH_CONNECTION_REFUSED"
  val SshAuthenticationFailed = "SSH_AUTH_FAILED"
  val SshHostKeyMismatch = "SSH_HOST_KEY_MISMATCH"
  val SshCommandTimeout = "SSH_COMMAND_TIMEOUT"
  val SshConnectionFailed = "SSH_CONNECTION_FAILED"
}
