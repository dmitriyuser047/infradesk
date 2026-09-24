package ru.bitec.app.ops
package integration.ssh

import application.port.SshProbeError
import cats.syntax.all._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{SshConnectionSettings, SshCredential}
import munit.FunSuite

final class SshConnectionProbeAdapterSpec extends FunSuite {
  private val settings = SshConnectionSettings("example.org", 22, "root", Some("SHA256:pinned"), 10, 30)

  test("reading a host identity never opens an authenticated session") {
    var authenticated = 0
    val client = new SshClient[IO] {
      override def probeHostKey(config: SshConnectionConfig): IO[String] = IO {
        assertEquals(config.host, "example.org")
        "SHA256:observed"
      }
      override def withSession[A](config: SshConnectionConfig, auth: SshAuthentication)
                                 (use: SshSession[IO] => IO[A]): IO[A] =
        IO { authenticated += 1 } *> IO.raiseError(new IllegalStateException("must not authenticate"))
    }

    val fingerprint = new SshConnectionProbeAdapter(client)
      .probeHostKey(settings.copy(hostKeyFingerprint = None)).unsafeRunSync()

    assertEquals(fingerprint, "SHA256:observed")
    assertEquals(authenticated, 0)
  }

  test("verification uses the pinned identity and the submitted credential") {
    val client = new SshClient[IO] {
      override def probeHostKey(config: SshConnectionConfig): IO[String] =
        IO.raiseError(new IllegalStateException("must not probe"))
      override def withSession[A](config: SshConnectionConfig, auth: SshAuthentication)
                                 (use: SshSession[IO] => IO[A]): IO[A] = IO {
        assertEquals(config.hostKeyFingerprint, Some("SHA256:pinned"))
        assertEquals(auth, SshAuthentication.PrivateKey("-----BEGIN-----", Some("phrase")))
      } *> use(new SshSession[IO] {
        override def execute(command: String): IO[SshCommandResult] = IO {
          assertEquals(command, "printf 'infradesk-ok\\n'")
          SshCommandResult(0, "infradesk-ok\n", "", "SHA256:pinned")
        }
      })
    }

    val result = new SshConnectionProbeAdapter(client)
      .verify(settings, SshCredential.PrivateKey("-----BEGIN-----", Some("phrase"))).unsafeRunSync()

    assertEquals(result, "SHA256:pinned")
  }

  test("operator-actionable SSH failures stay distinct from one another") {
    def failing(error: Throwable): SshConnectionProbeAdapter = new SshConnectionProbeAdapter(
      new SshClient[IO] {
        override def probeHostKey(config: SshConnectionConfig): IO[String] = IO.raiseError(error)
        override def withSession[A](config: SshConnectionConfig, auth: SshAuthentication)
                                   (use: SshSession[IO] => IO[A]): IO[A] = IO.raiseError(error)
      }
    )
    def verify(error: Throwable): Throwable =
      failing(error).verify(settings, SshCredential.Password("password")).attempt.unsafeRunSync()
        .swap.getOrElse(fail("expected a failure"))

    assertEquals(verify(new SshHostKeyMismatch), SshProbeError.HostKeyMismatch)
    assertEquals(verify(new SshTransportFailure.HostKeyMismatch(new SshHostKeyMismatch)),
      SshProbeError.HostKeyMismatch)
    assertEquals(verify(new SshTransportFailure.HostKeyNotTrusted(new SshHostKeyNotTrusted("x"))),
      SshProbeError.HostKeyNotTrusted)
    assertEquals(
      verify(new SshTransportFailure.AuthenticationFailed(new IllegalStateException("denied"))),
      SshProbeError.AuthenticationFailed)
    assertEquals(verify(new SshTransportFailure.PrivateKeyInvalid(new IllegalStateException("bad"))),
      SshProbeError.PrivateKeyInvalid)
    assertEquals(
      verify(new SshTransportFailure.PrivateKeyPassphraseInvalid(new IllegalStateException("bad"))),
      SshProbeError.PrivateKeyPassphraseInvalid)
    assertEquals(verify(new IllegalStateException("anything else")), SshProbeError.ConnectionFailed)

    // A command that answers with something else is a failed verification, not a success.
    val wrongOutput = new SshConnectionProbeAdapter(new SshClient[IO] {
      override def probeHostKey(config: SshConnectionConfig): IO[String] = IO.pure("SHA256:pinned")
      override def withSession[A](config: SshConnectionConfig, auth: SshAuthentication)
                                 (use: SshSession[IO] => IO[A]): IO[A] =
        use(new SshSession[IO] {
          override def execute(command: String): IO[SshCommandResult] =
            IO.pure(SshCommandResult(1, "", "denied", "SHA256:pinned"))
        })
    })
    intercept[SshProbeError.ConnectionFailed.type] {
      wrongOutput.verify(settings, SshCredential.Password("password")).unsafeRunSync()
    }
  }

  test("no failure carries the credential it was given") {
    val adapter = new SshConnectionProbeAdapter(new SshClient[IO] {
      override def probeHostKey(config: SshConnectionConfig): IO[String] = IO.pure("SHA256:pinned")
      override def withSession[A](config: SshConnectionConfig, auth: SshAuthentication)
                                 (use: SshSession[IO] => IO[A]): IO[A] =
        IO.raiseError(new SshTransportFailure.AuthenticationFailed(
          new IllegalStateException("denied for super-secret-key")))
    })

    val error = adapter
      .verify(settings, SshCredential.PrivateKey("-----BEGIN PRIVATE KEY-----", Some("phrase")))
      .attempt.unsafeRunSync().swap.getOrElse(fail("expected a failure"))

    // The typed probe error replaces whatever the transport said.
    assertEquals(error, SshProbeError.AuthenticationFailed)
    assert(!error.toString.contains("BEGIN PRIVATE KEY"))
    assert(!error.toString.contains("phrase"))
  }
}
