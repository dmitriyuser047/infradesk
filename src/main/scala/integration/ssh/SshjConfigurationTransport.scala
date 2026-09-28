package ru.bitec.app.ops
package integration.ssh

import application.port.{
  RemoteConfigurationFailure,
  RemoteConfigurationFile,
  RemoteConfigurationSession,
  RemoteConfigurationTransport,
  RemoteFileCreation,
  RemoteFileMetadata
}
import cats.effect.IO
import domain.connection.Connection
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.sftp.{FileAttributes, FileMode, OpenMode, PacketType, Response, SFTPClient, SFTPException}

import java.io.{ByteArrayOutputStream, IOException}
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/** Bounded SFTP I/O over the application's existing pinned-key and encrypted-credential path.
  *
  * File bytes travel only through the SFTP protocol; the only commands ever run are an executable
  * and its arguments, each encoded as one POSIX word. Nothing here logs a path's content.
  */
final class SshjConfigurationTransport(
  client: SshjClient[IO],
  credentials: SshAuthenticationProvider[IO],
  sftpTimeout: FiniteDuration = 30.seconds
) extends RemoteConfigurationTransport[IO] {
  import SshjConfigurationTransport._

  override def withSession[A](connection: Connection)(use: RemoteConfigurationSession[IO] => IO[A]): IO[A] =
    (for {
      config <- IO.fromEither(SshConnectionConfig.from(connection.config))
      authentication <- credentials.resolve(connection)
      result <- client.withSftp(config, authentication, sftpTimeout.toMillis.toInt) { (sftp, run) =>
        use(new Session(sftp, run, sftpTimeout))
      }
    } yield result).adaptError(classify)
}

object SshjConfigurationTransport {
  /** The OpenSSH extension that renames over an existing file with rename(2) semantics. */
  val PosixRename = "posix-rename@openssh.com"

  private val ChunkSize = 32768
  /** Owner-only while the bytes are written; the final mode is applied once the content is complete. */
  private val WritingPermissions = Integer.parseInt("600", 8)

  private final class Session(
    sftp: SFTPClient,
    run: (String, Int) => IO[SshCommandResult],
    timeout: FiniteDuration
  ) extends RemoteConfigurationSession[IO] {

    override def supportsAtomicReplace: IO[Boolean] = guarded(IO.blocking {
      sftp.getSFTPEngine.supportsServerExtension("posix-rename", "openssh.com")
    })

    override def read(path: String, maxBytes: Int): IO[RemoteConfigurationFile] = guarded(IO.blocking {
      lstat(path) match {
        case None => RemoteConfigurationFile.Missing
        case Some(attributes) =>
          requireRegular(attributes)
          if (attributes.getSize > maxBytes) throw RemoteConfigurationFailure.FileTooLarge
          val file = sftp.open(path, EnumSet.of(OpenMode.READ))
          try {
            val output = new ByteArrayOutputStream(math.min(attributes.getSize.toInt, maxBytes))
            val buffer = new Array[Byte](ChunkSize)
            var offset = 0L
            var done = false
            // The size in the attributes is a hint; the bound is enforced on what is actually read.
            while (!done) {
              val size = file.read(offset, buffer, 0, math.min(buffer.length, maxBytes + 1 - output.size()))
              if (size <= 0) done = true
              else {
                output.write(buffer, 0, size)
                offset += size
                if (output.size() > maxBytes) throw RemoteConfigurationFailure.FileTooLarge
              }
            }
            RemoteConfigurationFile(exists = true, output.toByteArray, Some(metadataOf(attributes)))
          } finally file.close()
      }
    })

    override def create(path: String, bytes: Array[Byte], creation: RemoteFileCreation): IO[Unit] =
      guarded(IO.blocking {
        val file = sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
          new FileAttributes.Builder().withPermissions(WritingPermissions).build())
        try {
          var offset = 0
          while (offset < bytes.length) {
            val length = math.min(ChunkSize, bytes.length - offset)
            file.write(offset.toLong, bytes, offset, length)
            offset += length
          }
        } finally file.close()
        creation.owner.foreach { case (uid, gid) =>
          val current = sftp.lstat(path)
          if (current.getUID != uid || current.getGID != gid)
            try sftp.setattr(path, new FileAttributes.Builder().withUIDGID(uid, gid).build())
            catch { case _: SFTPException => throw RemoteConfigurationFailure.MetadataMismatch }
        }
        // An explicit chmod: the mode given at open time is filtered by the server's umask.
        sftp.setattr(path, new FileAttributes.Builder().withPermissions(creation.permissions).build())
        val written = sftp.lstat(path)
        requireRegular(written)
        val metadata = metadataOf(written)
        val ownerMatches = creation.owner.forall { case (uid, gid) => metadata.uid == uid && metadata.gid == gid }
        if (metadata.permissions != creation.permissions || !ownerMatches || written.getSize != bytes.length.toLong)
          throw RemoteConfigurationFailure.MetadataMismatch
      })

    override def atomicReplace(from: String, to: String): IO[Unit] = guarded(IO.blocking {
      val engine = sftp.getSFTPEngine
      if (!engine.supportsServerExtension("posix-rename", "openssh.com"))
        throw RemoteConfigurationFailure.AtomicReplaceUnsupported
      // SFTPv3 rename refuses an existing target; the OpenSSH extension is rename(2) itself.
      val request = engine.newExtendedRequest(PosixRename)
      request.putString(from, engine.getSubsystem.getRemoteCharset)
      request.putString(to, engine.getSubsystem.getRemoteCharset)
      try {
        engine.request(request).retrieve(timeout.toMillis, TimeUnit.MILLISECONDS)
          .ensurePacketTypeIs(PacketType.STATUS).ensureStatusPacketIsOK()
        ()
      } catch {
        case error: SFTPException if error.getStatusCode == Response.StatusCode.OP_UNSUPPORTED =>
          throw RemoteConfigurationFailure.AtomicReplaceUnsupported
      }
    })

    override def remove(path: String): IO[Unit] = guarded(IO.blocking {
      lstat(path).foreach { attributes =>
        requireRegular(attributes)
        sftp.rm(path)
      }
    })

    override def execute(executable: String, args: List[String], limit: FiniteDuration): IO[Int] =
      guarded(run(PosixArgv.encode(executable :: args), math.max(1, limit.toSeconds.toInt)).map(_.exitCode))

    private def lstat(path: String): Option[FileAttributes] =
      try Some(sftp.lstat(path))
      catch {
        case error: SFTPException if error.getStatusCode == Response.StatusCode.NO_SUCH_FILE ||
          error.getStatusCode == Response.StatusCode.NO_SUCH_PATH => None
      }
  }

  private def requireRegular(attributes: FileAttributes): Unit =
    if (attributes.getType != FileMode.Type.REGULAR) throw RemoteConfigurationFailure.NotRegularFile

  private def metadataOf(attributes: FileAttributes): RemoteFileMetadata =
    RemoteFileMetadata(attributes.getMode.getPermissionsMask & Integer.parseInt("7777", 8),
      attributes.getUID, attributes.getGID)

  private def guarded[A](action: IO[A]): IO[A] = action.adaptError(classify)

  /** Maps every transport failure to a typed, content-free failure. Unknown I/O is treated as a
    * broken connection: the caller then re-reads remote state before doing anything else.
    */
  private[ssh] val classify: PartialFunction[Throwable, Throwable] = {
    case failure: RemoteConfigurationFailure => failure
    case _: SshTransportFailure.HostKeyMismatch => RemoteConfigurationFailure.HostKeyMismatch
    case _: SshTransportFailure.HostKeyNotTrusted => RemoteConfigurationFailure.HostKeyNotTrusted
    case _: SshTransportFailure.AuthenticationFailed | _: SshTransportFailure.PrivateKeyInvalid |
         _: SshTransportFailure.PrivateKeyPassphraseInvalid => RemoteConfigurationFailure.AuthenticationFailed
    case _: SshTransportFailure.CommandTimeout => RemoteConfigurationFailure.CommandTimeout
    case _: SshTransportFailure.CommandOutputLimitExceeded => RemoteConfigurationFailure.RemoteIo
    case _: SshTransportFailure => RemoteConfigurationFailure.Unavailable
    case error: SFTPException => Option(error.getStatusCode) match {
      case Some(Response.StatusCode.PERMISSION_DENIED | Response.StatusCode.WRITE_PROTECT) =>
        RemoteConfigurationFailure.PermissionDenied
      case Some(Response.StatusCode.NO_SUCH_FILE | Response.StatusCode.NO_SUCH_PATH |
                Response.StatusCode.NOT_A_DIRECTORY) => RemoteConfigurationFailure.DirectoryMissing
      case Some(Response.StatusCode.NO_CONNECTION | Response.StatusCode.CONNECITON_LOST) =>
        RemoteConfigurationFailure.Unavailable
      case Some(Response.StatusCode.UNKNOWN) | None => RemoteConfigurationFailure.Unavailable
      case _ => RemoteConfigurationFailure.RemoteIo
    }
    case _: SSHException | _: IOException | _: java.util.concurrent.TimeoutException =>
      RemoteConfigurationFailure.Unavailable
  }
}

/** Each argument occupies exactly one POSIX shell word; no user input is parsed as shell syntax. */
object PosixArgv {
  def encode(args: List[String]): String = {
    require(args.nonEmpty && args.forall(arg => !arg.exists(c => c == '\u0000')), "Invalid argv")
    args.map(arg => "'" + arg.replace("'", "'\\''") + "'").mkString(" ")
  }
}
