package ru.bitec.app.ops
package domain.resource.container

import domain.resource.{ResourceDefinition, ResourceSpec, ResourceStatus}

final case class ContainerSpec(
                                image: Option[String]
                              ) extends ResourceSpec

final case class ContainerStatus(
                                  state: Option[String],
  telemetry: domain.metric.ResourceTelemetry = domain.metric.ResourceTelemetry()
                                ) extends ResourceStatus

object ContainerDefinition extends ResourceDefinition {
  override type Spec = ContainerSpec
  override type Status = ContainerStatus

  override val code: String = "CONTAINER"
}