package ru.bitec.app.ops
package support

import application.port._
import cats.effect.IO
import domain.connection.Connection

import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

/** Remote servers with POSIX file semantics in memory, one per connection, for deterministic worker
  * tests. It mirrors [[RemoteConfigurationServer]]'s simulated services, and every operation passes
  * through `hook`, where a test can change remote state, fail, or cancel the worker at an exact point.
  */
final class InMemoryRemote extends RemoteConfigurationTransport[IO] {
  import InMemoryRemote._

  val operations = new ConcurrentLinkedQueue[Operation]()
  private val servers = new AtomicReference[Map[UUID, Server]](Map.empty)
  /** Runs before an operation; `after` runs once it succeeded. */
  val before = new AtomicReference[Operation => IO[Unit]](_ => IO.unit)
  val after = new AtomicReference[Operation => IO[Unit]](_ => IO.unit)
  val atomicReplace = new AtomicReference[Boolean](true)
  val unavailable = new AtomicReference[Int](0)

  def server(connectionId: UUID): Server =
    servers.updateAndGet(current => if (current.contains(connectionId)) current
      else current.updated(connectionId, new Server)).apply(connectionId)

  def recorded: List[Operation] = operations.asScala.toList
  def count(kind: String): Int = recorded.count(_.kind == kind)

  override def withSession[A](connection: Connection)(use: RemoteConfigurationSession[IO] => IO[A]): IO[A] =
    IO.defer {
      if (unavailable.get() > 0) { unavailable.updateAndGet(_ - 1); IO.raiseError(RemoteConfigurationFailure.Unavailable) }
      else use(new Session(server(connection.id), connection.id))
    }

  private final class Session(remote: Server, connectionId: UUID) extends RemoteConfigurationSession[IO] {
    private def step[A](kind: String, path: String, args: List[String] = Nil)(action: => A): IO[A] = {
      val operation = Operation(connectionId, kind, path, args)
      IO(operations.add(operation)) *> before.get()(operation) *> IO(action).flatTap(_ => after.get()(operation))
    }

    override def supportsAtomicReplace: IO[Boolean] = step("supports", "")(InMemoryRemote.this.atomicReplace.get())

    override def read(path: String, maxBytes: Int): IO[RemoteConfigurationFile] = step("read", path) {
      remote.files.get().get(path) match {
        case None => RemoteConfigurationFile.Missing
        case Some(file) if file.symlink => throw RemoteConfigurationFailure.NotRegularFile
        case Some(file) if file.bytes.length > maxBytes => throw RemoteConfigurationFailure.FileTooLarge
        case Some(file) => RemoteConfigurationFile(exists = true, file.bytes, Some(RemoteFileMetadata(file.mode, file.uid, file.gid)))
      }
    }

    override def create(path: String, bytes: Array[Byte], creation: RemoteFileCreation): IO[Unit] = step("create", path) {
      val (uid, gid) = creation.owner.getOrElse(remote.user)
      if (remote.readOnly.get().exists(path.startsWith)) throw RemoteConfigurationFailure.PermissionDenied
      remote.files.updateAndGet { files =>
        if (files.contains(path)) throw RemoteConfigurationFailure.RemoteIo
        files.updated(path, File(bytes.clone(), creation.permissions, uid, gid))
      }
      ()
    }

    override def atomicReplace(from: String, to: String): IO[Unit] = step("rename", to, List(from)) {
      if (!InMemoryRemote.this.atomicReplace.get()) throw RemoteConfigurationFailure.AtomicReplaceUnsupported
      remote.files.updateAndGet { files =>
        val moved = files.getOrElse(from, throw RemoteConfigurationFailure.RemoteIo)
        files.removed(from).updated(to, moved)
      }
      ()
    }

    override def remove(path: String): IO[Unit] = step("remove", path) {
      remote.files.updateAndGet { files =>
        if (files.get(path).exists(_.symlink)) throw RemoteConfigurationFailure.NotRegularFile
        files.removed(path)
      }
      ()
    }

    override def execute(executable: String, args: List[String], timeout: FiniteDuration): IO[Int] =
      step("execute", executable, args)(remote.run(executable :: args))
  }
}

object InMemoryRemote {
  final case class Operation(connectionId: UUID, kind: String, path: String, args: List[String])

  final case class File(bytes: Array[Byte], mode: Int, uid: Int, gid: Int, symlink: Boolean = false) {
    def text: String = new String(bytes, StandardCharsets.UTF_8)
  }

  /** One node: its files, its systemd units (unit -> configuration path) and the calls it received. */
  final class Server {
    val files = new AtomicReference[Map[String, File]](Map.empty)
    val units = new AtomicReference[Map[String, String]](Map.empty)
    val readOnly = new AtomicReference[Set[String]](Set.empty)
    /** action -> remaining failures; a negative count fails forever. */
    val failures = new AtomicReference[Map[String, Int]](Map.empty)
    val user: (Int, Int) = (1000, 1000)

    def write(path: String, content: String, mode: Int = Integer.parseInt("644", 8), uid: Int = 0, gid: Int = 0): Unit =
      files.updateAndGet(_.updated(path, File(content.getBytes(StandardCharsets.UTF_8), mode, uid, gid)))

    def text(path: String): Option[String] = files.get().get(path).map(_.text)
    def file(path: String): Option[File] = files.get().get(path)
    def names: Set[String] = files.get().keySet

    def fail(action: String, times: Int = -1): Unit = failures.updateAndGet(_.updated(action, times))

    private def failing(action: String): Boolean = {
      val remaining = failures.get().getOrElse(action, 0)
      if (remaining > 0) failures.updateAndGet(_.updated(action, remaining - 1))
      remaining != 0
    }

    def run(argv: List[String]): Int = argv match {
      case "systemctl" :: action :: _ if failing(action) => 1
      case "systemctl" :: ("reload" | "restart") :: unit :: Nil => if (units.get().contains(unit)) 0 else 5
      case "systemctl" :: "is-active" :: "--quiet" :: unit :: Nil =>
        units.get().get(unit) match {
          case Some(path) if !text(path).exists(_.contains("BROKEN")) => 0
          case _ => 3
        }
      case "/usr/local/bin/check-config" :: args if args.nonEmpty => if (text(args.last).exists(_.contains("INVALID"))) 1 else 0
      case _ => 127
    }
  }
}
