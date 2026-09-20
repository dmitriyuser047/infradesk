package ru.bitec.app.ops
package integration.docker

final case class DockerContainerSummary(
                                         id: String,
                                         names: List[String],
                                         image: String,
                                         state: String,
                                         status: String
                                       )

trait DockerEngineClient[F[_]] {
  def listContainers(
                      config: DockerConnectionConfig
                    ): F[List[DockerContainerSummary]]
}