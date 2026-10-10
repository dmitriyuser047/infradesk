package ru.bitec.app.ops
package serialization.resource.node

import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}
import domain.resource.{ResourceSpec, ResourceStatus}
import io.circe.{Decoder, Encoder}
import serialization.resource.ResourceTelemetryJson._
import domain.metric.ResourceTelemetry
import serialization.resource.ResourceDataCodec
import serialization.resource.node.NodeResourceJson._

/** NODE serialization and validation: everything the generic codec used to know about nodes. */
object NodeResourceDataCodec extends ResourceDataCodec.Typed[NodeSpec, NodeStatus](NodeDefinition) {

  override protected val expectedTypes: String = "NodeSpec and NodeStatus"

  override protected def narrow(
    spec: ResourceSpec,
    status: ResourceStatus
  ): Option[(NodeSpec, NodeStatus)] =
    (spec, status) match {
      case (nodeSpec: NodeSpec, nodeStatus: NodeStatus) => Some((nodeSpec, nodeStatus))
      case _ => None
    }
}

/** The stored JSON shape of a NODE. Unchanged field names: this is existing persisted data. */
private[node] object NodeResourceJson {

  implicit val nodeSpecEncoder: Encoder[NodeSpec] =
    Encoder.forProduct8(
      "hostname", "operatingSystem", "distribution", "kernelVersion",
      "architecture", "cpuModel", "cpuCores", "memoryMb"
    )(node =>
      (node.hostname, node.operatingSystem, node.distribution, node.kernelVersion,
        node.architecture, node.cpuModel, node.cpuCores, node.memoryMb)
    )

  implicit val nodeSpecDecoder: Decoder[NodeSpec] =
    Decoder.forProduct8(
      "hostname", "operatingSystem", "distribution", "kernelVersion",
      "architecture", "cpuModel", "cpuCores", "memoryMb"
    )(NodeSpec.apply)

  implicit val nodeStatusEncoder: Encoder[NodeStatus] =
    Encoder.forProduct5("online", "cpuUsagePercent", "memoryUsagePercent", "uptimeSeconds", "telemetry")(node =>
      (node.online, node.cpuUsagePercent, node.memoryUsagePercent, node.uptimeSeconds, node.telemetry)
    )

  implicit val nodeStatusDecoder: Decoder[NodeStatus] =
    Decoder.instance { c =>
      for {
        online <- c.get[Boolean]("online")
        cpu <- c.get[Option[BigDecimal]]("cpuUsagePercent")
        memory <- c.get[Option[BigDecimal]]("memoryUsagePercent")
        uptime <- c.get[Option[Long]]("uptimeSeconds")
        telemetry <- c.get[Option[ResourceTelemetry]]("telemetry")
      } yield NodeStatus(online, cpu, memory, uptime, telemetry.getOrElse(ResourceTelemetry()))
    }
}
