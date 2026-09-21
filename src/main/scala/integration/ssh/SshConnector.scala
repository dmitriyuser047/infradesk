package ru.bitec.app.ops
package integration.ssh

import application.discovery.{DiscoveredExternalIdentity, DiscoveredResource}
import application.port.{ResourceConnector, ResourceConnectorResult}
import domain.connection.Connection
import domain.resource.ResourceData
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.container.ContainerDefinition
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

      effectiveConfig =
        config.hostKeyFingerprint match {
          case Some(_) =>
            config

          case None =>
            config.copy(
              hostKeyFingerprint = Some(hostnameResult.hostKeyFingerprint)
            )
        }

      containerDiscovery <- sshClient
        .execute(
          effectiveConfig,
          authentication,
          SshConnector.DockerContainersCommand
        )
        .flatMap(parseContainers(connection, _))
        .map(containers => containers -> Set(
          SshConnector.NodeExternalType,
          SshConnector.ContainerExternalType
        ))
        .attempt
        .map {
          case Right(value) => value
          case Left(_) => List.empty[DiscoveredResource] -> Set(SshConnector.NodeExternalType)
        }

      (containers, completeExternalTypes) = containerDiscovery

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
      resources = DiscoveredResource(
        externalType = SshConnector.NodeExternalType,
        externalId = SshConnector.NodeExternalId,
        resourceTypeCode = NodeDefinition.code,
        code = connection.code,
        name = hostname
      ) :: containers,
      completeExternalTypes = completeExternalTypes,
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

  private def parseContainers(
                               connection: Connection,
                               result: SshCommandResult
                             ): F[List[DiscoveredResource]] =
    if (!result.isSuccess) {
      new IllegalStateException(
        s"Failed to discover Docker containers for SSH connection ${connection.id}: " +
          s"docker ps exited with code ${result.exitCode}: ${result.stderr.trim}"
      ).raiseError[F, List[DiscoveredResource]]
    } else {
      result.stdout
        .linesIterator
        .filter(_.nonEmpty)
        .toList
        .traverse(parseContainer(connection, _))
    }

  private def parseContainer(
                              connection: Connection,
                              line: String
                            ): F[DiscoveredResource] =
    line.split("\\t", 4).toList match {
      case containerId :: name :: image :: state :: Nil if containerId.nonEmpty && name.nonEmpty =>
        DiscoveredResource(
          externalType = SshConnector.ContainerExternalType,
          externalId = containerId,
          resourceTypeCode = ContainerDefinition.code,
          code = name,
          name = name,
          parentExternalIdentity = Some(
            DiscoveredExternalIdentity(
              SshConnector.NodeExternalType,
              SshConnector.NodeExternalId
            )
          ),
          data = ResourceData(
            spec = Some(ContainerSpec(optionalValue(image))),
            status = Some(ContainerStatus(optionalValue(state)))
          )
        ).pure[F]

      case _ =>
        new IllegalStateException(
          s"SSH connection ${connection.id} returned malformed docker ps row: '$line'"
        ).raiseError[F, DiscoveredResource]
    }

  private def optionalValue(value: String): Option[String] =
    Option(value.trim).filter(_.nonEmpty)
}

object SshConnector {

  val ConnectorType = "SSH"

  val NodeExternalType = "NODE"
  val NodeExternalId = "SELF"

  val ContainerExternalType = "CONTAINER"
  private val HostnameCommand = "hostname"
  private val DockerContainersCommand = "docker ps --all --no-trunc --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.State}}'"
}
