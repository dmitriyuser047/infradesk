package ru.bitec.app.ops
package integration.ssh

import application.discovery.{DiscoveredExternalIdentity, DiscoveredResource}
import application.port.{ResourceConnector, ResourceConnectorFailure, ResourceConnectorFailureCode, ResourceConnectorResult}
import domain.connection.Connection
import domain.resource.ResourceData
import domain.resource.container.{ContainerSpec, ContainerStatus}
import domain.resource.container.ContainerDefinition
import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}

import cats.MonadThrow
import cats.syntax.all._

import scala.util.Try

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

      result <- sshClient.withSession(config, authentication) { ssh =>
        for {
          nodeResult <- ssh.execute(SshConnector.NodeDiscoveryCommand)
          node <- parseNodeDiscovery(connection, nodeResult)
          dockerResult <- ssh.execute(SshConnector.DockerContainersCommand)

          containerDiscovery <-
            if (!dockerResult.isSuccess) {
              (List.empty[DiscoveredResource] -> Set(SshConnector.NodeExternalType)).pure[F]
            } else {
              parseContainers(connection, dockerResult).map { containers =>
                containers -> Set(SshConnector.NodeExternalType, SshConnector.ContainerExternalType)
              }
            }

          (containers, completeExternalTypes) = containerDiscovery

          updatedConfig = config.hostKeyFingerprint match {
            case Some(_) => connection.config
            case None => connection.config.updated(
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
                cpuUsagePercent = node.cpuUsagePercent,
                memoryUsagePercent = node.memoryUsagePercent,
                uptimeSeconds = node.uptimeSeconds
              ))
            )
          ) :: containers,
          completeExternalTypes = completeExternalTypes,
          connectionConfig = updatedConfig
        )
      }.adaptError { case failure: SshTransportFailure =>
        SshConnector.toConnectorFailure(failure)
      }
    } yield result

  private final case class NodeDiscovery(
                                           hostname: String,
                                           operatingSystem: Option[String],
                                           architecture: Option[String],
                                           cpuCores: Option[Int],
                                           memoryMb: Option[Long],
                                           cpuUsagePercent: Option[BigDecimal],
                                           memoryUsagePercent: Option[BigDecimal],
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
            cpuUsagePercent = values.get("cpu_usage_percent").flatMap(parsePercentage),
            memoryUsagePercent = values.get("memory_usage_percent").flatMap(parsePercentage),
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

  private def parsePercentage(value: String): Option[BigDecimal] =
    optionalValue(value)
      .flatMap(value => Try(BigDecimal(value)).toOption)
      .filter(value => value >= BigDecimal(0) && value <= BigDecimal(100))
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
      case _: SshTransportFailure.CommandTimeout =>
        ResourceConnectorFailureCode.SshCommandTimeout -> "SSH command timed out"
      case _: SshTransportFailure.ConnectionFailed =>
        ResourceConnectorFailureCode.SshConnectionFailed -> "SSH connection failed"
    }
    ResourceConnectorFailure(code, message, failure)
  }

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
      "cpu_first=\"$(awk '/^cpu / { total=0; for (i=2; i<=9 && i<=NF; i++) total += $i; print total, $5 + $6; exit }' /proc/stat 2>/dev/null || true)\"; " +
      "cpu_second=''; if sleep 0.2 2>/dev/null; then cpu_second=\"$(awk '/^cpu / { total=0; for (i=2; i<=9 && i<=NF; i++) total += $i; print total, $5 + $6; exit }' /proc/stat 2>/dev/null || true)\"; fi; " +
      "printf 'cpu_usage_percent\\t%s\\n' \"$(awk -v first=\"$cpu_first\" -v second=\"$cpu_second\" 'BEGIN { n1=split(first, a, \" \"); n2=split(second, b, \" \"); if (n1 != 2 || n2 != 2) exit; total1=a[1]+0; idle1=a[2]+0; total2=b[1]+0; idle2=b[2]+0; deltaTotal=total2-total1; deltaIdle=idle2-idle1; if (deltaTotal <= 0) exit; usage=100*(deltaTotal-deltaIdle)/deltaTotal; if (usage < 0 || usage > 100) exit; printf \"%.6f\", usage }' 2>/dev/null || true)\"; " +
      "printf 'memory_usage_percent\\t%s\\n' \"$(awk '/^MemTotal:/ { total=$2; hasTotal=1 } /^MemAvailable:/ { available=$2; hasAvailable=1 } END { if (hasTotal && hasAvailable && total > 0 && available >= 0 && available <= total) printf \"%.6f\", 100 * (total - available) / total }' /proc/meminfo 2>/dev/null || true)\"; " +
      "printf 'uptime_seconds\\t%s\\n' \"$(awk '{ print int($1) }' /proc/uptime 2>/dev/null || true)\""
  private val DockerContainersCommand = "docker ps --all --no-trunc --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.State}}'"
}
