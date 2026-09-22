package ru.bitec.app.ops
package integration.ssh

import application.port.SshProbeError
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.SshConnectionSettings
import munit.FunSuite

final class SshConnectionProbeAdapterSpec extends FunSuite {
  private val settings = SshConnectionSettings("example.org", 22, "root", Some("SHA256:pinned"), 10, 30)

  test("probe uses the existing SSH client and lightweight command") {
    val client = new SshClient[IO] {
      override def execute(config: SshConnectionConfig, auth: SshAuthentication, command: String): IO[SshCommandResult] = IO {
        assertEquals(config.hostKeyFingerprint, Some("SHA256:pinned"))
        assertEquals(auth, SshAuthentication.Password("password"))
        assertEquals(command, "printf 'infradesk-ok\\n'")
        SshCommandResult(0, "infradesk-ok\n", "", "SHA256:pinned")
      }
    }
    assertEquals(new SshConnectionProbeAdapter(client).probe(settings, "password").unsafeRunSync(), "SHA256:pinned")
  }

  test("probe keeps host-key mismatch distinct from other SSH failures") {
    val mismatch = new SshClient[IO] {
      override def execute(config: SshConnectionConfig, auth: SshAuthentication, command: String): IO[SshCommandResult] =
        IO.raiseError(new SshHostKeyMismatch)
    }
    intercept[SshProbeError.HostKeyMismatch.type] {
      new SshConnectionProbeAdapter(mismatch).probe(settings, "password").unsafeRunSync()
    }
    val failed = new SshClient[IO] {
      override def execute(config: SshConnectionConfig, auth: SshAuthentication, command: String): IO[SshCommandResult] =
        IO.pure(SshCommandResult(1, "", "denied", "SHA256:pinned"))
    }
    intercept[SshProbeError.ConnectionFailed.type] {
      new SshConnectionProbeAdapter(failed).probe(settings, "password").unsafeRunSync()
    }
  }
}
