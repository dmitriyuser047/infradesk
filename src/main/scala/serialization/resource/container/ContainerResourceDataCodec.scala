package ru.bitec.app.ops
package serialization.resource.container

import domain.resource.container.{ContainerDefinition, ContainerSpec, ContainerStatus}
import domain.resource.{ResourceSpec, ResourceStatus}
import io.circe.{Decoder, Encoder}
import serialization.resource.ResourceDataCodec
import serialization.resource.container.ContainerResourceJson._

/** CONTAINER serialization and validation. */
object ContainerResourceDataCodec
  extends ResourceDataCodec.Typed[ContainerSpec, ContainerStatus](ContainerDefinition) {

  override protected val expectedTypes: String = "ContainerSpec and ContainerStatus"

  override protected def narrow(
    spec: ResourceSpec,
    status: ResourceStatus
  ): Option[(ContainerSpec, ContainerStatus)] =
    (spec, status) match {
      case (containerSpec: ContainerSpec, containerStatus: ContainerStatus) =>
        Some((containerSpec, containerStatus))
      case _ => None
    }
}

/** The stored JSON shape of a CONTAINER. Unchanged field names: this is existing persisted data. */
private[container] object ContainerResourceJson {

  implicit val containerSpecEncoder: Encoder[ContainerSpec] =
    Encoder.forProduct1("image")(_.image)

  implicit val containerSpecDecoder: Decoder[ContainerSpec] =
    Decoder.forProduct1("image")(ContainerSpec.apply)

  implicit val containerStatusEncoder: Encoder[ContainerStatus] =
    Encoder.forProduct1("state")(_.state)

  implicit val containerStatusDecoder: Decoder[ContainerStatus] =
    Decoder.forProduct1("state")(ContainerStatus.apply)
}
