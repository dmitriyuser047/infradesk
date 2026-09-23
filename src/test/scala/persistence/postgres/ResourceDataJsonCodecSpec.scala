package ru.bitec.app.ops
package persistence.postgres

import domain.resource.ResourceData
import domain.resource.container.{ContainerDefinition, ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}
import munit.FunSuite

final class ResourceDataJsonCodecSpec extends FunSuite {

  private val codec = ProductionResourceCodec.codec

  private val nodeData = ResourceData(
    Some(NodeSpec("node-1", Some("Linux"), Some("x86_64"), Some(4), Some(8192))),
    Some(NodeStatus(true, Some(BigDecimal("12.5")), Some(BigDecimal("37.5")), Some(123456)))
  )

  private val containerData = ResourceData(
    Some(ContainerSpec(Some("backend:2.0"))),
    Some(ContainerStatus(Some("exited")))
  )

  private def encoded(typeCode: String, data: ResourceData) =
    codec.encode(typeCode, data).toOption.getOrElse(fail("Encode failed"))

  private def failure(result: Either[IllegalArgumentException, _]): String =
    result.swap.toOption.getOrElse(fail("Expected a failure")).getMessage

  test("round-trips NodeSpec and NodeStatus") {
    val stored = encoded(NodeDefinition.code, nodeData)
    val decoded = codec.decode(NodeDefinition.code, stored.specJson, stored.statusJson)

    assertEquals(decoded, Right(nodeData))
  }

  test("round-trips ContainerSpec and ContainerStatus") {
    val stored = encoded(ContainerDefinition.code, containerData)
    val decoded = codec.decode(ContainerDefinition.code, stored.specJson, stored.statusJson)

    assertEquals(decoded, Right(containerData))
  }

  test("round-trips empty resource data as empty JSON objects") {
    List(NodeDefinition.code, ContainerDefinition.code, "UNKNOWN").foreach { typeCode =>
      val stored = encoded(typeCode, ResourceData.empty)

      assertEquals(stored.specJson, "{}")
      assertEquals(stored.statusJson, "{}")
      assertEquals(codec.decode(typeCode, stored.specJson, stored.statusJson), Right(ResourceData.empty))
    }
  }

  test("rejects data of another resource type") {
    assertEquals(
      failure(codec.encode(NodeDefinition.code, containerData)),
      "NODE requires NodeSpec and NodeStatus, got ContainerSpec and ContainerStatus"
    )
    assertEquals(
      failure(codec.encode(ContainerDefinition.code, nodeData)),
      "CONTAINER requires ContainerSpec and ContainerStatus, got NodeSpec and NodeStatus"
    )
  }

  test("rejects half-filled resource data on encode") {
    assert(failure(codec.encode(NodeDefinition.code, nodeData.copy(status = None)))
      .contains("NODE requires NodeSpec and NodeStatus"))
    assert(failure(codec.encode(NodeDefinition.code, nodeData.copy(spec = None)))
      .contains("NODE requires NodeSpec and NodeStatus"))
    assert(failure(codec.encode(ContainerDefinition.code, containerData.copy(status = None)))
      .contains("CONTAINER requires ContainerSpec and ContainerStatus"))
    assert(failure(codec.encode(ContainerDefinition.code, containerData.copy(spec = None)))
      .contains("CONTAINER requires ContainerSpec and ContainerStatus"))
  }

  test("rejects half-filled stored payload on decode") {
    val node = encoded(NodeDefinition.code, nodeData)
    val container = encoded(ContainerDefinition.code, containerData)

    assertEquals(
      failure(codec.decode(NodeDefinition.code, node.specJson, "{}")),
      "NODE requires both non-empty spec and non-empty status"
    )
    assertEquals(
      failure(codec.decode(NodeDefinition.code, "{}", node.statusJson)),
      "NODE requires both non-empty spec and non-empty status"
    )
    assertEquals(
      failure(codec.decode(ContainerDefinition.code, container.specJson, "{}")),
      "CONTAINER requires both non-empty spec and non-empty status"
    )
  }

  test("reports malformed JSON with the resource type code and the field") {
    assert(failure(codec.decode(NodeDefinition.code, "{oops", "{}")).startsWith(
      "Resource type 'NODE' has invalid spec JSON:"
    ))
    assert(failure(codec.decode(NodeDefinition.code, "{}", "{oops")).startsWith(
      "Resource type 'NODE' has invalid status JSON:"
    ))
  }

  test("reports a spec that does not match the resource type schema") {
    val status = encoded(NodeDefinition.code, nodeData).statusJson

    assert(failure(codec.decode(NodeDefinition.code, """{"image":"backend:2.0"}""", status))
      .startsWith("Invalid NODE spec JSON:"))
    assert(failure(codec.decode(NodeDefinition.code, """{"hostname":42}""", status))
      .startsWith("Invalid NODE spec JSON:"))
  }

  test("rejects non-empty payload for an unregistered resource type") {
    assertEquals(
      failure(codec.decode("UNKNOWN", """{"hostname":"node-1"}""", "{}")),
      "Resource type 'UNKNOWN' has non-empty spec/status but no JSON codec"
    )
    assertEquals(
      failure(codec.decode("UNKNOWN", "{}", """{"online":true}""")),
      "Resource type 'UNKNOWN' has non-empty spec/status but no JSON codec"
    )
    assertEquals(
      failure(codec.encode("UNKNOWN", nodeData)),
      "Resource type 'UNKNOWN' has non-empty ResourceData but no JSON codec"
    )
  }
}
