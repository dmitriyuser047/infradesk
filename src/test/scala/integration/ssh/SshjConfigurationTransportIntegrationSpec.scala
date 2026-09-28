package ru.bitec.app.ops
package integration.ssh

import application.port.{RemoteConfigurationFailure, RemoteConfigurationFile, RemoteFileCreation}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.ConnectionConfig
import munit.FunSuite
import support.RemoteConfigurationServer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import scala.concurrent.duration._

/** The production SFTP transport against a real SSH server with a real SFTP subsystem: bounded
  * reads, exact metadata, OpenSSH posix-rename, typed failures and argv that stays argv.
  */
final class SshjConfigurationTransportIntegrationSpec extends FunSuite {
  private val transport = new SshjConfigurationTransport(new SshjClient[IO], RemoteConfigurationServer.credentials, 10.seconds)
  private val octal = (text: String) => Integer.parseInt(text, 8)

  private val server = FunFixture[RemoteConfigurationServer](_ => RemoteConfigurationServer.start(), _.stop())

  private def session[A](remote: RemoteConfigurationServer)(use: application.port.RemoteConfigurationSession[IO] => IO[A]): A =
    transport.withSession(remote.connection(UUID.randomUUID()))(use).unsafeRunSync()

  private def failure(action: => Any): RemoteConfigurationFailure =
    intercept[RemoteConfigurationFailure](action)

  server.test("reads a regular file with its hash and mode, and reports a missing one as missing") { remote =>
    assume(RemoteConfigurationServer.posix, "POSIX file permissions are required")
    remote.write("/etc/app/config.json", "{\"a\":1}\n", "rw-r-----")
    val (present, missing) = session(remote)(s => (s.read("/etc/app/config.json", 1024), s.read("/etc/app/none.json", 1024)).tupled)
    assert(present.exists)
    assertEquals(new String(present.bytes, StandardCharsets.UTF_8), "{\"a\":1}\n")
    assertEquals(present.sha256, Some(RemoteConfigurationFile.sha256("{\"a\":1}\n".getBytes(StandardCharsets.UTF_8))))
    assertEquals(present.metadata.map(_.permissions), Some(octal("640")))
    assertEquals(missing, RemoteConfigurationFile.Missing)
  }

  server.test("a file above the limit is refused without reading it into memory") { remote =>
    remote.write("/etc/app/big.conf", "x" * 4097)
    assertEquals(failure(session(remote)(_.read("/etc/app/big.conf", 4096))), RemoteConfigurationFailure.FileTooLarge)
  }

  server.test("a symbolic link or a directory is not a configuration file") { remote =>
    assume(RemoteConfigurationServer.posix, "symbolic links need a POSIX file system")
    remote.write("/etc/app/real.conf", "real")
    Files.createSymbolicLink(remote.local("/etc/app/link.conf"), remote.local("/etc/app/real.conf"))
    assertEquals(failure(session(remote)(_.read("/etc/app/link.conf", 1024))), RemoteConfigurationFailure.NotRegularFile)
    assertEquals(failure(session(remote)(_.read("/etc/app", 1024))), RemoteConfigurationFailure.NotRegularFile)
    assertEquals(failure(session(remote)(_.remove("/etc/app/link.conf"))), RemoteConfigurationFailure.NotRegularFile)
    assert(remote.exists("/etc/app/link.conf"))
  }

  server.test("create writes exact bytes and applies the exact mode whatever the server umask") { remote =>
    assume(RemoteConfigurationServer.posix, "POSIX file permissions are required")
    Files.createDirectories(remote.local("/etc/app"))
    session(remote)(_.create("/etc/app/.infradesk-x.tmp", "new\n".getBytes(StandardCharsets.UTF_8),
      RemoteFileCreation(octal("664"), None)))
    assertEquals(remote.read("/etc/app/.infradesk-x.tmp"), Some("new\n"))
    assertEquals(remote.mode("/etc/app/.infradesk-x.tmp"), "rw-rw-r--")
    // Exclusive: an existing artifact is never silently overwritten by create.
    val again = failure(session(remote)(_.create("/etc/app/.infradesk-x.tmp", Array[Byte](1),
      RemoteFileCreation(octal("644"), None))))
    assertEquals(again, RemoteConfigurationFailure.RemoteIo)
    assertEquals(remote.read("/etc/app/.infradesk-x.tmp"), Some("new\n"))
  }

  server.test("posix-rename replaces an existing target atomically") { remote =>
    remote.write("/etc/app/config.json", "old")
    remote.write("/etc/app/.infradesk-x.tmp", "new")
    val supported = session(remote)(s => s.supportsAtomicReplace <* s.atomicReplace("/etc/app/.infradesk-x.tmp", "/etc/app/config.json"))
    assert(supported)
    assertEquals(remote.read("/etc/app/config.json"), Some("new"))
    assert(!remote.exists("/etc/app/.infradesk-x.tmp"))
  }

  server.test("a missing directory and a read-only directory are typed failures") { remote =>
    assume(RemoteConfigurationServer.posix, "POSIX file permissions are required")
    assertEquals(failure(session(remote)(_.create("/nowhere/.infradesk-x.tmp", Array[Byte](1),
      RemoteFileCreation(octal("644"), None)))), RemoteConfigurationFailure.DirectoryMissing)
    val locked = remote.local("/etc/locked")
    Files.createDirectories(locked)
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
    try {
      assume(!Files.isWritable(locked), "the test user can write anywhere (root)")
      assertEquals(failure(session(remote)(_.create("/etc/locked/.infradesk-x.tmp", Array[Byte](1),
        RemoteFileCreation(octal("644"), None)))), RemoteConfigurationFailure.PermissionDenied)
    } finally Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
  }

  server.test("every argument reaches the server as exactly one literal argument") { remote =>
    val hostile = List("-t", "a b", "it's", "$(touch /tmp/pwned)", "`id`", "x; rm -rf /", "|", "&&", "\"q\"")
    val status = session(remote)(_.execute("/usr/local/bin/check-config", hostile :+ "/etc/app/none", 5.seconds))
    assertEquals(status, 0)
    assertEquals(remote.recorded.last, ("/usr/local/bin/check-config" :: hostile) :+ "/etc/app/none")
  }

  server.test("a changed host key is refused before anything is read") { remote =>
    val changed = remote.connection(UUID.randomUUID()).copy(config = ConnectionConfig(
      remote.connectionConfig.values.updated("hostKeyFingerprint", "SHA256:not-this-host")))
    val error = intercept[RemoteConfigurationFailure](transport.withSession(changed)(_.read("/etc/app/x", 10)).unsafeRunSync())
    assertEquals(error, RemoteConfigurationFailure.HostKeyMismatch)
  }

  test("an unreachable host is a transient failure") {
    val remote = RemoteConfigurationServer.start()
    val connection = remote.connection(UUID.randomUUID())
    remote.stop()
    val error = intercept[RemoteConfigurationFailure](transport.withSession(connection)(_.read("/etc/app/x", 10)).unsafeRunSync())
    assertEquals(error, RemoteConfigurationFailure.Unavailable)
    assert(error.transient)
  }

  test("POSIX argv encoding keeps every argument one word") {
    assertEquals(PosixArgv.encode(List("systemctl", "reload", "nginx.service")), "'systemctl' 'reload' 'nginx.service'")
    val tricky = List("/bin/x", "it's", "", " a ", "$HOME", "\\n")
    assertEquals(RemoteConfigurationServer.decode(PosixArgv.encode(tricky)), tricky)
    intercept[IllegalArgumentException](PosixArgv.encode(List("/bin/x", "nul\u0000byte")))
  }
}
