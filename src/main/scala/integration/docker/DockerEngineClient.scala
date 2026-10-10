package ru.bitec.app.ops
package integration.docker

final case class DockerContainerSummary(
                                         id: String,
                                         names: List[String],
                                         image: String,
                                         state: String,
                                         status: String,
                                         telemetry: domain.metric.ResourceTelemetry = domain.metric.ResourceTelemetry()
                                       )

trait DockerEngineClient[F[_]] {
  def listContainers(
                      config: DockerConnectionConfig
                    ): F[List[DockerContainerSummary]]
}