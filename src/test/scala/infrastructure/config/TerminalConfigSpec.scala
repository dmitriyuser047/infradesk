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
  }
  test("invalid heartbeat, lease and capacity settings fail startup") {
    List(
      Map("INFRADESK_TERMINAL_HEARTBEAT_SECONDS" -> "0"),
      Map("INFRADESK_TERMINAL_HEARTBEAT_SECONDS" -> "301"),
      Map("INFRADESK_TERMINAL_LEASE_SECONDS" -> "29"),
      Map("INFRADESK_TERMINAL_LEASE_SECONDS" -> "901"),
      Map("INFRADESK_TERMINAL_MAX_USER_SESSIONS" -> "0"),
      Map("INFRADESK_TERMINAL_MAX_ORGANIZATION_SESSIONS" -> "1025")
    ).foreach(env => assert(TerminalConfig.fromEnvironment(env).isLeft))
  }
  test("bounded heartbeat and lease overrides are accepted") {
    val c = TerminalConfig.fromEnvironment(Map("INFRADESK_TERMINAL_HEARTBEAT_SECONDS" -> "1",
      "INFRADESK_TERMINAL_LEASE_SECONDS" -> "3")).toOption.get
    assertEquals(c.heartbeatInterval, 1.second)
    assertEquals(c.leaseDuration, 3.seconds)
  }
}
