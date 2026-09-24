package ru.bitec.app.ops
package integration.ssh

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the concrete SSHJ transport against a real in-process SSH server. Test identities
  * are generated for the suite and are unrelated to every deployment credential.
  */
final class SshjClientIntegrationSpec extends FunSuite {
  private val client = new SshjClient[IO]

  test("authenticates with a real unencrypted private key") {
    val key = generatePrivateKey(None)
    withServer(acceptPublicKeys = true) { server =>
      val config = trustedConfig(server)
      val result = client.withSession(config, SshAuthentication.PrivateKey(key, None))(
        _ => IO.pure("authenticated")).unsafeRunSync()
      assertEquals(result, "authenticated")
    }
  }

  test("authenticates with a real encrypted private key and its passphrase") {
    val passphrase = "correct horse battery staple"
    val key = generatePrivateKey(Some(passphrase))
    withServer(acceptPublicKeys = true) { server =>
      val config = trustedConfig(server)
      val result = client.withSession(config,
        SshAuthentication.PrivateKey(key, Some(passphrase)))(
        _ => IO.pure("authenticated")).unsafeRunSync()
      assertEquals(result, "authenticated")
    }
  }

  test("classifies a wrong private-key passphrase without exposing it") {
    val key = generatePrivateKey(Some("correct horse battery staple"))
    withServer(acceptPublicKeys = true) { server =>
      val error = intercept[SshTransportFailure.PrivateKeyPassphraseInvalid] {
        client.withSession(trustedConfig(server),
          SshAuthentication.PrivateKey(key, Some("wrong passphrase value")))(
          _ => IO.unit).unsafeRunSync()
      }
      assert(!error.getMessage.contains("wrong passphrase value"))
    }
  }

  test("classifies an invalid private key and a rejected valid key") {
    withServer(acceptPublicKeys = true) { server =>
      intercept[SshTransportFailure.PrivateKeyInvalid] {
        client.withSession(trustedConfig(server),
          SshAuthentication.PrivateKey("not a private key", None))(_ => IO.unit).unsafeRunSync()
      }
    }

    val key = generatePrivateKey(None)
    withServer(acceptPublicKeys = false) { server =>
      intercept[SshTransportFailure.AuthenticationFailed] {
        client.withSession(trustedConfig(server),
          SshAuthentication.PrivateKey(key, None))(_ => IO.unit).unsafeRunSync()
      }
    }
  }

  test("a wrong pinned fingerprint stops before public-key authentication") {
    val key = generatePrivateKey(None)
    withServer(acceptPublicKeys = true) { server =>
      val error = intercept[SshTransportFailure.HostKeyMismatch] {
        client.withSession(baseConfig(server).copy(hostKeyFingerprint = Some("SHA256:not-the-host")),
          SshAuthentication.PrivateKey(key, None))(_ => IO.unit).unsafeRunSync()
      }
      assertEquals(server.authenticationAttempts.get(), 0)
      assertEquals(error.getMessage, "SSH host key has changed")
    }
  }

  private def trustedConfig(server: RunningServer): SshConnectionConfig = {
    val config = baseConfig(server)
    val fingerprint = client.probeHostKey(config).unsafeRunSync()
    config.copy(hostKeyFingerprint = Some(fingerprint))
  }

  private def baseConfig(server: RunningServer): SshConnectionConfig =
    SshConnectionConfig("127.0.0.1", server.port, "infradesk", None, 5, 5)

  private def withServer[A](acceptPublicKeys: Boolean)(use: RunningServer => A): A = {
    val attempts = new AtomicInteger(0)
    val directory = temporaryDirectory("infradesk-sshd-")
    val sshd = SshServer.setUpDefaultServer()
    sshd.setHost("127.0.0.1")
    sshd.setPort(0)
    sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(directory.resolve("host-key")))
    sshd.setPublickeyAuthenticator((_, _, _) => {
      attempts.incrementAndGet()
      acceptPublicKeys
    })
    sshd.start()
    try use(RunningServer(sshd.getPort, attempts))
    finally sshd.stop(true)
  }

  private def generatePrivateKey(passphrase: Option[String]): String = {
    val directory = temporaryDirectory("infradesk-client-key-")
    val path = directory.resolve("id_rsa")
    val process = new ProcessBuilder(
      "ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-m", "PEM",
      "-N", passphrase.getOrElse(""), "-f", path.toString
    ).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val status = process.waitFor()
    assertEquals(status, 0, clues(output))
    Files.readString(path, StandardCharsets.UTF_8)
  }

  private def temporaryDirectory(prefix: String): Path = {
    val directory = Files.createTempDirectory(prefix)
    directory.toFile.deleteOnExit()
    directory
  }

  private final case class RunningServer(port: Int, authenticationAttempts: AtomicInteger)
}
