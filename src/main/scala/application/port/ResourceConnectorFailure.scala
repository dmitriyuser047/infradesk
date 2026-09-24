package ru.bitec.app.ops
package application.port

final case class ResourceConnectorFailure(code: String, safeMessage: String, underlying: Throwable)
  extends RuntimeException(safeMessage, underlying)

object ResourceConnectorFailureCode {
  val SshConnectTimeout = "SSH_CONNECT_TIMEOUT"
  val SshConnectionRefused = "SSH_CONNECTION_REFUSED"
  val SshAuthenticationFailed = "SSH_AUTH_FAILED"
  val SshHostKeyMismatch = "SSH_HOST_KEY_MISMATCH"
  val SshHostKeyNotTrusted = "SSH_HOST_KEY_NOT_TRUSTED"
  val SshPrivateKeyInvalid = "SSH_PRIVATE_KEY_INVALID"
  val SshPrivateKeyPassphraseInvalid = "SSH_PRIVATE_KEY_PASSPHRASE_INVALID"
  val SshCommandTimeout = "SSH_COMMAND_TIMEOUT"
  val SshCommandOutputLimit = "SSH_COMMAND_OUTPUT_LIMIT"
  val SshConnectionFailed = "SSH_CONNECTION_FAILED"
}
