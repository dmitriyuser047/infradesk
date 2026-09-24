package ru.bitec.app.ops
package integration.ssh

import cats.effect.kernel.Outcome
import cats.effect.Async
import cats.effect.implicits._
import cats.syntax.all._

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

final case class SshCommandExecutionPolicy(stdoutMaxBytes: Int, stderrMaxBytes: Int)

object SshCommandExecutionPolicy {
  val Default: SshCommandExecutionPolicy = SshCommandExecutionPolicy(
    stdoutMaxBytes = 1024 * 1024,
    stderrMaxBytes = 128 * 1024
  )
}

private[ssh] trait RunningSshCommand {
  def stdout: InputStream
  def stderr: InputStream
  def await(timeoutSeconds: Int): Option[Int]
  def close(): Unit
}

private[ssh] final case class CapturedSshCommand(exitCode: Int, stdout: String, stderr: String)

private[ssh] object BoundedOutput {
  def read(stream: InputStream, maxBytes: Int, streamName: String): Array[Byte] = {
    require(maxBytes >= 0, "maxBytes must not be negative")
    val output = new ByteArrayOutputStream(math.min(maxBytes, 8192))
    val buffer = new Array[Byte](8192)
    var total = 0
    var read = stream.read(buffer)
    while (read >= 0) {
      if (read > 0) {
        if (total > maxBytes - read)
          throw SshTransportFailure.commandOutputLimitExceeded(streamName, maxBytes)
        output.write(buffer, 0, read)
        total += read
      }
      read = stream.read(buffer)
    }
    output.toByteArray
  }
}

private[ssh] final class SshCommandExecutor[F[_]: Async](policy: SshCommandExecutionPolicy) {

  def execute(command: RunningSshCommand, timeoutSeconds: Int): F[CapturedSshCommand] = {
    val control = new ExecutionControl(command)

    def capture(stream: InputStream, limit: Int, name: String): F[String] =
      Async[F].interruptibleMany {
        try new String(BoundedOutput.read(stream, limit, name), StandardCharsets.UTF_8)
        catch {
          case failure: SshTransportFailure.CommandOutputLimitExceeded =>
            control.failAndClose(failure)
            throw failure
        }
      }

    val awaitExit = Async[F].interruptibleMany(command.await(timeoutSeconds)).flatMap {
      case Some(exitCode) => exitCode.pure[F]
      case None =>
        val failure = SshTransportFailure.commandTimeout()
        Async[F].blocking(control.failAndClose(failure)) *> failure.raiseError[F, Int]
    }

    (capture(command.stdout, policy.stdoutMaxBytes, "stdout"),
      capture(command.stderr, policy.stderrMaxBytes, "stderr"),
      awaitExit).parMapN { case (stdout, stderr, exitCode) =>
      CapturedSshCommand(exitCode, stdout, stderr)
    }.handleErrorWith { error =>
      Async[F].delay(control.primaryFailure.get()).flatMap {
        case Some(primary) => primary.raiseError[F, CapturedSshCommand]
        case None => error.raiseError[F, CapturedSshCommand]
      }
    }.guaranteeCase {
      case Outcome.Errored(primary: Throwable) => Async[F].blocking(control.close()).attempt.flatMap {
        case Left(closeError) => Async[F].delay {
          if (closeError ne primary) primary.addSuppressed(closeError)
        }
        case Right(_) => Async[F].unit
      }
      case _ => Async[F].blocking(control.close())
    }
  }

  private final class ExecutionControl(command: RunningSshCommand) {
    val primaryFailure = new AtomicReference[Option[Throwable]](None)
    private val closed = new AtomicBoolean(false)

    def failAndClose(failure: Throwable): Unit = {
      primaryFailure.compareAndSet(None, Some(failure))
      try close()
      catch {
        case closeError: Throwable if closeError ne failure => failure.addSuppressed(closeError)
      }
    }

    def close(): Unit = if (closed.compareAndSet(false, true)) command.close()
  }
}
