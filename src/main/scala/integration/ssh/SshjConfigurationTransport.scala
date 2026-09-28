package ru.bitec.app.ops
package integration.ssh

import application.port.{RemoteConfigurationFile, RemoteConfigurationSession, RemoteConfigurationTransport}
import cats.effect.IO
import cats.syntax.all._
import domain.connection.Connection
import net.schmizz.sshj.sftp.{FileAttributes, OpenMode, RenameFlags}

import java.io.ByteArrayOutputStream
import java.util.EnumSet
import scala.concurrent.duration._

/** Bounded SFTP I/O over the application's existing pinned-key and encrypted-credential path. */
final class SshjConfigurationTransport(client: SshjClient[IO], credentials: SshAuthenticationProvider[IO])
  extends RemoteConfigurationTransport[IO] {

  override def withSession[A](connection: Connection)(use: RemoteConfigurationSession[IO] => IO[A]): IO[A] =
    for {
      config <- IO.fromEither(SshConnectionConfig.from(connection.config))
      authentication <- credentials.resolve(connection)
      result <- client.withSftp(config, authentication) { (sftp, run) =>
        use(new RemoteConfigurationSession[IO] {
          override def read(path: String, maxBytes: Int): IO[RemoteConfigurationFile] = IO.blocking {
            val attributes = sftp.statExistence(path)
            if (attributes == null) RemoteConfigurationFile(false, Array.emptyByteArray, None, None, None)
            else {
              if (attributes.getSize > maxBytes) throw new RemoteFileTooLarge
              val file = sftp.open(path)
              try {
                val output = new ByteArrayOutputStream(math.min(attributes.getSize.toInt, maxBytes))
                val buffer = new Array[Byte](32768)
                var offset = 0L
                var done = false
                while (!done) {
                  val size = file.read(offset, buffer, 0, math.min(buffer.length, maxBytes + 1 - output.size()))
                  if (size < 0 || size == 0) done = true
                  else {
                    output.write(buffer, 0, size)
                    offset += size
                    if (output.size() > maxBytes) throw new RemoteFileTooLarge
                  }
                }
                val mode = attributes.getMode.getPermissionsMask
                RemoteConfigurationFile(true, output.toByteArray, Some(mode), Some(attributes.getUID), Some(attributes.getGID))
              } finally file.close()
            }
          }

          override def upload(path: String, bytes: Array[Byte], mode: Int,
                              uid: Option[Int], gid: Option[Int]): IO[Unit] = IO.blocking {
            val builder = new FileAttributes.Builder().withPermissions(mode)
            (uid, gid).tupled.foreach { case (user, group) => builder.withUIDGID(user, group) }
            val file = sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), builder.build())
            try {
              var offset = 0
              while (offset < bytes.length) {
                val length = math.min(32768, bytes.length - offset)
                file.write(offset.toLong, bytes, offset, length)
                offset += length
              }
            } finally file.close()
          }

          override def atomicReplace(from: String, to: String): IO[Unit] = IO.blocking {
            sftp.rename(from, to, EnumSet.of(RenameFlags.ATOMIC, RenameFlags.OVERWRITE))
          }

          override def remove(path: String): IO[Unit] = IO.blocking {
            if (sftp.statExistence(path) != null) sftp.rm(path)
          }

          override def execute(executable: String, args: List[String], timeoutSeconds: Int): IO[Int] =
            run(PosixArgv.encode(executable :: args)).map(_.exitCode).timeout(timeoutSeconds.seconds)
        })
      }
    } yield result
}

final class RemoteFileTooLarge extends RuntimeException("Remote configuration file is too large")

/** Each argument occupies exactly one POSIX shell word; no user input is parsed as shell syntax. */
object PosixArgv {
  def encode(args: List[String]): String =
    args.map(arg => "'" + arg.replace("'", "'\\''") + "'").mkString(" ")
}
