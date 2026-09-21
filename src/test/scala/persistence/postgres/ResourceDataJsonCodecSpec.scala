package ru.bitec.app.ops
package persistence.postgres

import domain.resource.ResourceData
import domain.resource.container.{ContainerDefinition, ContainerSpec, ContainerStatus}
import munit.FunSuite

final class ResourceDataJsonCodecSpec extends FunSuite {

  private val codec = new ResourceDataJsonCodec

  test("round-trips ContainerSpec and ContainerStatus") {
    val data = ResourceData(
      Some(ContainerSpec(Some("backend:2.0"))),
      Some(ContainerStatus(Some("exited")))
    )

    val encoded = codec.encode(ContainerDefinition.code, data).toOption.getOrElse(fail("Encode failed"))
    val decoded = codec
      .decode(ContainerDefinition.code, encoded.specJson, encoded.statusJson)
      .toOption
      .getOrElse(fail("Decode failed"))

    assertEquals(decoded, data)
  }

  test("rejects ResourceData that does not match CONTAINER") {
    val mismatched = ResourceData(
      Some(ContainerSpec(Some("backend:2.0"))),
      None
    )

    val result = codec.encode(ContainerDefinition.code, mismatched)

    assert(result.isLeft)
    assert(result.swap.toOption.exists(_.getMessage.contains("CONTAINER requires")))
  }

  test("rejects non-empty JSON for an unsupported resource type") {
    val result = codec.decode("NODE", "{\"hostname\":\"node-1\"}", "{}")

    assert(result.isLeft)
    assert(result.swap.toOption.exists(_.getMessage.contains("NODE")))
  }
}
