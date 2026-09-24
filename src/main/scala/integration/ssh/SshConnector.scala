package ru.bitec.app.ops
package integration.ssh

import application.port.{ResourceConnector, ResourceConnectorFailure, ResourceConnectorFailureCode, ResourceConnectorResult}
import cats.MonadThrow
import cats.syntax.all._
import domain.connection.Connection
import integration.ssh.docker.{DockerInventoryResult, SshDockerInventoryCollector}
import integration.ssh.node.SshNodeInventoryCollector

final class SshConnector[F[_]: MonadThrow](
  sshClient: SshClient[F],
  authenticationProvider: SshAuthenticationProvider[F]
) extends ResourceConnector[F] {

  private val nodeCollector = new SshNodeInventoryCollector[F]
  private val dockerCollector = new SshDockerInventoryCollector[F]

  override val connectorType: String = SshConnector.ConnectorType

  override def discover(connection: Connection): F[ResourceConnectorResult] =
    for {
      config <- SshConnectionConfig.from(connection.config).liftTo[F]
      _ <- config.hostKeyFingerprint.toRight(
        SshConnector.toConnectorFailure(
          SshTransportFailure.hostKeyNotTrusted(config.host, config.port))).liftTo[F]
      authentication <- authenticationProvider.resolve(connection)
      result <- sshClient.withSession(config, authentication) { session =>
        for {
          collectedNode <- nodeCollector.collect(session)
          docker <- dockerCollector.collect(session)
          node = collectedNode.inventory
          (containers, completeExternalTypes) = docker match {
            case DockerInventoryResult.Available(values) =>
              values.map(_.toDiscoveredResource) ->
                Set(SshConnector.NodeExternalType, SshConnector.ContainerExternalType)
            case DockerInventoryResult.Unavailable(_) =>
              List.empty -> Set(SshConnector.NodeExternalType)
          }
        } yield ResourceConnectorResult(
          resources = node.toDiscoveredResource(connection) :: containers,
          completeExternalTypes = completeExternalTypes,
          connectionConfig = connection.config
        )
      }.adaptError { case failure: SshTransportFailure => SshConnector.toConnectorFailure(failure) }
    } yield result
}

object SshConnector {
  private[ssh] def toConnectorFailure(failure: SshTransportFailure): ResourceConnectorFailure = {
    val (code, message) = failure match {
      case _: SshTransportFailure.ConnectTimeout =>
        ResourceConnectorFailureCode.SshConnectTimeout -> "SSH connection timed out"
      case _: SshTransportFailure.ConnectionRefused =>
        ResourceConnectorFailureCode.SshConnectionRefused -> "SSH connection was refused"
      case _: SshTransportFailure.AuthenticationFailed =>
        ResourceConnectorFailureCode.SshAuthenticationFailed -> "SSH authentication failed"
      case _: SshTransportFailure.HostKeyMismatch =>
        ResourceConnectorFailureCode.SshHostKeyMismatch -> "SSH host key has changed"
      // A host nobody confirmed is refused before authentication, and a sync says so plainly.
      case _: SshTransportFailure.HostKeyNotTrusted =>
        ResourceConnectorFailureCode.SshHostKeyNotTrusted -> "SSH host identity is not trusted"
      case _: SshTransportFailure.PrivateKeyInvalid =>
        ResourceConnectorFailureCode.SshPrivateKeyInvalid -> "SSH private key could not be read"
      case _: SshTransportFailure.PrivateKeyPassphraseInvalid =>
        ResourceConnectorFailureCode.SshPrivateKeyPassphraseInvalid ->
          "SSH private key passphrase is invalid"
      case _: SshTransportFailure.CommandTimeout =>
        ResourceConnectorFailureCode.SshCommandTimeout -> "SSH command timed out"
      case _: SshTransportFailure.CommandOutputLimitExceeded =>
        ResourceConnectorFailureCode.SshCommandOutputLimit ->
          "SSH command output exceeded the allowed limit"
      case _: SshTransportFailure.ConnectionFailed =>
        ResourceConnectorFailureCode.SshConnectionFailed -> "SSH connection failed"
    }
    ResourceConnectorFailure(code, message, failure)
  }

  val ConnectorType = "SSH"
  val NodeExternalType = "NODE"
  val NodeExternalId = "SELF"
  val ContainerExternalType = "CONTAINER"

  val NodeDiscoveryCommand: String = SshNodeInventoryCollector.Command
  val DockerContainersCommand: String = SshDockerInventoryCollector.Command
}
