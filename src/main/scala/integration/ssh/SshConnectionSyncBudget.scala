package ru.bitec.app.ops
package integration.ssh

import application.port.ConnectionSyncBudget
import domain.connection.Connection

import scala.concurrent.duration._

/** How long one SSH inventory attempt may legitimately take.
  *
  * The connector connects once and then runs the node and the Docker commands one after the
  * other, so a conservative budget covers the connect timeout plus both command timeouts. A
  * connector this adapter does not recognise contributes nothing, and the policy floor applies.
  */
final class SshConnectionSyncBudget extends ConnectionSyncBudget {

  override def maxAttemptDuration(connection: Connection): FiniteDuration =
    if (connection.connectorType != SshConnector.ConnectorType) Duration.Zero
    else
      SshConnectionConfig.from(connection.config)
        .map(config =>
          config.connectTimeoutSeconds.seconds +
            SshConnectionSyncBudget.SequentialCommands * config.commandTimeoutSeconds.seconds
        )
        .getOrElse(Duration.Zero)
}

object SshConnectionSyncBudget {

  /** The node inventory and the Docker inventory, run in sequence on one session. */
  val SequentialCommands: Int = 2
}
