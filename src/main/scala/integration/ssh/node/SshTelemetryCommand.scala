package ru.bitec.app.ops
package integration.ssh.node

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.io.Source

/** Fixed read-only program. No remote strings are interpolated into shell code. */
object SshTelemetryCommand {
  private val program: String = {
    val stream = Option(getClass.getResourceAsStream("/ssh/telemetry.py"))
      .getOrElse(throw new IllegalStateException("Missing SSH telemetry program"))
    val source = Source.fromInputStream(stream, "UTF-8")
    try source.mkString finally source.close()
  }
  def commandFor(hostname: String): String = {
    val host = Base64.getEncoder.encodeToString(hostname.getBytes(StandardCharsets.UTF_8))
    val encoded = Base64.getEncoder.encodeToString(program.getBytes(StandardCharsets.UTF_8))
    s"if command -v python3 >/dev/null 2>&1; then printf '%s' '$encoded' | base64 -d | python3 - '$host' 2>/dev/null || true; fi"
  }
  val command: String = commandFor("localhost")
}
