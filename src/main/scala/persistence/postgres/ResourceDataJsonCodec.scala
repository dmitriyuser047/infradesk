package ru.bitec.app.ops
package persistence.postgres

import domain.resource.{ResourceData, ResourceSpec, ResourceStatus}
import domain.resource.container.{ContainerDefinition, ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}

import io.circe.{Decoder, Encoder, Json}
import io.circe.parser
import io.circe.syntax._

final case class EncodedResourceData(specJson: String, statusJson: String)

final class ResourceDataJsonCodec {

  import ResourceDataJsonCodec._

  def encode(resourceTypeCode: String, data: ResourceData): Either[IllegalArgumentException, EncodedResourceData] =
    resourceTypeCode match {
      case ContainerDefinition.code =>
        encodeContainer(data)

      case code if code == NodeDefinition.code =>
        encodeNode(data)

      case unsupportedTypeCode =>
        if (data == ResourceData.empty)
          Right(EncodedResourceData(EmptyJson, EmptyJson))
        else
          Left(new IllegalArgumentException(
            s"Resource type '$unsupportedTypeCode' has non-empty ResourceData but no JSON codec"
          ))
    }

  def decode(
              resourceTypeCode: String,
              specJson: String,
              statusJson: String
            ): Either[IllegalArgumentException, ResourceData] =
    for {
      spec <- parseJson(resourceTypeCode, "spec", specJson)
      status <- parseJson(resourceTypeCode, "status", statusJson)
      data <- resourceTypeCode match {
        case ContainerDefinition.code =>
          decodeContainer(spec, status)

        case code if code == NodeDefinition.code =>
          decodeNode(spec, status)

        case unsupportedTypeCode =>
          if (isEmptyObject(spec) && isEmptyObject(status))
            Right(ResourceData.empty)
          else
            Left(new IllegalArgumentException(
              s"Resource type '$unsupportedTypeCode' has non-empty spec/status but no JSON codec"
            ))
      }
    } yield data

  private def encodeContainer(data: ResourceData): Either[IllegalArgumentException, EncodedResourceData] =
    (data.spec, data.status) match {
      case (None, None) =>
        Right(EncodedResourceData(EmptyJson, EmptyJson))

      case (Some(spec: ContainerSpec), Some(status: ContainerStatus)) =>
        Right(EncodedResourceData(spec.asJson.noSpaces, status.asJson.noSpaces))

      case (spec, status) =>
        Left(new IllegalArgumentException(
          s"CONTAINER requires ContainerSpec and ContainerStatus, got ${typeName(spec)} and ${typeName(status)}"
        ))
    }

  private def decodeContainer(
                              spec: Json,
                              status: Json
                            ): Either[IllegalArgumentException, ResourceData] =
    if (isEmptyObject(spec) && isEmptyObject(status)) {
      Right(ResourceData.empty)
    } else if (isEmptyObject(spec) || isEmptyObject(status)) {
      Left(new IllegalArgumentException(
        "CONTAINER requires both non-empty spec and non-empty status"
      ))
    } else {
      for {
        containerSpec <- spec.as[ContainerSpec].left.map(error =>
          new IllegalArgumentException(s"Invalid CONTAINER spec JSON: ${error.getMessage}", error)
        )
        containerStatus <- status.as[ContainerStatus].left.map(error =>
          new IllegalArgumentException(s"Invalid CONTAINER status JSON: ${error.getMessage}", error)
        )
      } yield ResourceData(Some(containerSpec), Some(containerStatus))
    }

  private def encodeNode(data: ResourceData): Either[IllegalArgumentException, EncodedResourceData] =
    (data.spec, data.status) match {
      case (None, None) =>
        Right(EncodedResourceData(EmptyJson, EmptyJson))

      case (Some(spec: NodeSpec), Some(status: NodeStatus)) =>
        Right(EncodedResourceData(spec.asJson.noSpaces, status.asJson.noSpaces))

      case (spec, status) =>
        Left(new IllegalArgumentException(
          s"NODE requires NodeSpec and NodeStatus, got ${typeName(spec)} and ${typeName(status)}"
        ))
    }

  private def decodeNode(
                         spec: Json,
                         status: Json
                       ): Either[IllegalArgumentException, ResourceData] =
    if (isEmptyObject(spec) && isEmptyObject(status)) {
      Right(ResourceData.empty)
    } else if (isEmptyObject(spec) || isEmptyObject(status)) {
      Left(new IllegalArgumentException(
        "NODE requires both non-empty spec and non-empty status"
      ))
    } else {
      for {
        nodeSpec <- spec.as[NodeSpec].left.map(error =>
          new IllegalArgumentException(s"Invalid NODE spec JSON: ${error.getMessage}", error)
        )
        nodeStatus <- status.as[NodeStatus].left.map(error =>
          new IllegalArgumentException(s"Invalid NODE status JSON: ${error.getMessage}", error)
        )
      } yield ResourceData(Some(nodeSpec), Some(nodeStatus))
    }

  private def parseJson(
                        resourceTypeCode: String,
                        field: String,
                        value: String
                      ): Either[IllegalArgumentException, Json] =
    parser.parse(value).left.map(error =>
      new IllegalArgumentException(
        s"Resource type '$resourceTypeCode' has invalid $field JSON: ${error.getMessage}",
        error
      )
    )

  private def isEmptyObject(json: Json): Boolean =
    json.asObject.exists(_.isEmpty)

  private def typeName(value: Option[_]): String =
    value.fold("empty")(_.getClass.getSimpleName)
}

object ResourceDataJsonCodec {
  private val EmptyJson = "{}"

  implicit val containerSpecEncoder: Encoder[ContainerSpec] =
    Encoder.forProduct1("image")(_.image)

  implicit val containerSpecDecoder: Decoder[ContainerSpec] =
    Decoder.forProduct1("image")(ContainerSpec.apply)

  implicit val containerStatusEncoder: Encoder[ContainerStatus] =
    Encoder.forProduct1("state")(_.state)

  implicit val containerStatusDecoder: Decoder[ContainerStatus] =
    Decoder.forProduct1("state")(ContainerStatus.apply)

  implicit val nodeSpecEncoder: Encoder[NodeSpec] =
    Encoder.forProduct5("hostname", "operatingSystem", "architecture", "cpuCores", "memoryMb")(node =>
      (node.hostname, node.operatingSystem, node.architecture, node.cpuCores, node.memoryMb)
    )

  implicit val nodeSpecDecoder: Decoder[NodeSpec] =
    Decoder.forProduct5("hostname", "operatingSystem", "architecture", "cpuCores", "memoryMb")(NodeSpec.apply)

  implicit val nodeStatusEncoder: Encoder[NodeStatus] =
    Encoder.forProduct4("online", "cpuUsagePercent", "memoryUsagePercent", "uptimeSeconds")(node =>
      (node.online, node.cpuUsagePercent, node.memoryUsagePercent, node.uptimeSeconds)
    )

  implicit val nodeStatusDecoder: Decoder[NodeStatus] =
    Decoder.forProduct4("online", "cpuUsagePercent", "memoryUsagePercent", "uptimeSeconds")(NodeStatus.apply)
}
