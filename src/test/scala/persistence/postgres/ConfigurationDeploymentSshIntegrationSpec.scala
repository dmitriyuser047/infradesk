package ru.bitec.app.ops
package persistence.postgres

import application.configuration.ConfigurationDeploymentError
import application.port.{RemoteConfigurationSession, RemoteConfigurationTransport}
import cats.effect.IO
import domain.configuration._
import domain.connection.Connection
import munit.FunSuite
import support.RemoteConfigurationServer

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** End to end over real SSH: the production SFTP transport, pinned host key, posix-rename, the
  * structured validator and systemd activation reaching a real SSH server as argv, and a crash after
  * the replace recovered by another worker.
  */
final class ConfigurationDeploymentSshIntegrationSpec extends FunSuite {
  private val Path = "/etc/xray/config.json"
  private val Policy = ConfigurationExecutionPolicy(ConfigurationActivation.SystemdRestart, Some("xray.service"),
    Some(ConfigurationValidator("/usr/local/bin/check-config", List("run", "-test", "-config", "{candidate}"))),
    ConfigurationExecutionPolicy.DefaultFileMode)

  private def world(body: ConfigurationDeploymentWorld => IO[Unit]): Unit = {
    assume(RemoteConfigurationServer.posix, "Remote file modes need a POSIX file system")
    ConfigurationDeploymentWorld.run(body)
  }

  private def prepared(w: ConfigurationDeploymentWorld, mode: String = "ok", existing: Boolean = true) = for {
    node <- w.node("vpn-finland")
    _ <- IO {
      if (existing) w.remote.write(Path, "{\"domainStrategy\": \"AsIs\"}\n", "rw-r-----")
      else Files.createDirectories(w.remote.local("/etc/xray"))
      w.remote.units.set(Map("xray.service" -> Path))
    }
    profile <- w.profile()
    assignmentId <- w.assign(node, profile, Path, "domain" -> "fi.example", "mode" -> mode)
  } yield (node, assignmentId)

  private def leftovers(w: ConfigurationDeploymentWorld): List[String] =
    w.remote.list("/etc/xray").filter(_.startsWith(".infradesk-"))

  test("preview reads the remote file over SFTP and returns hashes and a diff without writing") {
    world { w =>
      for {
        setup <- prepared(w)
        (node, assignmentId) = setup
        preview <- w.deployments.preview(w.org, assignmentId, 1, node.connectionId)
      } yield {
        assert(preview.remoteExists && preview.changed && preview.atomicReplaceSupported)
        assertEquals(preview.remoteSha256, Some(ConfigurationDeployment.sha256("{\"domainStrategy\": \"AsIs\"}\n")))
        assert(preview.diff.text.contains("-{\"domainStrategy\": \"AsIs\"}"), clues(preview.diff))
        assert(preview.diff.text.contains("+server_name fi.example;"), clues(preview.diff))
        assertEquals(w.remote.read(Path), Some("{\"domainStrategy\": \"AsIs\"}\n"))
      }
    }
  }

  test("validates, replaces atomically with the same mode, restarts, verifies and cleans up") {
    world { w =>
      for {
        setup <- prepared(w)
        (node, assignmentId) = setup
        id <- w.deploy(assignmentId, node, Policy)
        _ <- w.drain()
        deployment <- w.deployment(id)
      } yield {
        assertEquals(deployment.state, ConfigurationDeploymentState.Succeeded)
        assertEquals(w.remote.read(Path), Some("server_name fi.example;\nmode ok;\n"))
        assertEquals(w.remote.mode(Path), "rw-r-----")
        assertEquals(leftovers(w), Nil)
        assertEquals(w.remote.recorded, List(
          List("/usr/local/bin/check-config", "run", "-test", "-config", s"/etc/xray/.infradesk-$id.tmp"),
          List("systemctl", "restart", "xray.service"),
          List("systemctl", "is-active", "--quiet", "xray.service")))
      }
    }
  }

  test("a new file is created with the explicit safe mode") {
    world { w =>
      for {
        setup <- prepared(w, existing = false)
        (node, assignmentId) = setup
        id <- w.deploy(assignmentId, node)
        _ <- w.drain()
        deployment <- w.deployment(id)
      } yield {
        assertEquals(deployment.state, ConfigurationDeploymentState.Succeeded)
        assertEquals(w.remote.mode(Path), "rw-r--r--")
      }
    }
  }

  test("a rejected candidate never replaces the target") {
    world { w =>
      for {
        setup <- prepared(w, mode = "INVALID")
        (node, assignmentId) = setup
        id <- w.deploy(assignmentId, node, Policy)
        _ <- w.drain()
        deployment <- w.deployment(id)
      } yield {
        assertEquals(deployment.failureCode, Some("CONFIGURATION_VALIDATION_FAILED"))
        assertEquals(w.remote.read(Path), Some("{\"domainStrategy\": \"AsIs\"}\n"))
        assertEquals(leftovers(w), Nil)
        assert(!w.remote.recorded.exists(_.headOption.contains("systemctl")))
      }
    }
  }

  test("a service that fails its health check gets the exact previous file back") {
    world { w =>
      for {
        setup <- prepared(w, mode = "BROKEN")
        (node, assignmentId) = setup
        id <- w.deploy(assignmentId, node, Policy)
        _ <- w.drain()
        deployment <- w.deployment(id)
      } yield {
        assertEquals(deployment.state, ConfigurationDeploymentState.RolledBack)
        assertEquals(deployment.failureCode, Some("CONFIGURATION_HEALTH_CHECK_FAILED"))
        assertEquals(w.remote.read(Path), Some("{\"domainStrategy\": \"AsIs\"}\n"))
        assertEquals(w.remote.mode(Path), "rw-r-----")
        assertEquals(leftovers(w), Nil)
        assertEquals(w.remote.recorded.count(_ == List("systemctl", "restart", "xray.service")), 2)
      }
    }
  }

  test("a worker that dies right after the replace is recovered by another worker without a second rename") {
    world { w =>
      val renames = new java.util.concurrent.atomic.AtomicInteger(0)
      // The real transport, except that the process dies the moment the first rename returns.
      val dying = new RemoteConfigurationTransport[IO] {
        override def withSession[A](connection: Connection)(use: RemoteConfigurationSession[IO] => IO[A]): IO[A] =
          w.transport.withSession(connection) { real =>
            use(new RemoteConfigurationSession[IO] {
              def supportsAtomicReplace = real.supportsAtomicReplace
              def read(path: String, maxBytes: Int) = real.read(path, maxBytes)
              def create(path: String, bytes: Array[Byte], creation: application.port.RemoteFileCreation) =
                real.create(path, bytes, creation)
              def atomicReplace(from: String, to: String) =
                real.atomicReplace(from, to) *> IO(renames.incrementAndGet()) *> IO.canceled
              def remove(path: String) = real.remove(path)
              def execute(executable: String, args: List[String], timeout: scala.concurrent.duration.FiniteDuration) =
                real.execute(executable, args, timeout)
            })
          }
      }
      for {
        setup <- prepared(w)
        (node, assignmentId) = setup
        id <- w.deploy(assignmentId, node, Policy)
        outcome <- w.worker(remoteTransport = dying).tick.start.flatMap(_.join)
        crashed <- w.deployment(id)
        _ <- w.worker(clock = IO.realTimeInstant.map(_.plusSeconds(120))).tick
        done <- w.deployment(id)
      } yield {
        assert(outcome.isCanceled)
        assertEquals(crashed.phase, ConfigurationDeploymentPhase.Replace)
        assertEquals(renames.get(), 1)
        assertEquals(done.state, ConfigurationDeploymentState.Succeeded)
        assertEquals(w.remote.read(Path), Some("server_name fi.example;\nmode ok;\n"))
        assertEquals(leftovers(w), Nil)
      }
    }
  }

  test("a read-only directory is a permission failure before anything changes") {
    world { w =>
      for {
        setup <- prepared(w)
        (node, assignmentId) = setup
        directory = w.remote.local("/etc/xray")
        _ <- IO(Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x")))
        writable <- IO(Files.isWritable(directory))
        _ <- IO(assume(!writable, "the test user can write anywhere (root)"))
        id <- w.deploy(assignmentId, node)
        _ <- w.drain().guarantee(IO(Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"))))
        deployment <- w.deployment(id)
      } yield {
        assertEquals(deployment.failureCode, Some("CONFIGURATION_REMOTE_PERMISSION_DENIED"))
        assertEquals(w.remote.read(Path), Some("{\"domainStrategy\": \"AsIs\"}\n"))
      }
    }
  }

  test("a remote file above the limit is refused at preview") {
    world { w =>
      for {
        setup <- prepared(w)
        (node, assignmentId) = setup
        _ <- IO(w.remote.write(Path, "x" * (w.settings.maxRemoteFileBytes + 1)))
        preview <- w.deployments.preview(w.org, assignmentId, 1, node.connectionId).attempt
      } yield assertEquals(preview.swap.toOption.collect { case error: ConfigurationDeploymentError => error.code },
        Some("CONFIGURATION_REMOTE_FILE_TOO_LARGE"))
    }
  }

  test("a remote host whose key changed is refused before authentication") {
    world { w =>
      for {
        setup <- prepared(w)
        (node, assignmentId) = setup
        _ <- w.run {
          import org.typelevel.doobie.implicits._
          import org.typelevel.doobie.postgres.implicits._
          sql"""update connection set config = jsonb_set(config, '{hostKeyFingerprint}', '"SHA256:not-this-host"')
                where id = ${node.connectionId}""".update.run
        }
        preview <- w.deployments.preview(w.org, assignmentId, 1, node.connectionId).attempt
      } yield assertEquals(preview.swap.toOption.collect { case error: ConfigurationDeploymentError => error.code },
        Some("CONFIGURATION_HOST_KEY_MISMATCH"))
    }
  }
}
