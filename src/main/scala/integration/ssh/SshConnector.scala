package ru.bitec.app.ops
package integration.ssh

import application.discovery.DiscoveredResource
import application.port.{ResourceConnector, ResourceConnectorResult}
import domain.connection.Connection
import domain.resource.node.NodeDefinition

import cats.MonadThrow
import cats.syntax.all._

final class SshConnector[F[_]: MonadThrow](
                                            sshClient: SshClient[F],
                                            authenticationProvider: SshAuthenticationProvider[F]
                                          ) extends ResourceConnector[F] {

  override val connectorType: String =
    SshConnector.ConnectorType

  override def discover(
                         connection: Connection
                       ): F[ResourceConnectorResult] =
    for {
      config <- SshConnectionConfig
        .from(connection.config)
        .liftTo[F]

      authentication <-
        authenticationProvider.resolve(connection)

      hostnameResult <- sshClient.execute(
        config,
        authentication,
        SshConnector.HostnameCommand
      )

      hostname <- parseHostname(
        connection,
        hostnameResult
      )

      updatedConfig =
        config.hostKeyFingerprint match {
          case Some(_) =>
            connection.config

          case None =>
            connection.config.updated(
              SshConnectionConfig.HostKeyFingerprintKey,
              hostnameResult.hostKeyFingerprint
            )
        }
    } yield ResourceConnectorResult(
      resources = List(
        DiscoveredResource(
          externalType = SshConnector.NodeExternalType,
          externalId = SshConnector.NodeExternalId,
          resourceTypeCode = NodeDefinition.code,
          code = connection.code,
          name = hostname
        )
      ),
      connectionConfig = updatedConfig
    )

  private def parseHostname(
                             connection: Connection,
                             result: SshCommandResult
                           ): F[String] =
    if (!result.isSuccess) {
      new IllegalStateException(
        s"Failed to discover SSH node for connection ${connection.id}: " +
          s"hostname exited with code ${result.exitCode}: ${result.stderr.trim}"
      ).raiseError[F, String]
    } else {
      val hostname = result.stdout.trim

      if (hostname.nonEmpty)
        hostname.pure[F]
      else
        new IllegalStateException(
          s"SSH connection ${connection.id} returned empty hostname"
        ).raiseError[F, String]
    }
}

object SshConnector {

  val ConnectorType = "SSH"

  val NodeExternalType = "NODE"
  val NodeExternalId = "SELF"

  private val HostnameCommand = "hostname"
}