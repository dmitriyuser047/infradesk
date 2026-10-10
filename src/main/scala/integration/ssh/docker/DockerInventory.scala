package ru.bitec.app.ops
package integration.ssh.docker

import application.discovery.{DiscoveredExternalIdentity, DiscoveredResource}
import domain.resource.ResourceData
import domain.resource.container.{ContainerDefinition, ContainerSpec, ContainerStatus}
import integration.ssh.SshConnector

final case class DockerContainerInventory(id: String, name: String, image: Option[String], state: Option[String], telemetry: domain.metric.ResourceTelemetry = domain.metric.ResourceTelemetry()) {
  def toDiscoveredResource: DiscoveredResource = DiscoveredResource(
    externalType = SshConnector.ContainerExternalType,
    externalId = id,
    resourceTypeCode = ContainerDefinition.code,
    code = name,
    name = name,
    parentExternalIdentity = Some(DiscoveredExternalIdentity(
      SshConnector.NodeExternalType,
      SshConnector.NodeExternalId
    )),
    data = ResourceData(
      spec = Some(ContainerSpec(image)),
      status = Some(ContainerStatus(state, telemetry))
    )
  )
}

sealed trait DockerInventoryResult
object DockerInventoryResult {
  final case class Available(containers: List[DockerContainerInventory]) extends DockerInventoryResult
  final case class Unavailable(exitCode: Int) extends DockerInventoryResult
}
