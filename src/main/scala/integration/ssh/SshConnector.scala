package ru.bitec.app.ops
package integration.ssh

import application.discovery.{DiscoveredExternalIdentity, DiscoveredResource}
import application.port.{ResourceConnector, ResourceConnectorResult}
import domain.connection.Connection
import domain.resource.ResourceData
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.container.ContainerDefinition
import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}

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

      nodeResult <- sshClient.execute(
        config,
        authentication,
        SshConnector.NodeDiscoveryCommand
      )

      node <- parseNodeDiscovery(
        connection,
        nodeResult
      )

      effectiveConfig =
        config.hostKeyFingerprint match {
          case Some(_) =>
            config

          case None =>
            config.copy(
              hostKeyFingerprint = Some(nodeResult.hostKeyFingerprint)
            )
        }

      dockerResult <- sshClient.execute(
        effectiveConfig,
        authentication,
        SshConnector.DockerContainersCommand
      )

      containerDiscovery <-
        if (!dockerResult.isSuccess) {
          (List.empty[DiscoveredResource] -> Set(SshConnector.NodeExternalType)).pure[F]
        } else {
          parseContainers(connection, dockerResult).map { containers =>
            containers -> Set(
              SshConnector.NodeExternalType,
              SshConnector.ContainerExternalType
            )
          }
        }

      (containers, completeExternalTypes) = containerDiscovery

      updatedConfig =
        config.hostKeyFingerprint match {
          case Some(_) =>
            connection.config

          case None =>
            connection.config.updated(
              SshConnectionConfig.HostKeyFingerprintKey,
              nodeResult.hostKeyFingerprint
            )
        }
    } yield ResourceConnectorResult(
      resources = DiscoveredResource(
        externalType = SshConnector.NodeExternalType,
        externalId = SshConnector.NodeExternalId,
        resourceTypeCode = NodeDefinition.code,
        code = connection.code,
        name = node.hostname,
        data = ResourceData(
          spec = Some(NodeSpec(
            hostname = node.hostname,
            operatingSystem = node.operatingSystem,
            architecture = node.architecture,
            cpuCores = node.cpuCores,
            memoryMb = node.memoryMb
          )),
          status = Some(NodeStatus(
            online = true,
            cpuUsagePercent = None,
            memoryUsagePercent = None,
            uptimeSeconds = node.uptimeSeconds
          ))
        )
      ) :: containers,
      completeExternalTypes = completeExternalTypes,
      connectionConfig = updatedConfig
    )

  private final case class NodeDiscovery(
                                           hostname: String,
                                           operatingSystem: Option[String],
                                           architecture: Option[String],
                                           cpuCores: Option[Int],
                                           memoryMb: Option[Long],
                                           uptimeSeconds: Option[Long]
                                         )

  private def parseNodeDiscovery(
                                  connection: Connection,
                                  result: SshCommandResult
                                ): F[NodeDiscovery] =
    if (!result.isSuccess) {
      new IllegalStateException(
        s"Failed to discover SSH node for connection ${connection.id}: " +
          s"node discovery exited with code ${result.exitCode}: ${result.stderr.trim}"
      ).raiseError[F, NodeDiscovery]
    } else {
      val values = result.stdout.linesIterator.foldLeft(Map.empty[String, String]) { (current, line) =>
        line.split("\\t", 2).toList match {
          case key :: value :: Nil => current.updated(key, value.trim)
          case _ => current
        }
      }

      val hostname = values.get("hostname").flatMap(optionalValue)

      hostname match {
        case Some(value) =>
          NodeDiscovery(
            hostname = value,
            operatingSystem = values.get("operating_system").flatMap(optionalValue),
            architecture = values.get("architecture").flatMap(optionalValue),
            cpuCores = values.get("cpu_cores").flatMap(parsePositiveInt),
            memoryMb = values.get("memory_mb").flatMap(parseNonNegativeLong),
            uptimeSeconds = values.get("uptime_seconds").flatMap(parseNonNegativeLong)
          ).pure[F]

        case None =>
          new IllegalStateException(
            s"SSH connection ${connection.id} returned empty hostname from node discovery"
          ).raiseError[F, NodeDiscovery]
      }
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

  private def parsePositiveInt(value: String): Option[Int] =
    optionalValue(value).flatMap(_.toIntOption).filter(_ > 0)

  private def parseNonNegativeLong(value: String): Option[Long] =
    optionalValue(value).flatMap(_.toLongOption).filter(_ >= 0)
}

object SshConnector {

  val ConnectorType = "SSH"

  val NodeExternalType = "NODE"
  val NodeExternalId = "SELF"

  val ContainerExternalType = "CONTAINER"
  val NodeDiscoveryCommand =
    "LC_ALL=C; export LC_ALL; " +
      "printf 'hostname\\t%s\\n' \"$(hostname 2>/dev/null || true)\"; " +
      "printf 'operating_system\\t%s\\n' \"$(uname -s 2>/dev/null || true)\"; " +
      "printf 'architecture\\t%s\\n' \"$(uname -m 2>/dev/null || true)\"; " +
      "printf 'cpu_cores\\t%s\\n' \"$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)\"; " +
      "printf 'memory_mb\\t%s\\n' \"$(awk '/^MemTotal:/ { printf \"%d\", $2 / 1024; exit }' /proc/meminfo 2>/dev/null || true)\"; " +
      "printf 'uptime_seconds\\t%s\\n' \"$(awk '{ print int($1) }' /proc/uptime 2>/dev/null || true)\""
  private val DockerContainersCommand = "docker ps --all --no-trunc --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.State}}'"
}
