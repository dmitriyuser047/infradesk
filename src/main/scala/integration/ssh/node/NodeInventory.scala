package ru.bitec.app.ops
package integration.ssh.node

import application.discovery.DiscoveredResource
import domain.connection.Connection
import domain.resource.ResourceData
import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}
import integration.ssh.SshConnector

final case class NodeInventory(
  hostname: String,
  operatingSystem: Option[String],
  distribution: Option[String],
  kernelVersion: Option[String],
  architecture: Option[String],
  cpuModel: Option[String],
  cpuCores: Option[Int],
  memoryMb: Option[Long],
  cpuUsagePercent: Option[BigDecimal],
  memoryUsagePercent: Option[BigDecimal],
  uptimeSeconds: Option[Long],
  telemetry: domain.metric.ResourceTelemetry = domain.metric.ResourceTelemetry()
) {
  def toDiscoveredResource(connection: Connection): DiscoveredResource =
    DiscoveredResource(
      externalType = SshConnector.NodeExternalType,
      externalId = SshConnector.NodeExternalId,
      resourceTypeCode = NodeDefinition.code,
      code = connection.code,
      name = hostname,
      data = ResourceData(
        spec = Some(NodeSpec(
          hostname = hostname,
          operatingSystem = operatingSystem,
          architecture = architecture,
          cpuCores = cpuCores,
          memoryMb = memoryMb,
          distribution = distribution,
          kernelVersion = kernelVersion,
          cpuModel = cpuModel
        )),
        status = Some(NodeStatus(
          online = true,
          cpuUsagePercent = cpuUsagePercent,
          memoryUsagePercent = memoryUsagePercent,
          uptimeSeconds = uptimeSeconds,
          telemetry = telemetry.copy(devices = telemetry.devices.filterNot(_.kind == "container"))
        ))
      )
    )
}
