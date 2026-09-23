package ru.bitec.app.ops
package serialization.resource

import domain.resource.{ResourceData, ResourceDefinition, ResourceSpec, ResourceStatus}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

final case class EncodedResourceData(specJson: String, statusJson: String)

object EncodedResourceData {
  private[resource] val EmptyJson = "{}"

  val empty: EncodedResourceData = EncodedResourceData(EmptyJson, EmptyJson)
}

/** What a resource type contributes to generic persistence: how its spec and status are written
  * to and read back from JSON, and which payloads it rejects.
  *
  * This is the erased face used by the registry and by the generic codec. Concrete types
  * implement it through [[ResourceDataCodec.Typed]], which keeps the definition's `Spec` and
  * `Status` types.
  */
trait ResourceDataCodec {

  def definition: ResourceDefinition

  final def code: String = definition.code

  def encode(data: ResourceData): Either[IllegalArgumentException, EncodedResourceData]

  def decode(specJson: Json, statusJson: Json): Either[IllegalArgumentException, ResourceData]
}

object ResourceDataCodec {

  /** Base class for a concrete resource type codec.
    *
    * The definition is required to expose exactly `S` and `T`, so `NodeResourceDataCodec` can
    * only be built from a definition whose `Spec` is `NodeSpec` and whose `Status` is
    * `NodeStatus`. Empty, half-filled and mismatched payload rules live here once; a concrete
    * type only supplies its JSON instances and its runtime narrowing.
    */
  abstract class Typed[S <: ResourceSpec, T <: ResourceStatus](
    override val definition: ResourceDefinition { type Spec = S; type Status = T }
  )(implicit
    specEncoder: Encoder[S],
    specDecoder: Decoder[S],
    statusEncoder: Encoder[T],
    statusDecoder: Decoder[T]
  ) extends ResourceDataCodec {

    /** Spec and status type names for error messages, for example "NodeSpec and NodeStatus". */
    protected def expectedTypes: String

    /** Runtime narrowing of untyped domain data to this type, by pattern match, never by cast. */
    protected def narrow(spec: ResourceSpec, status: ResourceStatus): Option[(S, T)]

    final override def encode(data: ResourceData): Either[IllegalArgumentException, EncodedResourceData] =
      (data.spec, data.status) match {
        case (None, None) =>
          Right(EncodedResourceData.empty)

        case (Some(spec), Some(status)) =>
          narrow(spec, status)
            .toRight(mismatch(data.spec, data.status))
            .map { case (typedSpec, typedStatus) =>
              EncodedResourceData(typedSpec.asJson.noSpaces, typedStatus.asJson.noSpaces)
            }

        case _ =>
          Left(mismatch(data.spec, data.status))
      }

    final override def decode(specJson: Json, statusJson: Json): Either[IllegalArgumentException, ResourceData] =
      if (isEmptyObject(specJson) && isEmptyObject(statusJson))
        Right(ResourceData.empty)
      else if (isEmptyObject(specJson) || isEmptyObject(statusJson))
        Left(new IllegalArgumentException(s"$code requires both non-empty spec and non-empty status"))
      else
        for {
          spec <- specJson.as[S].left.map(error =>
            new IllegalArgumentException(s"Invalid $code spec JSON: ${error.getMessage}", error)
          )
          status <- statusJson.as[T].left.map(error =>
            new IllegalArgumentException(s"Invalid $code status JSON: ${error.getMessage}", error)
          )
        } yield ResourceData(Some(spec), Some(status))

    private def mismatch(spec: Option[ResourceSpec], status: Option[ResourceStatus]): IllegalArgumentException =
      new IllegalArgumentException(
        s"$code requires $expectedTypes, got ${typeName(spec)} and ${typeName(status)}"
      )

    private def isEmptyObject(json: Json): Boolean = json.asObject.exists(_.isEmpty)

    private def typeName(value: Option[Any]): String = value.fold("empty")(_.getClass.getSimpleName)
  }
}
