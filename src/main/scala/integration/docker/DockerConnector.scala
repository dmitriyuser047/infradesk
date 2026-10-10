package ru.bitec.app.ops
package integration.docker

import application.discovery.DiscoveredResource
import application.port.{ResourceConnector, ResourceConnectorResult}
import domain.connection.Connection
import domain.resource.container.{ContainerDefinition, ContainerSpec, ContainerStatus}
import domain.resource.ResourceData

import cats.MonadThrow
import cats.syntax.all._

final class DockerConnector[F[_]: MonadThrow](
                                               dockerEngineClient: DockerEngineClient[F]
                                             ) extends ResourceConnector[F] {

  override val connectorType: String =
    DockerConnector.ConnectorType

  override def discover(
                         connection: Connection
                       ): F[ResourceConnectorResult] =
    for {
      config <- DockerConnectionConfig
        .from(connection.config)
        .liftTo[F]

      containers <- dockerEngineClient.listContainers(config)
    } yield ResourceConnectorResult(
      resources = containers.map(toDiscoveredResource),
      completeExternalTypes = Set(DockerConnector.ExternalType),
      connectionConfig = connection.config
    )

  private def toDiscoveredResource(
                                    container: DockerContainerSummary
                                  ): DiscoveredResource = {
    val name = resolveName(container)

    DiscoveredResource(
      externalType = DockerConnector.ExternalType,
      externalId = container.id,
      resourceTypeCode = ContainerDefinition.code,
      code = name,
      name = name,
      data = ResourceData(Some(ContainerSpec(Option(container.image).filter(_.nonEmpty))),
        Some(ContainerStatus(Option(container.state).filter(_.nonEmpty), container.telemetry)))
    )
  }

  private def resolveName(container: DockerContainerSummary): String =
    container.names
      .headOption
      .map(_.stripPrefix("/"))
      .filter(_.nonEmpty)
      .getOrElse(container.id.take(12))
}

object DockerConnector {
  val ConnectorType = "DOCKER"
  val ExternalType = "CONTAINER"
}
