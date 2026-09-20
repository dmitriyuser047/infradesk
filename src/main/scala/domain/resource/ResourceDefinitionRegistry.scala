package ru.bitec.app.ops
package domain.resource

import domain.resource.node.NodeDefinition
import domain.resource.container.ContainerDefinition
import domain.resource.node.NodeDefinition

object ResourceDefinitionRegistry {


  private val definitions: Map[String, ResourceDefinition] =
    Seq(
      NodeDefinition,
      ContainerDefinition
    )
      .map(definition => definition.code -> definition)
      .toMap

  def find(code: String): Option[ResourceDefinition] = {
    definitions.get(code)
  }

  def find(resourceType: ResourceType): Option[ResourceDefinition] = {
    find(resourceType.code)
  }



}
