package ru.bitec.app.ops
package infrastructure.config

import munit.FunSuite
import scala.concurrent.duration._

final class TerminalConfigSpec extends FunSuite {
  test("distributed limits and lease timings have conservative defaults") {
    val c = TerminalConfig.fromEnvironment(Map.empty).toOption.get
    assertEquals(c.maxConcurrentSessions, 16)
    assertEquals(c.maxUserSessions, 4)
    assertEquals(c.maxOrganizationSessions, 32)
    assertEquals(c.heartbeatInterval, 15.seconds)
    assertEquals(c.leaseDuration, 45.seconds)
    assertEquals(TerminalConfig.RenewWorstCase, 15.seconds)
    assert(c.heartbeatInterval + TerminalConfig.RenewWorstCase < c.leaseDuration)
    assertEquals(c.detachTimeout, 10.minutes)
    assertEquals(c.detachedOutputBytes, 262144)
  }
  test("invalid heartbeat, lease and capacity settings fail startup") {
    List(
      Map("INFRADESK_TERMINAL_HEARTBEAT_SECONDS" -> "0"),
      Map("INFRADESK_TERMINAL_HEARTBEAT_SECONDS" -> "301"),
      Map("INFRADESK_TERMINAL_LEASE_SECONDS" -> "29"),
      Map("INFRADESK_TERMINAL_HEARTBEAT_SECONDS" -> "1", "INFRADESK_TERMINAL_LEASE_SECONDS" -> "20"),
      Map("INFRADESK_TERMINAL_LEASE_SECONDS" -> "901"),
      Map("INFRADESK_TERMINAL_MAX_USER_SESSIONS" -> "0"),
      Map("INFRADESK_TERMINAL_MAX_ORGANIZATION_SESSIONS" -> "1025"),
      Map("INFRADESK_TERMINAL_DETACH_TIMEOUT_SECONDS" -> "0"),
      Map("INFRADESK_TERMINAL_DETACH_TIMEOUT_SECONDS" -> "7201"),
      Map("INFRADESK_TERMINAL_DETACHED_OUTPUT_BYTES" -> "4095"),
      Map("INFRADESK_TERMINAL_DETACHED_OUTPUT_BYTES" -> "4194305")
    ).foreach(env => assert(TerminalConfig.fromEnvironment(env).isLeft))
  }
  test("bounded heartbeat and lease overrides are accepted") {
    val c = TerminalConfig.fromEnvironment(Map("INFRADESK_TERMINAL_HEARTBEAT_SECONDS" -> "1",
      "INFRADESK_TERMINAL_LEASE_SECONDS" -> "21")).toOption.get
    assertEquals(c.heartbeatInterval, 1.second)
    assertEquals(c.leaseDuration, 21.seconds)
  }
  test("bounded detach overrides are accepted") {
    val c = TerminalConfig.fromEnvironment(Map("INFRADESK_TERMINAL_DETACH_TIMEOUT_SECONDS" -> "60",
      "INFRADESK_TERMINAL_DETACHED_OUTPUT_BYTES" -> "65536")).toOption.get
    assertEquals(c.detachTimeout, 60.seconds)
    assertEquals(c.detachedOutputBytes, 65536)
  }
}
