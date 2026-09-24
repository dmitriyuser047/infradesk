package ru.bitec.app.ops
package domain.resource.node

import domain.resource.ResourceSpec

final case class NodeSpec(
                           hostname: String,
                           operatingSystem: Option[String],
                           distribution: Option[String],
                           kernelVersion: Option[String],
                           architecture: Option[String],
                           cpuModel: Option[String],
                           cpuCores: Option[Int],
                           memoryMb: Option[Long]
                         ) extends ResourceSpec

object NodeSpec {
  /** Source-compatible constructor for inventory persisted before Stage 11. */
  def apply(
    hostname: String,
    operatingSystem: Option[String],
    architecture: Option[String],
    cpuCores: Option[Int],
    memoryMb: Option[Long]
  ): NodeSpec =
    new NodeSpec(hostname, operatingSystem, None, None, architecture, None, cpuCores, memoryMb)
}
