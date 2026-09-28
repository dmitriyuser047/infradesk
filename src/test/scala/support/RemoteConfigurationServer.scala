package ru.bitec.app.ops
package support

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import integration.ssh.{SshAuthentication, SshAuthenticationProvider, SshConnectionConfig, SshjClient}
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.{Environment, ExitCallback, SshServer}
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{FileSystems, Files, Path}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

/** A real SSH server with a real SFTP subsystem, rooted in a temporary directory, for configuration
  * deployment tests. Remote paths such as `/etc/app/config.json` live under that directory.
  *
  * Commands arrive exactly as the transport sends them — one POSIX shell word per argument — and are
  * decoded, never given to a shell. `systemctl` is simulated per unit: a unit is active unless its
  * configuration file contains `BROKEN`, and a restart can be made to fail. The validator
  * `/usr/local/bin/check-config` exits non-zero when its last argument names a file containing
  * `INVALID`. Every decoded argv is recorded, so a test can assert what reached the server.
  */
final class RemoteConfigurationServer private (val root: Path, sshd: SshServer, val fingerprint: String) {
  val commands = new ConcurrentLinkedQueue[List[String]]()
  /** unit name -> remote configuration path the unit reads. */
  val units = new AtomicReference[Map[String, String]](Map.empty)
  val failingActions = new AtomicReference[Set[String]](Set.empty)

  def port: Int = sshd.getPort

  def local(remote: String): Path = root.resolve(remote.stripPrefix("/"))

  def write(remote: String, content: String, mode: String = "rw-r--r--"): Unit = {
    val path = local(remote)
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    if (RemoteConfigurationServer.posix) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
  }

  def read(remote: String): Option[String] = {
    val path = local(remote)
    Option.when(Files.exists(path))(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
  }

  def mode(remote: String): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(local(remote)))

  def exists(remote: String): Boolean = Files.exists(local(remote), java.nio.file.LinkOption.NOFOLLOW_LINKS)

  /** Names in a remote directory, such as leftover `.infradesk-*` artifacts. */
  def list(remoteDirectory: String): List[String] = {
    val directory = local(remoteDirectory)
    if (!Files.isDirectory(directory)) Nil
    else {
      val stream = Files.list(directory)
      try stream.iterator().asScala.map(_.getFileName.toString).toList.sorted finally stream.close()
    }
  }

  def recorded: List[List[String]] = commands.asScala.toList

  def connectionConfig: ConnectionConfig = ConnectionConfig(Map(
    "host" -> "127.0.0.1", "port" -> port.toString, "username" -> "deploy",
    "hostKeyFingerprint" -> fingerprint, "connectTimeoutSeconds" -> "5", "commandTimeoutSeconds" -> "10"))

  def connection(organizationId: UUID, id: UUID = UUID.randomUUID(), updatedAt: Instant = Instant.now()): Connection =
    Connection(id, organizationId, ConnectionScope.Organization, "SSH", s"ssh-$id", "production-ssh",
      connectionConfig, None, isActive = true, updatedAt, updatedAt)

  def stop(): Unit = sshd.stop(true)

  private[support] def handle(argv: List[String]): Int = {
    commands.add(argv)
    argv match {
      case "systemctl" :: action :: unit :: Nil if failingActions.get().contains(action) => 1
      case "systemctl" :: ("reload" | "restart") :: unit :: Nil => if (units.get().contains(unit)) 0 else 5
      case "systemctl" :: "is-active" :: "--quiet" :: unit :: Nil =>
        units.get().get(unit) match {
          case Some(configuration) if !read(configuration).exists(_.contains("BROKEN")) &&
            !failingActions.get().contains("is-active") => 0
          case _ => 3
        }
      case "/usr/local/bin/check-config" :: args if args.nonEmpty =>
        if (read(args.last).exists(_.contains("INVALID"))) 1 else 0
      case _ => 127
    }
  }
}

object RemoteConfigurationServer {
  val posix: Boolean = FileSystems.getDefault.supportedFileAttributeViews().contains("posix")

  /** Authentication for the fixture only; it never resembles a deployment credential. */
  val credentials: SshAuthenticationProvider[IO] = new SshAuthenticationProvider[IO] {
    override def resolve(connection: Connection): IO[SshAuthentication] =
      IO.pure(SshAuthentication.Password("fixture-only-password"))
  }

  def start(): RemoteConfigurationServer = {
    val root = Files.createTempDirectory("infradesk-remote-")
    val keys = Files.createTempDirectory("infradesk-remote-keys-")
    val sshd = SshServer.setUpDefaultServer()
    sshd.setHost("127.0.0.1")
    sshd.setPort(0)
    sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(keys.resolve("host-key")))
    sshd.setPasswordAuthenticator((_, _, _) => true)
    sshd.setFileSystemFactory(new VirtualFileSystemFactory(root))
    sshd.setSubsystemFactories(java.util.List.of(new SftpSubsystemFactory.Builder().build()))
    val holder = new AtomicReference[RemoteConfigurationServer]()
    sshd.setCommandFactory((_: ChannelSession, command: String) => new DecodedCommand(command, holder.get()))
    sshd.start()
    val fingerprint = new SshjClient[IO].probeHostKey(
      SshConnectionConfig("127.0.0.1", sshd.getPort, "deploy", None, 5, 10)).unsafeRunSync()
    val server = new RemoteConfigurationServer(root, sshd, fingerprint)
    holder.set(server)
    server
  }

  /** Decodes the POSIX words the transport produced; a shell is never involved. */
  def decode(command: String): List[String] = {
    val words = List.newBuilder[String]
    val current = new StringBuilder
    var inQuote = false
    var inWord = false
    var index = 0
    while (index < command.length) {
      val c = command.charAt(index)
      if (inQuote) { if (c == '\'') inQuote = false else current.append(c) }
      else c match {
        case '\'' => inQuote = true; inWord = true
        case '\\' if index + 1 < command.length => index += 1; current.append(command.charAt(index)); inWord = true
        case ' ' => if (inWord) { words += current.toString; current.clear(); inWord = false }
        case other => current.append(other); inWord = true
      }
      index += 1
    }
    if (inQuote) throw new IllegalArgumentException("Unterminated quote")
    if (inWord) words += current.toString
    words.result()
  }

  private final class DecodedCommand(command: String, server: RemoteConfigurationServer) extends Command {
    private var exit: ExitCallback = _
    private var out: OutputStream = _
    override def setInputStream(in: InputStream): Unit = ()
    override def setOutputStream(stream: OutputStream): Unit = out = stream
    override def setErrorStream(err: OutputStream): Unit = ()
    override def setExitCallback(callback: ExitCallback): Unit = exit = callback
    override def start(channel: ChannelSession, env: Environment): Unit = {
      val status = scala.util.Try(server.handle(decode(command))).getOrElse(126)
      out.flush()
      exit.onExit(status)
    }
    override def destroy(channel: ChannelSession): Unit = ()
  }
}
