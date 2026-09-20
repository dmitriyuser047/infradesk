package ru.bitec.app.ops
package domain.resource.node

import domain.resource.ResourceSpec

final case class NodeSpec(
                           hostname: String,
                           operatingSystem: Option[String],
                           architecture: Option[String],
                           cpuCores: Option[Int],
                           memoryMb: Option[Long]
                         ) extends ResourceSpec
