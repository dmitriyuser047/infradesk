package ru.bitec.app.ops
package integration.ssh

import application.port.{ProvisioningStepResult, SshCredentialResolver}
import cats.effect.IO
import domain.connection.{Connection, SshCredential}
import munit.FunSuite

final class SshProvisioningTransportSpec extends FunSuite {
  private val resolver = new SshCredentialResolver[IO] {
    override def resolveCredential(connection: Connection): IO[SshCredential] = IO.raiseError(
      new UnsupportedOperationException("parser tests do not connect"))
  }
  private val client = new SshClient[IO] {
    override def probeHostKey(config: SshConnectionConfig): IO[String] = IO.raiseError(
      new UnsupportedOperationException("parser tests do not connect"))
    override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
      (use: SshSession[IO] => IO[A]): IO[A] = IO.raiseError(new UnsupportedOperationException("parser tests do not connect"))
  }
  private val transport = new SshProvisioningTransport(client, resolver, 30)
  private val commands = "sh=1\napt-get=1\nsystemctl=1\nawk=1\ndf=1\ngrep=1\n"

  private def result(stdout: String = "", exit: Int = 0, truncated: Boolean = false) =
    SshCommandResult(exit, stdout, "", "fixture", stdoutTruncated = truncated)

  private def observed(os: String = "debian", version: String = "12", commandOutput: String = commands,
    exits: Map[Int, Int] = Map.empty, truncatedAt: Option[Int] = None): List[SshCommandResult] = List(
    result(s"ID=\"$os\"\nVERSION_ID=\"$version\"\n", exits.getOrElse(0, 0), truncatedAt.contains(0)),
    result("x86_64\n", exits.getOrElse(1, 0), truncatedAt.contains(1)),
    result("0\n", exits.getOrElse(2, 0), truncatedAt.contains(2)),
    result("", exits.getOrElse(3, 1), truncatedAt.contains(3)),
    result("1048576\n", exits.getOrElse(4, 0), truncatedAt.contains(4)),
    result("2097152\n", exits.getOrElse(5, 0), truncatedAt.contains(5)),
    result(commandOutput, exits.getOrElse(6, 0), truncatedAt.contains(6)),
    result("", exits.getOrElse(7, 0), truncatedAt.contains(7)),
    result("running\n", exits.getOrElse(8, 0), truncatedAt.contains(8)))

  test("Debian 12 and Ubuntu 22.04/24.04/26.04 are supported; unsupported OS is safe and explicit") {
    List("debian" -> "12", "ubuntu" -> "22.04", "ubuntu" -> "24.04", "ubuntu" -> "26.04").foreach { case (os, version) =>
      val checked = transport.assess(transport.parseResults(observed(os, version)))
      assertEquals(checked.failureCode, None)
      assertEquals(checked.verificationResult, Some(true))
      assertEquals(checked.facts.get("version"), Some(version))
    }
    val unsupported = transport.assess(transport.parseResults(observed("alpine", "3.20")))
    assertEquals(unsupported.failureCode, Some("PROVISIONING_UNSUPPORTED_PLATFORM"))
    assertEquals(unsupported.facts.get("os"), Some("unsupported"))
    assert(!unsupported.facts.values.exists(_.contains("alpine")))
  }

  test("Ubuntu 26.04 still requires readiness and unreviewed releases fail closed") {
    val missing = transport.assess(transport.parseResults(observed("ubuntu", "26.04", commandOutput = "")))
    assertEquals(missing.failureCode, Some("PROVISIONING_COMMANDS_MISSING"))
    List("25.10", "28.04", "26.04.1").foreach { version =>
      val checked = transport.assess(transport.parseResults(observed("ubuntu", version)))
      assertEquals(checked.failureCode, Some("PROVISIONING_UNSUPPORTED_PLATFORM"))
      assertEquals(checked.facts.get("version"), Some("unsupported"))
    }
  }

  test("empty command inventory and required probe failures fail closed") {
    assertEquals(transport.assess(transport.parseResults(observed(commandOutput = ""))).failureCode,
      Some("PROVISIONING_COMMANDS_MISSING"))
    assertEquals(transport.assess(transport.parseResults(observed(exits = Map(2 -> 1)))).failureCode,
      Some("PROVISIONING_REMOTE_PROBE_INVALID"))
    assertEquals(transport.assess(transport.parseResults(observed(os = "", version = ""))).failureCode,
      Some("PROVISIONING_REMOTE_PROBE_INVALID"))
  }

  test("malicious fields are sanitized, numeric facts are bounded and truncation stays uncertain") {
    val malicious = observed(os = ";secret=do-not-store", version = "12",
      exits = Map(4 -> 0, 5 -> 0)).updated(4, result("999999999999999999\n")).updated(5, result("999999999999999999\n"))
    val parsed = transport.parseResults(malicious)
    assertEquals(parsed.facts.get("os"), Some("unsupported"))
    assertEquals(parsed.facts.get("memoryMiB"), Some((1024L * 1024L).toString))
    assertEquals(parsed.facts.get("diskFreeMiB"), Some((1024L * 1024L).toString))
    assert(!parsed.facts.values.exists(_.contains("secret")))
    assertEquals(transport.assess(parsed).failureCode, Some("PROVISIONING_REMOTE_PROBE_INVALID"))

    val truncated = transport.parseResults(observed(truncatedAt = Some(6)))
    assertEquals(truncated.failureCode, Some("PROVISIONING_REMOTE_OUTPUT_LIMIT"))
    assert(truncated.uncertain)
    assert(truncated.outputTruncated)
  }
}
