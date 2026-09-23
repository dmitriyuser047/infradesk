package ru.bitec.app.ops
package persistence.postgres

import domain.resource.ResourceData
import io.circe.{Json, parser}
import serialization.resource.{EncodedResourceData, ResourceDefinitionRegistry}

/** Generic resource payload codec: parses the stored JSON, resolves the resource type through the
  * registry and delegates to that type's codec.
  *
  * It knows no concrete resource type. A type the registry does not know is readable and
  * writable only while its payload is empty, so a row written by a newer version of the
  * application is never silently reinterpreted here.
  */
final class ResourceDataJsonCodec(registry: ResourceDefinitionRegistry) {

  def encode(
              resourceTypeCode: String,
              data: ResourceData
            ): Either[IllegalArgumentException, EncodedResourceData] =
    registry.find(resourceTypeCode) match {
      case Some(codec) =>
        codec.encode(data)

      case None =>
        if (data == ResourceData.empty)
          Right(EncodedResourceData.empty)
        else
          Left(new IllegalArgumentException(
            s"Resource type '$resourceTypeCode' has non-empty ResourceData but no JSON codec"
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
      data <- registry.find(resourceTypeCode) match {
        case Some(codec) =>
          codec.decode(spec, status)

        case None =>
          if (isEmptyObject(spec) && isEmptyObject(status))
            Right(ResourceData.empty)
          else
            Left(new IllegalArgumentException(
              s"Resource type '$resourceTypeCode' has non-empty spec/status but no JSON codec"
            ))
      }
    } yield data

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
}
