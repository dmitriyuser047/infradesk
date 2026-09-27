package ru.bitec.app.ops
package infrastructure.http

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

final class TerminalProxyConfigSpec extends FunSuite {
  test("production nginx proxies terminal WebSockets without buffering and beyond session lifetime") {
    val config = Files.readString(Paths.get("deploy", "nginx", "infradesk.conf"), StandardCharsets.UTF_8)
    val start = config.indexOf("location ~ ^/api/v1/organizations/[^/]+/connections/[^/]+/terminal$")
    assert(start >= 0)
    val end = config.indexOf("location ", start + 1)
    val terminalLocation = config.substring(start, if (end < 0) config.length else end)

    assert(terminalLocation.contains("proxy_set_header Upgrade $http_upgrade;"))
    assert(terminalLocation.contains("proxy_set_header Connection $infradesk_connection_upgrade;"))
    assert(terminalLocation.contains("proxy_buffering off;"))
    assert("proxy_read_timeout 7500s;".r.findFirstIn(terminalLocation).isDefined)
    assert("proxy_send_timeout 7500s;".r.findFirstIn(terminalLocation).isDefined)
    assert(config.contains("map $http_upgrade $infradesk_connection_upgrade"))
    val sharedProxy = Files.readString(Paths.get("deploy", "nginx", "infradesk-proxy.inc"), StandardCharsets.UTF_8)
    assert(sharedProxy.contains("proxy_set_header Host $http_host;"))
  }
}
