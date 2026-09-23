package ru.bitec.app.ops
package serialization.resource

import bootstrap.PersistenceModule
import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}
import domain.resource.{ResourceData, ResourceDefinition, ResourceSpec, ResourceStatus}
import io.circe.{Decoder, Encoder}
import munit.FunSuite
import persistence.postgres.ResourceDataJsonCodec
import serialization.resource.TestResourceJson._
import serialization.resource.container.ContainerResourceDataCodec
import serialization.resource.node.NodeResourceDataCodec

/** Architecture acceptance test: a resource type that exists only in test sources is stored and
  * read back by the production generic codec. Adding it required a definition, a codec and one
  * registration — no change to `ResourceDataJsonCodec` or to `PostgresResourceRepository`.
  */
final class ResourceTypeExtensionSpec extends FunSuite {

  private val registry = ResourceDefinitionRegistry
    .build(PersistenceModule.resourceTypeCodecs :+ TestResourceDataCodec)
    .getOrElse(fail("Expected a registry"))

  private val codec = new ResourceDataJsonCodec(registry)

  private val testData = ResourceData(Some(TestSpec("widget", Some(3))), Some(TestStatus(ready = true)))

  private def failure(result: Either[IllegalArgumentException, _]): String =
    result.swap.toOption.getOrElse(fail("Expected a failure")).getMessage

  test("a test-only resource type round-trips through the generic codec") {
    val stored = codec.encode(TestDefinition.code, testData).getOrElse(fail("Encode failed"))

    assertEquals(stored.specJson, """{"label":"widget","size":3}""")
    assertEquals(stored.statusJson, """{"ready":true}""")
    assertEquals(codec.decode(TestDefinition.code, stored.specJson, stored.statusJson), Right(testData))
  }

  test("the new type inherits empty, half-filled and mismatch rules without extra code") {
    assertEquals(codec.encode(TestDefinition.code, ResourceData.empty).map(_.specJson), Right("{}"))
    assertEquals(codec.decode(TestDefinition.code, "{}", "{}"), Right(ResourceData.empty))
    assertEquals(
      failure(codec.encode(TestDefinition.code, testData.copy(status = None))),
      "TEST_RESOURCE requires TestSpec and TestStatus, got TestSpec and empty"
    )
    assertEquals(
      failure(codec.encode(
        TestDefinition.code,
        ResourceData(Some(NodeSpec("node-1", None, None, None, None)), Some(NodeStatus(true, None, None, None)))
      )),
      "TEST_RESOURCE requires TestSpec and TestStatus, got NodeSpec and NodeStatus"
    )
    assertEquals(
      failure(codec.decode(TestDefinition.code, """{"label":"widget","size":3}""", "{}")),
      "TEST_RESOURCE requires both non-empty spec and non-empty status"
    )
  }

  test("registering the new type does not disturb the shipped types") {
    val node = ResourceData(
      Some(NodeSpec("node-1", Some("Linux"), None, Some(2), Some(4096))),
      Some(NodeStatus(true, None, None, Some(10)))
    )
    val stored = codec.encode(NodeDefinition.code, node).getOrElse(fail("Encode failed"))

    assertEquals(codec.decode(NodeDefinition.code, stored.specJson, stored.statusJson), Right(node))
    assertEquals(registry.codes, Set("NODE", "CONTAINER", "TEST_RESOURCE"))
  }

  test("the shipped registry does not know the test-only type") {
    val shipped = new ResourceDataJsonCodec(
      ResourceDefinitionRegistry
        .build(List(NodeResourceDataCodec, ContainerResourceDataCodec))
        .getOrElse(fail("Expected a registry"))
    )

    assertEquals(
      failure(shipped.encode(TestDefinition.code, testData)),
      "Resource type 'TEST_RESOURCE' has non-empty ResourceData but no JSON codec"
    )
    assertEquals(shipped.encode(TestDefinition.code, ResourceData.empty).map(_.specJson), Right("{}"))
  }
}

final case class TestSpec(label: String, size: Option[Int]) extends ResourceSpec

final case class TestStatus(ready: Boolean) extends ResourceStatus

object TestDefinition extends ResourceDefinition {
  override type Spec = TestSpec
  override type Status = TestStatus

  override val code: String = "TEST_RESOURCE"
}

object TestResourceDataCodec extends ResourceDataCodec.Typed[TestSpec, TestStatus](TestDefinition) {

  override protected val expectedTypes: String = "TestSpec and TestStatus"

  override protected def narrow(spec: ResourceSpec, status: ResourceStatus): Option[(TestSpec, TestStatus)] =
    (spec, status) match {
      case (testSpec: TestSpec, testStatus: TestStatus) => Some((testSpec, testStatus))
      case _ => None
    }
}

object TestResourceJson {
  implicit val testSpecEncoder: Encoder[TestSpec] =
    Encoder.forProduct2("label", "size")(spec => (spec.label, spec.size))

  implicit val testSpecDecoder: Decoder[TestSpec] =
    Decoder.forProduct2("label", "size")(TestSpec.apply)

  implicit val testStatusEncoder: Encoder[TestStatus] =
    Encoder.forProduct1("ready")(_.ready)

  implicit val testStatusDecoder: Decoder[TestStatus] =
    Decoder.forProduct1("ready")(TestStatus.apply)
}
