package ru.bitec.app.ops
package integration.ssh

import domain.connection.Connection

trait SshAuthenticationProvider[F[_]] {
  def resolve(connection: Connection): F[SshAuthentication]
}