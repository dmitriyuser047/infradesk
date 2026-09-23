package ru.bitec.app.ops
package serialization.resource

import domain.resource.ResourceDefinition

/** The resource types this runtime can interpret, keyed by their stable resource type code.
  *
  * Built once in the composition root and injected where generic machinery has to resolve
  * concrete type behaviour. It is not a global object and not a service locator.
  */
final class ResourceDefinitionRegistry private (codecs: Map[String, ResourceDataCodec]) {

  def find(code: String): Option[ResourceDataCodec] = codecs.get(code)

  def definition(code: String): Option[ResourceDefinition] = find(code).map(_.definition)

  def codes: Set[String] = codecs.keySet
}

object ResourceDefinitionRegistry {

  /** Fails instead of silently letting the last registration of a code win. */
  def build(codecs: List[ResourceDataCodec]): Either[IllegalArgumentException, ResourceDefinitionRegistry] = {
    val duplicates = codecs.groupBy(_.code).collect { case (code, registered) if registered.size > 1 => code }
    if (duplicates.nonEmpty)
      Left(new IllegalArgumentException(
        s"Duplicate resource definition code: ${duplicates.toList.sorted.mkString(", ")}"
      ))
    else
      Right(new ResourceDefinitionRegistry(codecs.map(codec => codec.code -> codec).toMap))
  }
}
