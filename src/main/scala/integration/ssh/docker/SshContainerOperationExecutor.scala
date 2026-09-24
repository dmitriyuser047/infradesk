package ru.bitec.app.ops
package integration.ssh.docker

import application.operation.ResourceOperationFailure
import application.port.{ResourceOperationExecutor, ResourceOperationTarget}
import cats.MonadThrow
import cats.syntax.all._
import domain.operation.ResourceOperationCode
import integration.ssh.{SshAuthenticationProvider, SshClient, SshConnectionConfig, SshTransportFailure}

final case class DockerContainerId private (value: String) extends AnyVal
object DockerContainerId {
  private val Safe = "^[0-9a-f]{12,64}$".r
  def parse(value: String): Either[ResourceOperationFailure, DockerContainerId] = value match {
    case Safe() => Right(DockerContainerId(value))
    case _ => Left(ResourceOperationFailure("INVALID_CONTAINER_ID", "Container operation target is invalid"))
  }
}

final class SshContainerOperationExecutor[F[_]: MonadThrow](
  client: SshClient[F], authentication: SshAuthenticationProvider[F]
) extends ResourceOperationExecutor[F] {
  override def execute(target: ResourceOperationTarget, operation: ResourceOperationCode): F[Unit] =
    (for {
      id <- DockerContainerId.parse(target.externalId).liftTo[F]
      config <- SshConnectionConfig.from(target.connection.config).liftTo[F]
      auth <- authentication.resolve(target.connection)
      result <- client.withSession(config, auth)(_.execute(command(operation, id)))
      _ <- if (result.isSuccess) ().pure[F]
        else ResourceOperationFailure("DOCKER_OPERATION_FAILED", failureMessage(operation)).raiseError[F, Unit]
    } yield ()).adaptError {
      case failure: ResourceOperationFailure => failure
      case failure: SshTransportFailure => transportFailure(failure)
      case error => ResourceOperationFailure("SSH_CONNECTION_FAILED", "SSH connection failed", error)
    }

  private def command(operation: ResourceOperationCode, id: DockerContainerId): String = operation match {
    case ResourceOperationCode.ContainerStart => s"docker start ${id.value}"
    case ResourceOperationCode.ContainerStop => s"docker stop --time 10 ${id.value}"
    case ResourceOperationCode.ContainerRestart => s"docker restart --time 10 ${id.value}"
  }

  private def failureMessage(operation: ResourceOperationCode): String = operation match {
    case ResourceOperationCode.ContainerStart => "Docker container start failed"
    case ResourceOperationCode.ContainerStop => "Docker container stop failed"
    case ResourceOperationCode.ContainerRestart => "Docker container restart failed"
  }

  private def transportFailure(value: SshTransportFailure): ResourceOperationFailure = value match {
    case _: SshTransportFailure.ConnectTimeout => ResourceOperationFailure("SSH_CONNECT_TIMEOUT", "SSH connection timed out", value)
    case _: SshTransportFailure.ConnectionRefused => ResourceOperationFailure("SSH_CONNECTION_REFUSED", "SSH connection was refused", value)
    case _: SshTransportFailure.AuthenticationFailed => ResourceOperationFailure("SSH_AUTH_FAILED", "SSH authentication failed", value)
    case _: SshTransportFailure.HostKeyMismatch => ResourceOperationFailure("SSH_HOST_KEY_MISMATCH", "SSH host key has changed", value)
    case _: SshTransportFailure.CommandTimeout => ResourceOperationFailure("SSH_COMMAND_TIMEOUT", "SSH command timed out", value)
    case _: SshTransportFailure.CommandOutputLimitExceeded => ResourceOperationFailure("SSH_COMMAND_OUTPUT_LIMIT", "SSH command output exceeded the allowed limit", value)
    case _: SshTransportFailure.ConnectionFailed => ResourceOperationFailure("SSH_CONNECTION_FAILED", "SSH connection failed", value)
  }
}
