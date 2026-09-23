package ru.bitec.app.ops
package serialization.resource

import bootstrap.PersistenceModule
import domain.resource.container.ContainerDefinition
import domain.resource.node.NodeDefinition
import munit.FunSuite
import serialization.resource.container.ContainerResourceDataCodec
import serialization.resource.node.NodeResourceDataCodec

final class ResourceDefinitionRegistrySpec extends FunSuite {

  test("registering the same resource type code twice fails instead of overwriting") {
    val result = ResourceDefinitionRegistry.build(
      List(NodeResourceDataCodec, ContainerResourceDataCodec, NodeResourceDataCodec)
    )

    assertEquals(
      result.swap.toOption.getOrElse(fail("Expected a duplicate failure")).getMessage,
      "Duplicate resource definition code: NODE"
    )
  }

  test("resolves a registered code to its definition and leaves unknown codes unresolved") {
    val registry = ResourceDefinitionRegistry
      .build(List(NodeResourceDataCodec, ContainerResourceDataCodec))
      .getOrElse(fail("Expected a registry"))

    assertEquals(registry.definition(NodeDefinition.code), Some(NodeDefinition: domain.resource.ResourceDefinition))
    assertEquals(registry.find(ContainerDefinition.code).map(_.code), Some("CONTAINER"))
    assertEquals(registry.find("UNKNOWN"), None)
  }

  test("the shipped registration contains NODE and CONTAINER and builds successfully") {
    val registry = PersistenceModule.resourceDefinitionRegistry.getOrElse(fail("Expected a registry"))

    assertEquals(registry.codes, Set(NodeDefinition.code, ContainerDefinition.code))
  }
}
