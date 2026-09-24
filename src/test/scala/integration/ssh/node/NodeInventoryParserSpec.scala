package ru.bitec.app.ops
package integration.ssh.node

import munit.FunSuite

final class NodeInventoryParserSpec extends FunSuite {
  test("parses a full Linux inventory response") {
    val result = parse(
      "hostname\tnode-1\noperating_system\tLinux\ndistribution\tUbuntu 24.04 LTS\n" +
        "kernel_version\t6.8.0-79-generic\narchitecture\tx86_64\ncpu_model\tAMD EPYC\n" +
        "cpu_cores\t8\nmemory_mb\t16384\ncpu_usage_percent\t12.5\n" +
        "memory_usage_percent\t37.25\nuptime_seconds\t123456\n"
    )

    assertEquals(result.hostname, "node-1")
    assertEquals(result.operatingSystem, Some("Linux"))
    assertEquals(result.distribution, Some("Ubuntu 24.04 LTS"))
    assertEquals(result.kernelVersion, Some("6.8.0-79-generic"))
    assertEquals(result.architecture, Some("x86_64"))
    assertEquals(result.cpuModel, Some("AMD EPYC"))
    assertEquals(result.cpuCores, Some(8))
    assertEquals(result.memoryMb, Some(16384L))
    assertEquals(result.cpuUsagePercent, Some(BigDecimal("12.5")))
    assertEquals(result.memoryUsagePercent, Some(BigDecimal("37.25")))
    assertEquals(result.uptimeSeconds, Some(123456L))
  }

  test("keeps a node valid when distribution or cpu model is unavailable") {
    val result = parse("hostname\tnode-1\ndistribution\t\ncpu_model\t   \n")
    assertEquals(result.distribution, None)
    assertEquals(result.cpuModel, None)
  }

  test("turns malformed optional numbers and percentages into None") {
    val result = parse(
      "hostname\tnode-1\ncpu_cores\tbroken\nmemory_mb\tbroken\n" +
        "cpu_usage_percent\tbroken\nmemory_usage_percent\tNaN\nuptime_seconds\tbroken\n"
    )
    assertEquals(result.cpuCores, None)
    assertEquals(result.memoryMb, None)
    assertEquals(result.cpuUsagePercent, None)
    assertEquals(result.memoryUsagePercent, None)
    assertEquals(result.uptimeSeconds, None)
  }

  test("accepts only percentages in the inclusive 0 to 100 range") {
    assertEquals(parse("hostname\tn\ncpu_usage_percent\t0\nmemory_usage_percent\t100\n").cpuUsagePercent,
      Some(BigDecimal(0)))
    val invalid = parse("hostname\tn\ncpu_usage_percent\t-0.1\nmemory_usage_percent\t100.1\n")
    assertEquals(invalid.cpuUsagePercent, None)
    assertEquals(invalid.memoryUsagePercent, None)
  }

  test("requires a non-empty hostname") {
    assert(NodeInventoryParser.parse("operating_system\tLinux\n").isLeft)
    assert(NodeInventoryParser.parse("hostname\t   \n").isLeft)
  }

  test("trims empty optional fields") {
    val result = parse("hostname\t node-1 \nkernel_version\t  \narchitecture\t \n")
    assertEquals(result.hostname, "node-1")
    assertEquals(result.kernelVersion, None)
    assertEquals(result.architecture, None)
  }

  private def parse(value: String): NodeInventory =
    NodeInventoryParser.parse(value).fold(error => fail(error.message), identity)
}
