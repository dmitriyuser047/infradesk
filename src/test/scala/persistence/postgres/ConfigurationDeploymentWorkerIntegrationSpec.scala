package ru.bitec.app.ops
package persistence.postgres

import application.configuration.ConfigurationDeploymentWorker
import cats.effect.{IO, Outcome}
import cats.syntax.all._
import domain.configuration._
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import support.InMemoryRemote
import support.InMemoryRemote.Operation

import java.time.Instant
import java.util.UUID

/** The deployment worker's phase machine against a real database and in-memory POSIX servers:
  * every remote step, every failure path and a crash at every phase boundary. The same flows run
  * over real SSH in [[ConfigurationDeploymentSshIntegrationSpec]].
  */
final class ConfigurationDeploymentWorkerIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentPhase._
  import ConfigurationDeploymentState._

  private val Path = "/etc/app/app.conf"
  private val Temp = (id: UUID) => s"/etc/app/.infradesk-$id.tmp"
  private val Backup = (id: UUID) => s"/etc/app/.infradesk-$id.bak"
  private val octal = (text: String) => Integer.parseInt(text, 8)
  private val Restart = ConfigurationExecutionPolicy(ConfigurationActivation.SystemdRestart, Some("app.service"), None,
    ConfigurationExecutionPolicy.DefaultFileMode)
  private val Validated = ConfigurationExecutionPolicy.Default.copy(
    validator = Some(ConfigurationValidator("/usr/local/bin/check-config", List("-t", "-c", "{candidate}"))))

  private final class Harness(val w: ConfigurationDeploymentWorld) {
    val memory = new InMemoryRemote
    val deployments = w.deploymentsWith(remoteTransport = memory)

    def worker(clock: IO[Instant] = IO.realTimeInstant): ConfigurationDeploymentWorker[ConnectionIO] =
      w.worker(remoteTransport = memory, clock = clock)
    /** A worker whose clock is far enough ahead that every lease taken before has expired. */
    def takeover: ConfigurationDeploymentWorker[ConnectionIO] = worker(IO.realTimeInstant.map(_.plusSeconds(120)))

    def server(node: w.Node): InMemoryRemote.Server = memory.server(node.connectionId)

    def setUp(content: Option[String] = Some("server_name old;\n"), domain: String = "a.example",
              mode: String = "ok"): IO[(w.Node, UUID)] = for {
      node <- w.node("node-a")
      _ <- IO(content.foreach(text => server(node).write(Path, text, octal("640"), 0, 33)))
      _ <- IO(server(node).units.set(Map("app.service" -> Path)))
      profile <- w.profile()
      assignmentId <- w.assign(node, profile, Path, "domain" -> domain, "mode" -> mode)
    } yield node -> assignmentId

    def deploy(assignmentId: UUID, node: w.Node, policy: ConfigurationExecutionPolicy = ConfigurationExecutionPolicy.Default): IO[UUID] =
      for {
        current <- w.assignment(assignmentId)
        preview <- deployments.preview(w.org, assignmentId, current.version, node.connectionId)
        id <- deployments.request(w.actor, assignmentId, current.version, node.connectionId,
          ExpectedRemoteState.of(preview.remoteSha256), policy, UUID.randomUUID())
      } yield id

    /** Cancels the worker's fiber at an exact remote operation: the process "dies" there. */
    def crashing(at: Operation => Boolean, afterSuccess: Boolean): IO[Unit] = {
      val hook: Operation => IO[Unit] = operation => if (at(operation)) IO.canceled else IO.unit
      IO {
        if (afterSuccess) memory.after.set(hook) else memory.before.set(hook)
      } *> worker().tick.start.flatMap(_.join).flatMap {
        case Outcome.Canceled() => IO.unit
        case other => IO(fail(s"the worker was expected to die, but ended with $other"))
      }.guarantee(IO { memory.before.set(_ => IO.unit); memory.after.set(_ => IO.unit) })
    }
  }

  private def world(body: Harness => IO[Unit]): Unit = ConfigurationDeploymentWorld.run(w => body(new Harness(w)))

  private val Desired = "server_name a.example;\nmode ok;\n"

  test("a new revision replaces the file with its exact mode and owner, and leaves nothing behind") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node)
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
        events <- h.w.events(id)
      } yield {
        assertEquals(deployment.state, Succeeded)
        assertEquals(h.server(node).text(Path), Some(Desired))
        val file = h.server(node).file(Path).get
        assertEquals((file.mode, file.uid, file.gid), (octal("640"), 0, 33))
        assertEquals(h.server(node).names, Set(Path))
        assertEquals(events, List("QUEUED", "CLAIMED", "REMOTE_PRECHECK_OK", "UPLOADED", "VALIDATED", "REPLACED",
          "ACTIVATED", "VERIFIED", "SUCCEEDED"))
      }
    }
  }

  test("a missing target is created with the explicit safe mode") {
    world { h =>
      for {
        setup <- h.setUp(content = None)
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node)
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
      } yield {
        assertEquals(deployment.expectedRemoteState, ExpectedRemoteState.Missing)
        assertEquals(deployment.state, Succeeded)
        assertEquals(h.server(node).file(Path).map(_.mode), Some(octal("644")))
      }
    }
  }

  test("a failing validator leaves the target untouched and removes the candidate") {
    world { h =>
      for {
        setup <- h.setUp(mode = "INVALID")
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Validated)
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
      } yield {
        assertEquals(deployment.state, Failed)
        assertEquals(deployment.failureCode, Some("CONFIGURATION_VALIDATION_FAILED"))
        assertEquals(h.server(node).text(Path), Some("server_name old;\n"))
        assertEquals(h.server(node).names, Set(Path))
        // The placeholder became the server-generated candidate path, as one argument.
        val validator = h.memory.recorded.find(_.kind == "execute").get
        assertEquals(validator.path :: validator.args, List("/usr/local/bin/check-config", "-t", "-c", Temp(id)))
      }
    }
  }

  test("a file changed after the preview is never overwritten") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node)
        _ <- IO(h.server(node).write(Path, "edited by hand\n"))
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
      } yield {
        assertEquals(deployment.failureCode, Some("CONFIGURATION_REMOTE_CHANGED"))
        assertEquals(h.server(node).text(Path), Some("edited by hand\n"))
        assertEquals(h.memory.count("create"), 0)
      }
    }
  }

  test("a file changed between the worker's precheck and the replace is never overwritten") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node)
        // Another administrator edits the file right after the candidate was validated and backed up.
        _ <- IO(h.memory.after.set(operation =>
          if (operation.kind == "create" && operation.path.endsWith(".bak")) IO(h.server(node).write(Path, "race\n"))
          else IO.unit))
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
      } yield {
        assertEquals(deployment.state, Failed)
        assertEquals(deployment.failureCode, Some("CONFIGURATION_REMOTE_CHANGED"))
        assertEquals(h.server(node).text(Path), Some("race\n"))
        assertEquals(h.memory.count("rename"), 0)
        assertEquals(h.server(node).names, Set(Path))
      }
    }
  }

  test("restart and a healthy unit complete the deployment") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Restart)
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
        commands = h.memory.recorded.filter(_.kind == "execute").map(op => op.path :: op.args)
      } yield {
        assertEquals(deployment.state, Succeeded)
        assertEquals(commands, List(List("systemctl", "restart", "app.service"),
          List("systemctl", "is-active", "--quiet", "app.service")))
      }
    }
  }

  test("an unhealthy unit after restart rolls back to the exact previous file and restarts it again") {
    world { h =>
      for {
        setup <- h.setUp(mode = "BROKEN")
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Restart)
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
        events <- h.w.events(id)
      } yield {
        assertEquals(deployment.state, RolledBack)
        assertEquals(deployment.failureCode, Some("CONFIGURATION_HEALTH_CHECK_FAILED"))
        assertEquals(deployment.rollbackFromPhase, Some(Verify))
        assertEquals(h.server(node).text(Path), Some("server_name old;\n"))
        assertEquals(h.server(node).file(Path).map(f => (f.mode, f.uid, f.gid)), Some((octal("640"), 0, 33)))
        assertEquals(h.server(node).names, Set(Path))
        assertEquals(h.memory.recorded.count(op => op.args.headOption.contains("restart")), 2)
        assert(events.containsSlice(List("ROLLBACK_STARTED", "ROLLBACK_SUCCEEDED")))
      }
    }
  }

  test("a newly created file is removed on rollback") {
    world { h =>
      for {
        setup <- h.setUp(content = None, mode = "BROKEN")
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Restart)
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
      } yield {
        assertEquals(deployment.state, RolledBack)
        assertEquals(h.server(node).names, Set.empty[String])
      }
    }
  }

  test("a failed activation rolls back; a rollback that cannot restore the service is ROLLBACK_FAILED") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        _ <- IO(h.server(node).fail("restart", times = 1))
        first <- h.deploy(assignmentId, node, Restart)
        _ <- h.w.drain(h.worker())
        rolledBack <- h.w.deployment(first)
        _ <- IO(h.server(node).fail("restart", times = 2))
        second <- h.deploy(assignmentId, node, Restart)
        _ <- h.w.drain(h.worker())
        rollbackFailed <- h.w.deployment(second)
      } yield {
        assertEquals((rolledBack.state, rolledBack.failureCode), (RolledBack, Some("CONFIGURATION_ACTIVATION_FAILED")))
        assertEquals(rollbackFailed.state, RollbackFailed)
        assertEquals(rollbackFailed.failureCode, Some("CONFIGURATION_ACTIVATION_FAILED"))
        // The file itself is back even though the service could not be restarted with it.
        assertEquals(h.server(node).text(Path), Some("server_name old;\n"))
      }
    }
  }

  test("a file edited by hand after the replace is never overwritten by a rollback") {
    world { h =>
      for {
        setup <- h.setUp(mode = "BROKEN")
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Restart)
        _ <- IO(h.memory.after.set(operation =>
          if (operation.kind == "rename") IO(h.server(node).write(Path, "hand edit BROKEN\n")) else IO.unit))
        _ <- h.w.drain(h.worker())
        deployment <- h.w.deployment(id)
      } yield {
        assertEquals(deployment.state, RollbackFailed)
        assertEquals(h.server(node).text(Path), Some("hand edit BROKEN\n"))
      }
    }
  }

  test("refusals before any change: no atomic rename, too large, setuid target, read-only directory") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        _ <- IO(h.memory.atomicReplace.set(false))
        noRename <- h.deploy(assignmentId, node)
        _ <- h.w.drain(h.worker())
        _ <- IO(h.memory.atomicReplace.set(true))
        _ <- IO(h.server(node).write(Path, "old\n", octal("4755")))
        setuid <- h.deploy(assignmentId, node)
        _ <- h.w.drain(h.worker())
        _ <- IO(h.server(node).write(Path, "old\n"))
        _ <- IO(h.server(node).readOnly.set(Set("/etc/app/")))
        readOnly <- h.deploy(assignmentId, node)
        _ <- h.w.drain(h.worker())
        _ <- IO(h.server(node).readOnly.set(Set.empty))
        _ <- IO(h.server(node).write(Path, "x" * (70 * 1024)))
        tooLarge <- h.deploy(assignmentId, node).attempt
        results <- List(noRename, setuid, readOnly).traverse(h.w.deployment)
      } yield {
        assertEquals(results.map(_.failureCode), List(Some("CONFIGURATION_ATOMIC_REPLACE_UNSUPPORTED"),
          Some("CONFIGURATION_REMOTE_METADATA_MISMATCH"), Some("CONFIGURATION_REMOTE_PERMISSION_DENIED")))
        assert(results.forall(_.state == Failed))
        assertEquals(tooLarge.swap.toOption.collect {
          case error: application.configuration.ConfigurationDeploymentError => error.code
        }, Some("CONFIGURATION_REMOTE_FILE_TOO_LARGE"))
        assertEquals(h.memory.count("rename"), 0)
      }
    }
  }

  test("an unreachable server before any change is retried, then the deployment continues") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node)
        _ <- IO(h.memory.unavailable.set(1))
        _ <- h.worker().tick
        waiting <- h.w.deployment(id)
        _ <- h.takeover.tick
        done <- h.w.deployment(id)
        events <- h.w.events(id)
      } yield {
        assertEquals((waiting.state, waiting.transientAttempts), (Running, 1))
        assertEquals(done.state, Succeeded)
        assert(events.contains("RETRY_SCHEDULED"))
      }
    }
  }

  test("an unreachable server that stays unreachable fails the deployment before any change") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node)
        _ <- IO(h.memory.unavailable.set(10))
        _ <- h.worker().tick
        _ <- h.worker(IO.realTimeInstant.map(_.plusSeconds(60))).tick
        _ <- h.worker(IO.realTimeInstant.map(_.plusSeconds(120))).tick
        deployment <- h.w.deployment(id)
      } yield {
        assertEquals(deployment.state, Failed)
        assertEquals(deployment.failureCode, Some("CONFIGURATION_SSH_UNAVAILABLE"))
        assertEquals(h.server(node).text(Path), Some("server_name old;\n"))
      }
    }
  }

  // Crash and resume: the process dies at an exact point; a new worker must continue from the
  // persisted phase without repeating what already happened, or roll back.

  test("a crash after the upload resumes at validation and reuses the candidate") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Validated)
        _ <- h.crashing(op => op.kind == "create" && op.path.endsWith(".tmp"), afterSuccess = true)
        crashed <- h.w.deployment(id)
        _ <- h.takeover.tick
        done <- h.w.deployment(id)
      } yield {
        assertEquals((crashed.state, crashed.phase), (Running, Upload))
        assertEquals(done.state, Succeeded)
        assertEquals(h.memory.recorded.count(op => op.kind == "create" && op.path == Temp(id)), 1)
      }
    }
  }

  test("a crash after validation resumes at replace without validating again") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Validated)
        // The second read of the backup is REPLACE checking it: validation is durably done by then.
        backupReads = new java.util.concurrent.atomic.AtomicInteger(0)
        _ <- h.crashing(op => op.kind == "read" && op.path == Backup(id) && backupReads.incrementAndGet() == 2,
          afterSuccess = false)
        crashed <- h.w.deployment(id)
        _ <- h.takeover.tick
        done <- h.w.deployment(id)
      } yield {
        assertEquals(crashed.phase, Replace)
        assertEquals(done.state, Succeeded)
        assertEquals(h.memory.count("execute"), 1)
        assertEquals(h.memory.recorded.count(op => op.path == Backup(id) && op.kind == "create"), 1)
      }
    }
  }

  test("a crash right after the rename resumes without renaming again") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Restart)
        _ <- h.crashing(_.kind == "rename", afterSuccess = true)
        crashed <- h.w.deployment(id)
        _ <- IO(assertEquals(h.server(node).text(Path), Some(Desired)))
        _ <- h.takeover.tick
        done <- h.w.deployment(id)
      } yield {
        // The database still said REPLACE: only the remote target shows the rename happened.
        assertEquals(crashed.phase, Replace)
        assertEquals(done.state, Succeeded)
        assertEquals(h.memory.count("rename"), 1)
        assertEquals(h.server(node).names, Set(Path))
      }
    }
  }

  test("a crash before activation resumes with the activation") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Restart)
        _ <- h.crashing(op => op.kind == "execute" && op.args.headOption.contains("restart"), afterSuccess = false)
        crashed <- h.w.deployment(id)
        _ <- h.takeover.tick
        done <- h.w.deployment(id)
      } yield {
        assertEquals(crashed.phase, Activate)
        assertEquals(done.state, Succeeded)
        assertEquals(h.memory.recorded.count(op => op.args.headOption.contains("restart")), 2)
      }
    }
  }

  test("a crash after the replace, followed by a failed health check, rolls back on the new worker") {
    world { h =>
      for {
        setup <- h.setUp(mode = "BROKEN")
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Restart)
        _ <- h.crashing(op => op.kind == "execute" && op.args.headOption.contains("restart"), afterSuccess = true)
        _ <- h.takeover.tick
        done <- h.w.deployment(id)
      } yield {
        assertEquals(done.state, RolledBack)
        assertEquals(done.failureCode, Some("CONFIGURATION_HEALTH_CHECK_FAILED"))
        assertEquals(h.server(node).text(Path), Some("server_name old;\n"))
      }
    }
  }

  test("a crash in the middle of a rollback resumes the rollback, not the deployment") {
    world { h =>
      for {
        setup <- h.setUp(mode = "BROKEN")
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node, Restart)
        // Dies right after the previous file was put back, before the service was restarted with it.
        _ <- h.crashing(op => op.kind == "rename" && op.args.headOption.exists(_.endsWith(".bak")), afterSuccess = true)
        crashed <- h.w.deployment(id)
        _ <- h.takeover.tick
        done <- h.w.deployment(id)
      } yield {
        assertEquals((crashed.phase, crashed.rollbackFromPhase), (Rollback, Some(Verify)))
        assertEquals(done.state, RolledBack)
        assertEquals(h.server(node).text(Path), Some("server_name old;\n"))
        assertEquals(h.memory.recorded.count(op => op.args.headOption.contains("restart")), 2)
      }
    }
  }

  test("a cancel before the replace stops safely; a cancel after it lets the deployment finish") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        first <- h.deploy(assignmentId, node, Validated)
        _ <- IO(h.memory.after.set(op =>
          if (op.kind == "create" && op.path.endsWith(".tmp")) h.deployments.cancel(h.w.actor, first) else IO.unit))
        _ <- h.w.drain(h.worker())
        cancelled <- h.w.deployment(first)
        second <- h.deploy(assignmentId, node, Restart)
        _ <- IO(h.memory.after.set(op =>
          if (op.kind == "rename") h.deployments.cancel(h.w.actor, second) else IO.unit))
        _ <- h.w.drain(h.worker())
        finished <- h.w.deployment(second)
      } yield {
        assertEquals(cancelled.state, Cancelled)
        assertEquals(h.memory.recorded.count(op => op.kind == "rename" && op.args == List(Temp(first))), 0)
        assert(!h.server(node).names.exists(_.contains(first.toString)))
        assertEquals(finished.state, Succeeded)
        assert(finished.cancelRequested)
        assertEquals(h.server(node).text(Path), Some(Desired))
      }
    }
  }

  test("a stopping worker gives its lease back at the next boundary and another one continues") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node)
        stopping <- cats.effect.Ref[IO].of(false)
        _ <- IO(h.memory.after.set(op => if (op.kind == "supports") stopping.set(true) else IO.unit))
        _ <- h.worker().tick(stopping.get)
        released <- h.w.deployment(id)
        _ <- IO(h.memory.after.set(_ => IO.unit))
        _ <- h.worker().tick
        done <- h.w.deployment(id)
        events <- h.w.events(id)
      } yield {
        assertEquals((released.state, released.phase), (Running, Upload))
        assertEquals(done.state, Succeeded)
        assert(events.contains("RELEASED"))
      }
    }
  }

  test("a deployment past its overall deadline fails before the replace") {
    world { h =>
      for {
        setup <- h.setUp()
        (node, assignmentId) = setup
        id <- h.deploy(assignmentId, node)
        _ <- h.crashing(op => op.kind == "create", afterSuccess = true)
        _ <- h.worker(IO.realTimeInstant.map(_.plusSeconds(3600))).tick
        deployment <- h.w.deployment(id)
      } yield {
        assertEquals(deployment.failureCode, Some("CONFIGURATION_DEPLOYMENT_TIMEOUT"))
        assertEquals(h.server(node).text(Path), Some("server_name old;\n"))
        assertEquals(h.server(node).names, Set(Path))
      }
    }
  }
}
