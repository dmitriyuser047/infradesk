package ru.bitec.app.ops
package infrastructure.http

import application.connection.TerminalOpenFailure
import application.terminal.TerminalSessionLifecycle
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.syntax.all._
import domain.terminal.{TerminalCloseReason, TerminalRenewResult, TerminalSession}
import fs2.{Chunk, Stream}
import fs2.concurrent.SignallingRef
import infrastructure.config.TerminalConfig
import integration.ssh.{InteractiveSshTerminal, TerminalSize}
import io.circe.Json
import org.http4s.websocket.WebSocketFrame
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.annotation.tailrec
import scala.concurrent.duration._

/**
 * How a live shell ended: the durable close reason and the frames an attached socket receives.
 * Only the socket's outgoing stream writes them, so an error and its Close frame always arrive in
 * order and are never cut off by the end itself.
 */
final case class TerminalEnd(reason: String, frames: List[WebSocketFrame])

/** What an attached socket takes from its shell next. */
sealed trait TerminalDelivery
object TerminalDelivery {
  final case class Output(bytes: Chunk[Byte]) extends TerminalDelivery
  final case class Ended(end: TerminalEnd) extends TerminalDelivery
  /** Another socket of the same session attached; this one only closes. */
  case object Superseded extends TerminalDelivery
}

/** One attachment of a socket to a shell, and whether output was dropped while none was attached. */
final case class TerminalAttachment(id: Long, outputTruncated: Boolean)

/**
 * The shared output of one shell. Attached, a full outbox holds the shell back as the socket
 * used to; detached, the oldest bytes give way so the remote program never blocks on its pty.
 */
private final case class TerminalOutbox(
  chunks: Vector[Chunk[Byte]],
  bytes: Long,
  truncated: Boolean,
  attachment: Option[Long],
  nextAttachment: Long,
  detachedSince: Option[FiniteDuration],
  end: Option[TerminalEnd]
)

/**
 * One SSH shell of this process. It belongs to its durable terminal session, not to the socket
 * that shows it: sockets attach and detach while the shell, its lease and its timers carry on.
 */
final class LiveTerminal private[http] (
  val session: TerminalSession,
  shell: InteractiveSshTerminal[IO],
  outbox: SignallingRef[IO, TerminalOutbox],
  activity: Ref[IO, FiniteDuration],
  stop: Deferred[IO, TerminalEnd],
  outputLimit: Int
) {
  /** Attaches a socket, superseding any earlier one, or tells how the shell already ended. */
  def attach: IO[Either[TerminalEnd, TerminalAttachment]] = outbox.modify { state =>
    state.end match {
      case Some(end) => (state, Left(end))
      case None =>
        val id = state.nextAttachment
        (state.copy(attachment = Some(id), nextAttachment = id + 1, detachedSince = None, truncated = false),
          Right(TerminalAttachment(id, state.truncated)))
    }
  }

  /** The socket went away without the close control: the shell keeps running, detached. */
  def detach(id: Long): IO[Boolean] = IO.monotonic.flatMap(now => outbox.modify { state =>
    if (state.attachment.contains(id) && state.end.isEmpty) (state.copy(attachment = None, detachedSince = Some(now)), true)
    else (state, false)
  })

  /** Waits for output, the end, or a newer attachment. Pending output is delivered before the end. */
  def next(id: Long): IO[TerminalDelivery] =
    outbox.waitUntil(state => !state.attachment.contains(id) || state.chunks.nonEmpty || state.end.isDefined) >>
      outbox.modify { state =>
        if (!state.attachment.contains(id)) (state, Some(TerminalDelivery.Superseded))
        else if (state.chunks.nonEmpty)
          (state.copy(chunks = Vector.empty, bytes = 0), Some(TerminalDelivery.Output(Chunk.concat(state.chunks))))
        else (state, state.end.map(TerminalDelivery.Ended(_)))
      }.flatMap(_.fold(next(id))(IO.pure))

  /**
   * Only the current socket may end the shell; a superseded socket's late request changes nothing.
   * True when the end was requested here: its frames then reach the socket through the outgoing stream.
   */
  def end(id: Long, end: TerminalEnd): IO[Boolean] =
    outbox.get.flatMap(state => if (state.attachment.contains(id)) stop.complete(end).as(true) else IO.pure(false))

  def write(bytes: Chunk[Byte]): IO[Unit] = shell.write(bytes) *> touch
  def resize(size: TerminalSize): IO[Unit] = shell.resize(size) *> touch

  private[http] def touch: IO[Unit] = IO.monotonic.flatMap(activity.set)
  private[http] def idleFor: IO[FiniteDuration] = (IO.monotonic, activity.get).mapN(_ - _)
  private[http] def detachedFor: IO[Option[FiniteDuration]] =
    (IO.monotonic, outbox.get).mapN((now, state) => state.detachedSince.map(now - _))
  private[http] def requested: IO[TerminalEnd] = stop.get
  private[http] def pendingOutput: IO[Chunk[Byte]] = outbox.get.map(state => Chunk.concat(state.chunks))
  private[http] def finish(end: TerminalEnd): IO[Unit] = outbox.update(state => state.copy(end = state.end.orElse(Some(end))))

  /** Drains the shell into the outbox until EOF; raises on SSH failure. */
  private[http] def pump(stalled: IO[Unit]): IO[Unit] =
    shell.output.chunks.evalMap(chunk => touch *> deliver(chunk, stalled)).compile.drain

  private def deliver(chunk: Chunk[Byte], stalled: IO[Unit]): IO[Unit] =
    outbox.modify { state =>
      val fits = state.bytes == 0 || state.bytes + chunk.size <= outputLimit
      if (fits) (state.copy(chunks = state.chunks :+ chunk, bytes = state.bytes + chunk.size), true)
      else if (state.attachment.isDefined) (state, false)
      else (keepNewest(state, chunk), true)
    }.flatMap {
      case true => IO.unit
      case false =>
        outbox.waitUntil(state => state.attachment.isEmpty || state.bytes + chunk.size <= outputLimit)
          .timeoutTo(TerminalConfig.OutputStallTimeout, stalled) *> deliver(chunk, stalled)
    }

  /** A socket that has taken nothing for the stall timeout is gone in all but name. */
  private[http] def detachStalled: IO[Option[Long]] = IO.monotonic.flatMap(now => outbox.modify { state =>
    if (state.attachment.isDefined && state.bytes > 0 && state.end.isEmpty)
      (state.copy(attachment = None, detachedSince = Some(now)), state.attachment)
    else (state, None)
  })

  private def keepNewest(state: TerminalOutbox, chunk: Chunk[Byte]): TerminalOutbox = {
    val incoming = if (chunk.size > outputLimit) chunk.takeRight(outputLimit) else chunk
    @tailrec def drop(kept: Vector[Chunk[Byte]], bytes: Long): (Vector[Chunk[Byte]], Long) =
      if (kept.isEmpty || bytes + incoming.size <= outputLimit) (kept, bytes)
      else drop(kept.tail, bytes - kept.head.size)
    val (kept, bytes) = drop(state.chunks, state.bytes)
    state.copy(chunks = kept :+ incoming, bytes = bytes + incoming.size, truncated = true)
  }
}

/**
 * The SSH shells of this process. A shell outlives the WebSocket that opened it: when a socket is
 * lost without the close control the shell stays detached for `detachTimeout`, and a socket of the same
 * user's same login session may resume it. Shells exist only in this process; a resume that reaches
 * another process finds nothing and the client opens a fresh session. Releasing this resource ends
 * every shell with SERVER_SHUTDOWN.
 */
final class LiveTerminals[Tx[_]] private (
  val lifecycle: TerminalSessionLifecycle[Tx],
  config: TerminalConfig,
  logger: Logger[IO],
  supervisor: Supervisor[IO],
  registry: Ref[IO, Map[UUID, LiveTerminal]]
) {
  import TerminalProtocol._

  /**
   * Opens the shell for a claimed durable session and returns once it is active, or how it ended.
   * From here on the shell, its capacity permit and its durable close belong to this registry.
   */
  def open(session: TerminalSession, shell: Resource[IO, InteractiveSshTerminal[IO]], logContext: String):
    IO[Either[TerminalEnd, LiveTerminal]] =
    Deferred[IO, Either[TerminalEnd, LiveTerminal]].flatMap { opened =>
      val program: IO[TerminalEnd] = shell.attempt.use {
        case Left(failure) =>
          logOpenFailure(failure, logContext).as(TerminalEnd("SSH_OPEN_FAILED", List(
            textFrame(control("error", failureCode(failure), safeFailure(failure))), closeFrame("SSH_OPEN_FAILED", 1011))))
            .flatTap(end => opened.complete(Left(end)))
        case Right(terminal) =>
          for {
            live <- create(session, terminal)
            activated <- lifecycle.activate(session).attempt
            end <- activated match {
              case Right(true) =>
                registry.update(_ + (session.id -> live)) *> logger.info(s"terminal.opened $logContext") *>
                  opened.complete(Right(live)) *> run(live, logContext)
              case failed =>
                val failure = failed.left.toOption.getOrElse(new IllegalStateException("Terminal lease activation rejected"))
                logger.error(failure)(s"terminal.session.failed $logContext").as(validationFailed)
                  .flatTap(end => opened.complete(Left(end)))
            }
            _ <- live.finish(end)
          } yield end
      }
      val owned = program.guaranteeCase {
        case cats.effect.kernel.Outcome.Succeeded(result) => result.flatMap(end => release(session, end.reason, logContext))
        case cats.effect.kernel.Outcome.Errored(failure) =>
          logger.error(failure)(s"terminal.session.failed $logContext") *> release(session, "SESSION_VALIDATION_FAILED", logContext)
        case cats.effect.kernel.Outcome.Canceled() => release(session, "SERVER_SHUTDOWN", logContext)
      }.guarantee(opened.complete(Left(TerminalEnd("SERVER_SHUTDOWN", Nil))).void)
      supervisor.supervise(owned) *> opened.get
    }

  /**
   * The live shell a socket may resume: same organization, connection, user and login session,
   * captured connection version unchanged. Anything else is indistinguishable from absence.
   */
  def resumable(sessionId: UUID, organizationId: UUID, connectionId: UUID, userId: UUID,
    authSessionId: UUID, connectionUpdatedAt: Instant): IO[Option[LiveTerminal]] =
    registry.get.map(_.get(sessionId).filter { live =>
      val session = live.session
      session.organizationId == organizationId && session.connectionId == connectionId &&
        session.actorUserId == userId && session.authSessionId == authSessionId &&
        session.connectionUpdatedAt == connectionUpdatedAt
    })

  private def create(session: TerminalSession, terminal: InteractiveSshTerminal[IO]): IO[LiveTerminal] =
    for {
      now <- IO.monotonic
      outbox <- SignallingRef[IO, TerminalOutbox](TerminalOutbox(Vector.empty, 0, truncated = false,
        attachment = None, nextAttachment = 0, detachedSince = Some(now), end = None))
      activity <- Ref.of[IO, FiniteDuration](now)
      stop <- Deferred[IO, TerminalEnd]
    } yield new LiveTerminal(session, terminal, outbox, activity, stop, config.detachedOutputBytes)

  /** Runs the shell until its first ending: EOF, failure, timeouts, revocation or a socket's request. */
  private def run(live: LiveTerminal, logContext: String): IO[TerminalEnd] = {
    def every(limit: FiniteDuration): FiniteDuration = if (limit < 1.second) limit else 1.second
    val stalled = live.detachStalled.flatMap(_.traverse_(id =>
      logger.warn(s"terminal.session.detached $logContext sessionId=${live.session.id} attachment=$id cause=OUTPUT_STALLED")))
    val output = live.pump(stalled).as(TerminalEnd("REMOTE_EOF", List(
      textFrame(control("closed", "REMOTE_EOF", "SSH shell closed")), closeFrame("REMOTE_EOF", 1000))))
      .handleErrorWith(failure =>
        logger.error(failure)(s"terminal.transport.failed $logContext errorType=${failure.getClass.getSimpleName}").as(sshFailure))
    val idle = Stream.awakeEvery[IO](every(config.idleTimeout)).evalMap(_ => live.idleFor)
      .find(_ >= config.idleTimeout).as(TerminalEnd("IDLE_TIMEOUT", List(closeFrame("IDLE_TIMEOUT", 1000))))
    val detached = Stream.awakeEvery[IO](every(config.detachTimeout)).evalMap(_ => live.detachedFor)
      .find(_.exists(_ >= config.detachTimeout)).as(TerminalEnd("DETACH_TIMEOUT", Nil))
    val lifetime = IO.sleep(config.maxLifetime).as(TerminalEnd("MAX_LIFETIME", List(closeFrame("MAX_LIFETIME", 1000))))
    Stream(Stream.eval(output), idle, detached, Stream.eval(lifetime), heartbeat(live.session), Stream.eval(live.requested))
      .parJoinUnbounded.head.compile.lastOrError
  }

  // A brief database interruption is not proof that the SSH session or access has ended.
  // Keep the shell while retrying, but fail closed before its lease can expire.
  private def heartbeat(session: TerminalSession): Stream[IO, TerminalEnd] = {
    def renewWithRetry(delays: List[FiniteDuration]): IO[Either[Throwable, TerminalRenewResult]] =
      lifecycle.renew(session).timeout(TerminalConfig.RenewAttemptTimeout).attempt.flatMap {
        case Left(failure) if delays.nonEmpty =>
          logger.warn(failure)(s"terminal.session.verification.retry sessionId=${session.id} remaining=${delays.size}") *>
            IO.sleep(delays.head) *> renewWithRetry(delays.tail)
        case result => IO.pure(result)
      }
    Stream.awakeEvery[IO](config.heartbeatInterval).evalMap(_ => renewWithRetry(TerminalConfig.RenewRetryDelays).flatMap {
      case Right(_: TerminalRenewResult.Renewed) => IO.pure(None)
      case Right(result) =>
        val reason = result match {
          case TerminalRenewResult.Revoked(reason) => reason.code
          case _ => "TERMINAL_SESSION_REVOKED"
        }
        logger.warn(s"terminal.session.revoked sessionId=${session.id} closeReason=$reason").as(Some(TerminalEnd(reason,
          List(textFrame(control("closed", reason, "Terminal session ended")), closeFrame(reason, 1008)))))
      case Left(failure) =>
        logger.error(failure)(s"terminal.session.validation.failed sessionId=${session.id}").as(Some(validationFailed))
    }).unNone.take(1)
  }

  private def release(session: TerminalSession, reason: String, logContext: String): IO[Unit] =
    registry.update(_ - session.id) *>
      lifecycle.close(session, TerminalCloseReason.fromCode(reason).getOrElse(TerminalCloseReason.ProtocolError))
        .handleErrorWith(failure => logger.error(failure)(s"terminal.session.close.failed sessionId=${session.id}")) *>
      logger.info(s"terminal.closed $logContext closeReason=$reason")

  private val validationFailed = TerminalEnd("SESSION_VALIDATION_FAILED", List(
    textFrame(control("error", "SESSION_VALIDATION_FAILED", "Terminal session validation failed")),
    closeFrame("SESSION_VALIDATION_FAILED", 1011)))

  private def logOpenFailure(failure: Throwable, logContext: String): IO[Unit] = failure match {
    case expected: TerminalOpenFailure =>
      logger.warn(s"terminal.open.failed $logContext errorType=${expected.getClass.getSimpleName}")
    case expected: integration.ssh.SshTransportFailure =>
      logger.warn(expected)(s"terminal.open.failed $logContext errorType=${expected.getClass.getSimpleName}")
    case unexpected =>
      logger.error(unexpected)(s"terminal.open.failed $logContext errorType=${unexpected.getClass.getSimpleName}")
  }
  private def failureCode(error: Throwable): String = error match {
    case TerminalOpenFailure.Capacity => "TERMINAL_CAPACITY"
    case _ => "SSH_UNAVAILABLE"
  }
  private def safeFailure(error: Throwable): String = error match {
    case TerminalOpenFailure.Capacity => "Terminal capacity is full"
    case _: integration.ssh.SshTransportFailure => "SSH connection failed"
    case _ => "Terminal could not be opened"
  }
}

object LiveTerminals {
  def resource[Tx[_]](lifecycle: TerminalSessionLifecycle[Tx], config: TerminalConfig, logger: Logger[IO]):
    Resource[IO, LiveTerminals[Tx]] =
    for {
      supervisor <- Supervisor[IO](await = false)
      registry <- Resource.eval(Ref.of[IO, Map[UUID, LiveTerminal]](Map.empty))
    } yield new LiveTerminals(lifecycle, config, logger, supervisor, registry)
}

/** Frame shapes of protocol v1 shared by the route and the live shells. */
private[http] object TerminalProtocol {
  val sshFailure: TerminalEnd = TerminalEnd("SSH_FAILURE", List(
    textFrame(control("error", "SSH_IO_FAILED", "Terminal transport failed")), closeFrame("SSH_FAILURE", 1011)))

  def textFrame(value: Json): WebSocketFrame = WebSocketFrame.Text(value.noSpaces)
  def control(kind: String, code: String, message: String): Json = Json.obj(
    "type" -> Json.fromString(kind), "protocolVersion" -> Json.fromInt(1),
    "code" -> Json.fromString(code), "message" -> Json.fromString(message)
  )
  def closeFrame(reason: String, code: Int): WebSocketFrame =
    WebSocketFrame.Close(code, reason.take(100)).fold(throw _, identity)
}
