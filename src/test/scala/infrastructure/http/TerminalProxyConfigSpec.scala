package ru.bitec.app.ops
package infrastructure.http

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

final class TerminalProxyConfigSpec extends FunSuite {
  test("production Caddy proxies terminal WebSockets without buffering and beyond session lifetime") {
    val config = Files.readString(Paths.get("deploy", "caddy", "Caddyfile"), StandardCharsets.UTF_8)
    assert(config.contains("^/api/v1/organizations/[^/]+/connections/[^/]+/terminal$"))
    val start = config.indexOf("handle @terminal {")
    assert(start >= 0)
    val end = config.indexOf("@long path_regexp", start + 1)
    val terminalLocation = config.substring(start, if (end < 0) config.length else end)

    // Caddy upgrades WebSockets and retains the incoming Host without manual header rewriting.
    assert(terminalLocation.contains("reverse_proxy backend:8080"))
    assert(terminalLocation.contains("flush_interval -1"))
    assert(terminalLocation.contains("stream_timeout 7500s"))
    assert(terminalLocation.contains("dial_timeout 5s"))
    assert(!config.contains("header_up Host"))
  }
}
