package ru.bitec.app.ops
package infrastructure.http

import application.connection.{OpenSshTerminal, TerminalOpenFailure}
import cats.effect.{IO, Ref}
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.terminal.{TerminalSession, TerminalClaimResult, TerminalCloseReason}
import fs2.{Chunk, Pipe, Stream}
import infrastructure.config.TerminalConfig
import infrastructure.http.dto.HttpJsonCodecs
import integration.ssh.TerminalSize
import io.circe.{Json, parser}
import org.http4s.{Header, Headers, HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.http4s.headers.{`Sec-WebSocket-Key`, `Sec-WebSocket-Version`}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

final class TerminalRoutes[Tx[_]](
  terminals: OpenSshTerminal[Tx],
  config: TerminalConfig,
  authorization: OrganizationAuthorization,
  logger: Logger[IO],
  live: LiveTerminals[Tx]
) {
  import HttpJsonCodecs._
  import TerminalProtocol._

  private val protocol = "infradesk-terminal-v1"
  private object Resume extends OptionalQueryParamDecoderMatcher[String]("resume")

  def routes(builder: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / orgValue / "connections" / connectionValue / "terminal" :? Resume(resume) =>
      authorization.require(request, OrganizationPermission.OpenTerminal) { context =>
        (uuid(orgValue), uuid(connectionValue), resume.traverse(uuid)) match {
          case (Some(orgId), Some(connectionId), Some(resumeId)) if orgId == context.organizationId =>
            val logContext = contextFields(context.user.id, orgId, connectionId, request)
            if (!TerminalOrigin.sameOrigin(request)) {
              logger.warn(s"terminal.protocol.failed $logContext errorType=OriginRejected closeReason=ORIGIN_REJECTED") *>
                Forbidden(error("ORIGIN_REJECTED", "Request origin is not allowed"))
            } else resumeId match {
              case Some(sessionId) => resumeSession(builder, request, context, connectionId, sessionId, s"$logContext sessionId=$sessionId")
              case None => openSession(builder, request, context, connectionId, logContext)
            }
          case _ => BadRequest(error("INVALID_REQUEST", "Invalid connection identity"))
        }
      }
  }

  private def openSession(builder: WebSocketBuilder2[IO], request: Request[IO], context: OrganizationAccessContext,
    connectionId: UUID, logContext: String): IO[Response[IO]] =
    logger.info(s"terminal.open.start $logContext") *>
      terminals.prepare(context.organizationId, connectionId).attempt.flatMap {
        case Right(_) if !isWebSocketUpgrade(request) =>
          UpgradeRequired(error("TERMINAL_WEBSOCKET_REQUIRED", "WebSocket upgrade is required"))
        case Right(_) if !validHandshake(request) =>
          logger.warn(s"terminal.protocol.failed $logContext errorType=INVALID_WEBSOCKET_HANDSHAKE") *>
            BadRequest(error("INVALID_WEBSOCKET_HANDSHAKE", "WebSocket handshake is invalid"))
        case Right(prepared) => IO.uncancelable { poll =>
          terminals.reserveCapacity.flatMap {
            case None =>
              logger.warn(s"terminal.capacity.rejected $logContext errorType=Capacity") *>
                ServiceUnavailable(error("TERMINAL_CAPACITY", "Terminal capacity is full"))
            case Some(permit) =>
              poll(claimAndBuild(context, connectionId, prepared.connectionUpdatedAt, permit) { durable =>
                session(builder, request, prepared, permit, durable, s"$logContext sessionId=${durable.id}") })
                .guaranteeCase {
                  case cats.effect.kernel.Outcome.Errored(failure) =>
                    permit.release *> logger.error(failure)(s"terminal.open.failed $logContext errorType=${errorType(failure)}")
                  case cats.effect.kernel.Outcome.Canceled() => permit.release
                  case _ => IO.unit
                }
          }
        }
        case Left(failure: TerminalOpenFailure) =>
          logger.warn(s"terminal.open.failed $logContext errorType=${failure.getClass.getSimpleName}") *>
            mapOpenFailure(failure)
        case Left(unexpected) =>
          logger.error(unexpected)(s"terminal.open.failed $logContext errorType=${errorType(unexpected)}") *>
            InternalServerError(error("INTERNAL_ERROR", "Internal server error"))
      }

  /**
   * Attaches a new socket to a shell this process still runs. Authorization, origin and connection
   * checks are those of a fresh open; the shell must also belong to this user's login session. A
   * shell that is gone, foreign or on another process gets the same SESSION_NOT_RESUMABLE answer,
   * after which the client opens a fresh session.
   */
  private def resumeSession(builder: WebSocketBuilder2[IO], request: Request[IO], context: OrganizationAccessContext,
    connectionId: UUID, sessionId: UUID, logContext: String): IO[Response[IO]] =
    logger.info(s"terminal.resume.start $logContext") *>
      terminals.currentVersion(context.organizationId, connectionId).attempt.flatMap {
        case Right(_) if !isWebSocketUpgrade(request) =>
          UpgradeRequired(error("TERMINAL_WEBSOCKET_REQUIRED", "WebSocket upgrade is required"))
        case Right(_) if !validHandshake(request) =>
          logger.warn(s"terminal.protocol.failed $logContext errorType=INVALID_WEBSOCKET_HANDSHAKE") *>
            BadRequest(error("INVALID_WEBSOCKET_HANDSHAKE", "WebSocket handshake is invalid"))
        case Right(version) =>
          context.authSessionId.flatTraverse(authId =>
            live.resumable(sessionId, context.organizationId, connectionId, context.user.id, authId, version)).flatMap { found =>
            // Attach only once the socket runs: a failed handshake must not take the shell from its socket.
            val exchange: Pipe[IO, WebSocketFrame, WebSocketFrame] = input =>
              Stream.eval(found.flatTraverse(terminal => terminal.attach.map(_.toOption.map(terminal -> _)))).flatMap {
                case Some((terminal, attachment)) =>
                  Stream.eval(logger.info(s"terminal.session.resumed $logContext attachment=${attachment.id}")) >>
                    attached(terminal, attachment, resumed = true, logContext)(input)
                case None =>
                  Stream.eval(logger.info(s"terminal.resume.rejected $logContext closeReason=SESSION_NOT_RESUMABLE")) >>
                    Stream.emits(List(textFrame(control("error", "SESSION_NOT_RESUMABLE", "Terminal session cannot be resumed")),
                      closeFrame("SESSION_NOT_RESUMABLE", 1000)))
              }
            socket(builder, request, logContext, exchange, IO.unit)
          }
        case Left(failure: TerminalOpenFailure) =>
          logger.warn(s"terminal.open.failed $logContext errorType=${failure.getClass.getSimpleName}") *>
            mapOpenFailure(failure)
        case Left(unexpected) =>
          logger.error(unexpected)(s"terminal.open.failed $logContext errorType=${errorType(unexpected)}") *>
            InternalServerError(error("INTERNAL_ERROR", "Internal server error"))
      }

  private def claimAndBuild(context: OrganizationAccessContext, connectionId: UUID, version: java.time.Instant,
    permit: terminals.CapacityPermit)(build: TerminalSession => IO[Response[IO]]): IO[Response[IO]] =
    context.authSessionId match {
      case None => permit.release *> InternalServerError(error("INTERNAL_ERROR", "Internal server error"))
      case Some(authId) => IO.uncancelable { poll =>
        live.lifecycle.claim(context.organizationId, connectionId, context.user.id, authId, version).flatMap {
          case TerminalClaimResult.Claimed(durable) => poll(build(durable)).guaranteeCase {
            case cats.effect.kernel.Outcome.Succeeded(_) => IO.unit
            case _ => live.lifecycle.close(durable, TerminalCloseReason.ServerShutdown) *> permit.release
          }
          case _ => permit.release *> ServiceUnavailable(error("TERMINAL_CAPACITY", "Terminal capacity is full"))
        }
      }
    }

  private def mapOpenFailure(failure: TerminalOpenFailure): IO[Response[IO]] = failure match {
    case TerminalOpenFailure.NotFound => NotFound(error("CONNECTION_NOT_FOUND", "Connection was not found"))
    case TerminalOpenFailure.Unsupported => BadRequest(error("TERMINAL_UNSUPPORTED", "Connection does not support SSH terminal"))
    case TerminalOpenFailure.Inactive => BadRequest(error("CONNECTION_INACTIVE", "Connection is inactive"))
    case TerminalOpenFailure.Capacity => ServiceUnavailable(error("TERMINAL_CAPACITY", "Terminal capacity is full"))
    case TerminalOpenFailure.InvalidConfiguration => BadRequest(error("TERMINAL_UNAVAILABLE", "SSH terminal is unavailable"))
  }

  /**
   * The first socket of a new session. Until the socket runs, the claim and the capacity permit are
   * this route's to release; once it runs they pass to the live shell, whichever comes first.
   */
  private def session(
    builder: WebSocketBuilder2[IO],
    request: Request[IO],
    prepared: terminals.Prepared,
    permit: terminals.CapacityPermit,
    durable: TerminalSession,
    logContext: String
  ): IO[Response[IO]] =
    Ref.of[IO, Option[Boolean]](None).flatMap { handedOver =>
      val handOver = handedOver.modify {
        case None => (Some(true), true)
        case other => (other, false)
      }
      val released = handedOver.modify {
        case None => (Some(false), true)
        case other => (other, false)
      }.flatMap { release =>
        (live.lifecycle.close(durable, TerminalCloseReason.ClientClose)
          .handleErrorWith(failure => logger.error(failure)(s"terminal.session.close.failed sessionId=${durable.id}")) *>
          permit.release).whenA(release)
      }
      val exchange: Pipe[IO, WebSocketFrame, WebSocketFrame] = input => Stream.eval(handOver).flatMap {
        case false => Stream.empty
        case true =>
          val shell = terminals.resource(prepared, TerminalSize(config.initialColumns, config.initialRows), permit)
          Stream.eval(live.open(durable, shell, logContext)).flatMap {
            case Left(end) => Stream.emits(end.frames)
            case Right(terminal) => Stream.eval(terminal.attach).flatMap {
              case Right(attachment) => attached(terminal, attachment, resumed = false, logContext)(input)
              case Left(end) => Stream.emits(end.frames)
            }
          }
      }
      socket(builder, request, logContext, exchange, released)
    }

  /** One socket showing a live shell. Losing it without the close control leaves the shell detached. */
  private def attached(terminal: LiveTerminal, attachment: TerminalAttachment, resumed: Boolean,
    logContext: String): Pipe[IO, WebSocketFrame, WebSocketFrame] = input => {
    val id = attachment.id
    val ready = control("ready", "", "").mapObject(_.add("protocolVersion", Json.fromInt(1))
      .add("columns", Json.fromInt(config.initialColumns))
      .add("rows", Json.fromInt(config.initialRows))
      .add("sessionId", Json.fromString(terminal.session.id.toString))
      .add("resumed", Json.fromBoolean(resumed))
      .add("outputTruncated", Json.fromBoolean(attachment.outputTruncated)))
    val deliveries = Stream.repeatEval(terminal.next(id)).takeThrough {
      case _: TerminalDelivery.Output => true
      case _ => false
    }
    val outgoing = (Stream.emit(textFrame(ready)) ++ deliveries.flatMap {
      case TerminalDelivery.Output(bytes) =>
        Stream.chunk(bytes).chunkLimit(config.maxFrameBytes).map(group => WebSocketFrame.Binary(group.toByteVector))
      case TerminalDelivery.Ended(end) => Stream.emits(end.frames)
      case TerminalDelivery.Superseded => Stream.emit(closeFrame("SESSION_RESUMED", 1000))
    }).takeThrough { case _: WebSocketFrame.Close => false; case _ => true }
    val incoming = input.evalMap(frame => process(frame, terminal, id, logContext).attempt.flatMap {
      case Right(frames) => IO.pure(frames)
      case Left(failure) =>
        logger.error(failure)(s"terminal.transport.failed $logContext errorType=${errorType(failure)}") *>
          endWith(terminal, id, sshFailure)
    }).flatMap(frames => Stream.emits(frames)).takeThrough {
      case _: WebSocketFrame.Close => false
      case _ => true
    }
    outgoing.mergeHaltBoth(incoming).onFinalize(terminal.detach(id).flatMap(detached =>
      logger.info(s"terminal.session.detached $logContext attachment=$id").whenA(detached)))
  }

  private def socket(builder: WebSocketBuilder2[IO], request: Request[IO], logContext: String,
    exchange: Pipe[IO, WebSocketFrame, WebSocketFrame], released: IO[Unit]): IO[Response[IO]] = {
    // Builder returns a fallback response template; Ember performs the actual handshake.
    val socketBuilder = builder.withDefragment(false)
      .withOnClose(released)
      .withOnHandshakeFailure(released *>
        logger.warn(s"terminal.protocol.failed $logContext errorType=INVALID_WEBSOCKET_HANDSHAKE") *>
        BadRequest(error("INVALID_WEBSOCKET_HANDSHAKE", "WebSocket handshake is invalid")))
    val protocolRequested = request.headers.headers.find(_.name == CIString("Sec-WebSocket-Protocol"))
      .exists(_.value.split(',').exists(_.trim == protocol))
    val negotiated = if (protocolRequested)
      socketBuilder.withHeaders(Headers(Header.Raw(CIString("Sec-WebSocket-Protocol"), protocol)))
    else socketBuilder
    negotiated.build(exchange)
  }

  private def process(
    frame: WebSocketFrame,
    terminal: LiveTerminal,
    attachment: Long,
    logContext: String
  ): IO[List[WebSocketFrame]] = frame match {
    case WebSocketFrame.Binary(_, last) if !last => protocolFailure("UNSUPPORTED_FRAGMENT", "", terminal, attachment, logContext, 1002)
    case WebSocketFrame.Binary(data, _) if data.size > config.maxFrameBytes =>
      protocolFailure("FRAME_TOO_LARGE", "Terminal input frame is too large", terminal, attachment, logContext, 1009)
    case WebSocketFrame.Binary(data, _) =>
      terminal.write(Chunk.array(data.toArray)).as(List.empty[WebSocketFrame])
    case WebSocketFrame.Text(_, last) if !last => protocolFailure("UNSUPPORTED_FRAGMENT", "", terminal, attachment, logContext, 1002)
    case WebSocketFrame.Text(value, _) if value.getBytes(StandardCharsets.UTF_8).length > config.maxControlBytes =>
      protocolFailure("CONTROL_TOO_LARGE", "Terminal control frame is too large", terminal, attachment, logContext, 1009)
    case WebSocketFrame.Text(value, _) =>
      val parsed = parser.parse(value).toOption
      parsed.flatMap(_.hcursor.downField("type").as[String].toOption) match {
        case Some("resize") =>
          val cursor = parsed.get.hcursor
          (cursor.get[Int]("columns").toOption, cursor.get[Int]("rows").toOption) match {
            case (Some(columns), Some(rows)) if columns >= 1 && columns <= config.maxColumns && rows >= 1 && rows <= config.maxRows =>
              terminal.resize(TerminalSize(columns, rows)).as(List.empty[WebSocketFrame])
            case _ => protocolFailure("INVALID_SIZE", "Terminal size is outside allowed limits", terminal, attachment, logContext, 1002)
          }
        // Ember answers a Close frame itself and ends the input, exactly as a lost connection does.
        // Ending the shell is therefore an explicit control; a socket that merely goes away leaves
        // its shell detached for a resume.
        case Some("close") => clientClose(terminal, attachment)
        case _ => protocolFailure("INVALID_CONTROL", "Unsupported terminal control message", terminal, attachment, logContext, 1002)
      }
    case _: WebSocketFrame.Close => clientClose(terminal, attachment)
    case _: WebSocketFrame.Ping | _: WebSocketFrame.Pong => IO.pure(List.empty)
    case _ => protocolFailure("INVALID_FRAME", "Unsupported terminal frame", terminal, attachment, logContext, 1002)
  }

  private def clientClose(terminal: LiveTerminal, attachment: Long): IO[List[WebSocketFrame]] =
    endWith(terminal, attachment, TerminalEnd("CLIENT_CLOSE", List(closeFrame("CLIENT_CLOSE", 1000))))

  /**
   * Ends the shell on behalf of this socket. Its frames are then written by the outgoing stream as
   * the end's frames; a superseded socket, which cannot end the shell, is answered directly.
   */
  private def endWith(terminal: LiveTerminal, attachment: Long, end: TerminalEnd): IO[List[WebSocketFrame]] =
    terminal.end(attachment, end).map(accepted => if (accepted) Nil else end.frames)

  /** A malformed frame from the current socket ends the shell: the session fails closed. */
  private def protocolFailure(
    code: String,
    message: String,
    terminal: LiveTerminal,
    attachment: Long,
    logContext: String,
    closeCode: Int
  ): IO[List[WebSocketFrame]] =
    logger.warn(s"terminal.protocol.failed $logContext errorType=$code closeReason=$code") *>
      endWith(terminal, attachment, TerminalEnd(code,
        (if (message.isEmpty) List.empty else List(textFrame(control("error", code, message)))) :+ closeFrame(code, closeCode)))

  private def isWebSocketUpgrade(request: Request[IO]): Boolean = {
    val upgrade = request.headers.headers.find(_.name == CIString("Upgrade")).exists(_.value.equalsIgnoreCase("websocket"))
    val connection = request.headers.headers.find(_.name == CIString("Connection"))
      .exists(_.value.split(',').exists(_.trim.equalsIgnoreCase("upgrade")))
    upgrade && connection
  }

  private def contextFields(userId: UUID, organizationId: UUID, connectionId: UUID, request: Request[IO]): String = {
    val requestId = request.headers.headers.find(_.name == infrastructure.http.middleware.RequestIdMiddleware.HeaderName)
      .map(_.value).filter(_.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
      .fold("")(value => s" requestId=$value")
    s"userId=$userId organizationId=$organizationId connectionId=$connectionId$requestId"
  }

  private def validHandshake(request: Request[IO]): Boolean =
    request.headers.get[`Sec-WebSocket-Version`].exists(_.version == 13L) &&
      request.headers.get[`Sec-WebSocket-Key`].exists(_.hashedKey.length == 16)

  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption
  private def error(code: String, message: String): infrastructure.http.dto.ApiErrorResponse =
    infrastructure.http.dto.ApiErrorResponse(code, message)
  private def errorType(error: Throwable): String = error.getClass.getSimpleName
}
