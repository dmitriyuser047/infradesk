package ru.bitec.app.ops
package integration.ssh.node

import domain.metric.{MetricCode, ResourceTelemetry, TelemetryDevice}
import domain.resource.{ResourceData}
import domain.resource.node.{NodeSpec, NodeStatus}
import io.circe.syntax._
import munit.FunSuite
import serialization.resource.ResourceTelemetryJson._
import serialization.resource.node.NodeResourceDataCodec

final class NodeTelemetrySpec extends FunSuite {
  private val telemetry = ResourceTelemetry(Map(
    MetricCode.DiskUsagePercent.code -> BigDecimal(91),
    MetricCode.DiskFreeBytes.code -> BigDecimal(4096),
    MetricCode.LoadAverage1.code -> BigDecimal(125)
  ), List(TelemetryDevice("filesystem", "/var", Map("INODE_USAGE_PERCENT" -> BigDecimal(96)))))

  test("SSH parses device readings and unbounded non-percentage values") {
    val inventory = NodeInventoryParser.parse("hostname\tnode\ntelemetry\t" + telemetry.asJson.noSpaces).toOption.get
    assertEquals(inventory.telemetry, telemetry)
  }

  test("legacy node JSON remains readable and new telemetry round trips") {
    val legacy = NodeResourceDataCodec.decode(
      io.circe.parser.parse("""{"hostname":"node","operatingSystem":null,"distribution":null,"kernelVersion":null,"architecture":null,"cpuModel":null,"cpuCores":null,"memoryMb":null}""").toOption.get,
      io.circe.parser.parse("""{"online":true,"cpuUsagePercent":null,"memoryUsagePercent":null,"uptimeSeconds":null}""").toOption.get
    ).toOption.get
    assertEquals(legacy.status.collect { case v: NodeStatus => v.telemetry }, Some(ResourceTelemetry()))
    val data = ResourceData(Some(NodeSpec("node", None, None, None, None)),
      Some(NodeStatus(true, None, None, None, telemetry)))
    val encoded = NodeResourceDataCodec.encode(data).toOption.get
    assertEquals(NodeResourceDataCodec.decode(io.circe.parser.parse(encoded.specJson).toOption.get,
      io.circe.parser.parse(encoded.statusJson).toOption.get), Right(data))
  }

  test("invalid optional SSH telemetry is absent while malformed stored telemetry fails closed") {
    val invalid = """{"metrics":{"DISK_USAGE_PERCENT":101},"devices":[]}"""
    assert(io.circe.parser.decode[ResourceTelemetry](invalid).isLeft)
    val inventory = NodeInventoryParser.parse("hostname\tnode\ntelemetry\t" + invalid).toOption.get
    assertEquals(inventory.telemetry, ResourceTelemetry())
    assert(io.circe.parser.decode[ResourceTelemetry]("""{"metrics":{"NOT_A_METRIC":0},"devices":[]}""").isLeft)
  }

  test("service hostname cannot introduce a shell command") {
    val host = "example.test'; touch /tmp/unsafe; #"
    val command = SshTelemetryCommand.commandFor(host)
    assert(!command.contains(host))
    assert(command.contains(java.util.Base64.getEncoder.encodeToString(host.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
  }
}
