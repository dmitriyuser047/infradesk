package ru.bitec.app.ops
package application.connection

import domain.connection.{ConnectionScope, SshCredential, SshConnectionSettings}

final case class SshScheduleCommand(enabled: Boolean, intervalSeconds: Long)

final case class CreateSshConnectionCommand(
  code: String,
  name: String,
  scope: ConnectionScope,
  ssh: SshConnectionSettings,
  credential: SshCredential,
  schedule: SshScheduleCommand
)

final case class UpdateSshConnectionCommand(
  code: String,
  name: String,
  scope: ConnectionScope,
  ssh: SshConnectionSettings,
  /** Absent means "keep what is stored"; a change of authentication type requires a new one. */
  credential: Option[SshCredential],
  schedule: SshScheduleCommand
)

/** Asking a host who it is. Deliberately carries no credential. */
final case class ProbeSshHostCommand(ssh: SshConnectionSettings)

/** Authenticating against a host whose identity is already pinned in the settings. */
final case class TestSshConnectionCommand(ssh: SshConnectionSettings, credential: SshCredential)
