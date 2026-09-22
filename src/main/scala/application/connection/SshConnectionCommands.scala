package ru.bitec.app.ops
package application.connection

import domain.connection.{ConnectionScope, SshConnectionSettings}

final case class SshScheduleCommand(enabled: Boolean, intervalSeconds: Long)

final case class CreateSshConnectionCommand(
  code: String,
  name: String,
  scope: ConnectionScope,
  ssh: SshConnectionSettings,
  password: String,
  schedule: SshScheduleCommand
)

final case class UpdateSshConnectionCommand(
  code: String,
  name: String,
  scope: ConnectionScope,
  ssh: SshConnectionSettings,
  password: Option[String],
  schedule: SshScheduleCommand
)

final case class TestSshConnectionCommand(ssh: SshConnectionSettings, password: String)
