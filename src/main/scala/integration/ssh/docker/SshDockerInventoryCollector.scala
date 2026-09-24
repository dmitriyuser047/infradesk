package ru.bitec.app.ops
package integration.ssh.docker

import cats.MonadThrow
import cats.syntax.all._
import integration.ssh.SshSession

final class SshDockerInventoryCollector[F[_]: MonadThrow] {
  def collect(session: SshSession[F]): F[DockerInventoryResult] =
    session.execute(SshDockerInventoryCollector.Command).flatMap { result =>
      if (!result.isSuccess)
        (DockerInventoryResult.Unavailable(result.exitCode): DockerInventoryResult).pure[F]
      else DockerInventoryParser.parse(result.stdout)
        .map(containers => DockerInventoryResult.Available(containers): DockerInventoryResult)
        .liftTo[F]
    }
}

object SshDockerInventoryCollector {
  val Command = "docker ps --all --no-trunc --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.State}}'"
}
