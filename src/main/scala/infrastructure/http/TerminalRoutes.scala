package ru.bitec.app.ops
package infrastructure.http

import application.connection.{OpenSshTerminal, TerminalOpenFailure}
import cats.effect.{IO, Ref}
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.terminal.{TerminalSession, TerminalClaimResult, TerminalRenewResult, TerminalCloseReason}
import application.terminal.TerminalSessionLifecycle
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
import scala.concurrent.duration._
import scala.util.Try

final class TerminalRoutes[Tx[_]](
  terminals: OpenSshTerminal[Tx],
  config: TerminalConfig,
  authorization: OrganizationAuthorization,
  logger: Logger[IO],
  lifecycle: Option[TerminalSessionLifecycle[Tx]]
) {
  import HttpJsonCodecs._

  private val protocol = "infradesk-terminal-v1"

  def routes(builder: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / orgValue / "connections" / connectionValue / "terminal" =>
      authorization.require(request, OrganizationPermission.OpenTerminal) { context =>
        (uuid(orgValue), uuid(connectionValue)) match {
          case (Some(orgId), Some(connectionId)) if orgId == context.organizationId =>
            val logContext = contextFields(context.user.id, orgId, connectionId, request)
            if (!TerminalOrigin.sameOrigin(request)) {
              logger.warn(s"terminal.protocol.failed $logContext errorType=OriginRejected closeReason=ORIGIN_REJECTED") *>
                Forbidden(error("ORIGIN_REJECTED", "Request origin is not allowed"))
            } else {
              logger.info(s"terminal.open.start $logContext") *>
                terminals.prepare(orgId, connectionId).attempt.flatMap {
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
                        poll(claimAndBuild(context, connectionId, prepared.connectionUpdatedAt, permit) { durable => session(builder, prepared, permit,
                          request.headers.headers.find(_.name == CIString("Sec-WebSocket-Protocol"))
                            .exists(_.value.split(',').exists(_.trim == protocol)),
                          logContext + durable.fold("")(value => s" sessionId=${value.id}"), durable) })
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
            }
          case _ => BadRequest(error("INVALID_REQUEST", "Invalid connection identity"))
        }
      }
  }

  private def claimAndBuild(context: OrganizationAccessContext, connectionId: UUID, version: java.time.Instant,
    permit: terminals.CapacityPermit)(build: Option[TerminalSession] => IO[Response[IO]]): IO[Response[IO]] =
    lifecycle match {
      case None => permit.release *> InternalServerError(error("INTERNAL_ERROR", "Internal server error"))
      case Some(service) => context.authSessionId match {
        case None => permit.release *> InternalServerError(error("INTERNAL_ERROR", "Internal server error"))
        case Some(authId) => IO.uncancelable { poll =>
          service.claim(context.organizationId, connectionId, context.user.id, authId, version).flatMap {
            case TerminalClaimResult.Claimed(durable) => poll(build(Some(durable))).guaranteeCase {
              case cats.effect.kernel.Outcome.Succeeded(_) => IO.unit
              case _ => service.close(durable, TerminalCloseReason.ServerShutdown) *> permit.release
            }
            case _ => permit.release *> ServiceUnavailable(error("TERMINAL_CAPACITY", "Terminal capacity is full"))
          }
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

  private def session(
    builder: WebSocketBuilder2[IO],
    prepared: terminals.Prepared,
    permit: terminals.CapacityPermit,
    protocolRequested: Boolean,
    logContext: String,
    durable: Option[TerminalSession]
  ): IO[Response[IO]] =
    Ref.of[IO, Long](System.nanoTime()).flatMap { activity =>
      Ref.of[IO, String]("CLIENT_CLOSE").flatMap { closeReason =>
        def finish: IO[Unit] = durable.traverse_(value => lifecycle.traverse_ { service =>
          closeReason.get.flatMap(reason => service.close(value,
            TerminalCloseReason.fromCode(reason).getOrElse(TerminalCloseReason.ProtocolError)))
            .handleErrorWith(failure => logger.error(failure)(s"terminal.session.close.failed sessionId=${value.id}"))
        })
        val exchange: Pipe[IO, WebSocketFrame, WebSocketFrame] = input =>
          Stream.resource(terminals.resource(prepared, TerminalSize(config.initialColumns, config.initialRows), permit)
            .attempt).flatMap {
            case Left(failure) =>
              Stream.eval(closeReason.set("SSH_OPEN_FAILED") *> logOpenFailure(failure, logContext)) >>
                Stream.emits(List(
                  WebSocketFrame.Text(control("error", failureCode(failure), safeFailure(failure)).noSpaces),
                  closeFrame("SSH_OPEN_FAILED", 1011)
                ))
            case Right(terminal) =>
              val ready = control("ready", "", "").mapObject(_.add("protocolVersion", Json.fromInt(1))
                .add("columns", Json.fromInt(config.initialColumns))
                .add("rows", Json.fromInt(config.initialRows))
                .add("sessionId", durable.fold(Json.Null)(value => Json.fromString(value.id.toString))))
              val output = terminal.output.chunks.flatMap { bytes =>
                val frames = bytes.toVector.grouped(config.maxFrameBytes).map(group =>
                  WebSocketFrame.Binary(Chunk.array(group.toArray).toByteVector)).toList
                Stream.emits(frames)
              }.evalTap(_ => activity.set(System.nanoTime()))
                .handleErrorWith { failure =>
                  Stream.eval(closeReason.set("SSH_FAILURE") *>
                    logger.error(failure)(s"terminal.transport.failed $logContext errorType=${errorType(failure)}")) >>
                    Stream.emits(List(
                      WebSocketFrame.Text(control("error", "SSH_IO_FAILED", "Terminal transport failed").noSpaces),
                      closeFrame("SSH_FAILURE", 1011)
                    ))
                }
              val activate = durable.traverse_(value => lifecycle.traverse_(service => service.activate(value).flatMap {
                case true => IO.unit
                case false => IO.raiseError(new IllegalStateException("Terminal lease activation rejected"))
              }))
              val outgoing = (Stream.eval(logger.info(s"terminal.opened $logContext")) >>
                (Stream.emit(WebSocketFrame.Text(ready.noSpaces)) ++ output ++
                  Stream.emit(WebSocketFrame.Text(control("closed", "REMOTE_EOF", "SSH shell closed").noSpaces)) ++
                  Stream.emit(closeFrame("REMOTE_EOF", 1000)).evalTap(_ => closeReason.set("REMOTE_EOF"))))
                .takeThrough { case _: WebSocketFrame.Close => false; case _ => true }
              val incoming = input.evalMap(frame => process(frame, terminal, activity, closeReason, logContext).attempt.flatMap {
                case Right(frames) => IO.pure(frames)
                case Left(failure) =>
                  closeReason.set("SSH_FAILURE") *>
                    logger.error(failure)(s"terminal.transport.failed $logContext errorType=${errorType(failure)}") *>
                    IO.pure(List(WebSocketFrame.Text(control("error", "SSH_IO_FAILED", "Terminal transport failed").noSpaces),
                      closeFrame("SSH_FAILURE", 1011)))
              }).flatMap(frames => Stream.emits(frames)).takeThrough {
                case _: WebSocketFrame.Close => false
                case _ => true
              }
              val idleCheckInterval = if (config.idleTimeout < 1.second) config.idleTimeout else 1.second
              val timeout = Stream.awakeEvery[IO](idleCheckInterval).evalMap { _ =>
                activity.get.flatMap { last =>
                  IO(System.nanoTime()).flatMap { now =>
                    if ((now - last).nanos >= config.idleTimeout)
                      closeReason.set("IDLE_TIMEOUT").as(Some(closeFrame("IDLE_TIMEOUT", 1000)))
                    else IO.pure(None)
                  }
                }
              }.unNone.take(1)
              val lifetime = Stream.sleep_[IO](config.maxLifetime) ++
                Stream.emit(closeFrame("MAX_LIFETIME", 1000)).evalTap(_ => closeReason.set("MAX_LIFETIME"))
              val traffic = outgoing.mergeHaltBoth(incoming).mergeHaltBoth(timeout).mergeHaltBoth(lifetime)
              Stream.eval(activate) >> ((durable, lifecycle) match {
                case (Some(value), Some(service)) =>
                  val heartbeat = Stream.awakeEvery[IO](config.heartbeatInterval).evalMap(_ => service.renew(value).attempt.flatMap {
                    case Right(_: TerminalRenewResult.Renewed) => IO.pure(List.empty[WebSocketFrame])
                    case Right(result) =>
                      val reason = result match {
                        case TerminalRenewResult.Revoked(reason) => reason.code
                        case _ => "TERMINAL_SESSION_REVOKED"
                      }
                      closeReason.set(reason) *> logger.warn(s"terminal.session.revoked sessionId=${value.id} closeReason=$reason") *>
                        IO.pure(List(WebSocketFrame.Text(control("closed", reason, "Terminal session ended").noSpaces), closeFrame(reason, 1008)))
                    case Left(failure) =>
                      closeReason.set("SESSION_VALIDATION_FAILED") *>
                        logger.error(failure)(s"terminal.session.validation.failed sessionId=${value.id}") *>
                        IO.pure(List(WebSocketFrame.Text(control("error", "SESSION_VALIDATION_FAILED", "Terminal session validation failed").noSpaces),
                          closeFrame("SESSION_VALIDATION_FAILED", 1011)))
                  }).flatMap(frames => Stream.emits(frames)).takeThrough {
                    case _: WebSocketFrame.Close => false
                    case _ => true
                  }
                  traffic.mergeHaltBoth(heartbeat)
                case _ => traffic
              })
          }.handleErrorWith { failure =>
            Stream.eval(closeReason.set("SESSION_VALIDATION_FAILED") *>
              logger.error(failure)(s"terminal.session.failed $logContext")) >>
              Stream.emits(List(WebSocketFrame.Text(control("error", "SESSION_VALIDATION_FAILED", "Terminal session validation failed").noSpaces),
                closeFrame("SESSION_VALIDATION_FAILED", 1011)))
          }.onFinalize(finish *> closeReason.get.flatMap(reason => logger.info(s"terminal.closed $logContext closeReason=$reason")))
        // Builder returns a fallback response template; Ember performs the actual handshake.
        val socketBuilder = builder.withDefragment(false)
          .withOnClose(finish *> permit.release)
          .withOnHandshakeFailure(finish *> permit.release *>
            logger.warn(s"terminal.protocol.failed $logContext errorType=INVALID_WEBSOCKET_HANDSHAKE") *>
            BadRequest(error("INVALID_WEBSOCKET_HANDSHAKE", "WebSocket handshake is invalid")))
        val negotiated = if (protocolRequested)
          socketBuilder.withHeaders(Headers(Header.Raw(CIString("Sec-WebSocket-Protocol"), protocol)))
        else socketBuilder
        negotiated.build(exchange)
      }
    }

  private def process(
    frame: WebSocketFrame,
    terminal: integration.ssh.InteractiveSshTerminal[IO],
    activity: Ref[IO, Long],
    closeReason: Ref[IO, String],
    logContext: String
  ): IO[List[WebSocketFrame]] = frame match {
    case WebSocketFrame.Binary(_, last) if !last => protocolFailure("UNSUPPORTED_FRAGMENT", "", closeReason, logContext, 1002)
    case WebSocketFrame.Binary(data, _) if data.size > config.maxFrameBytes =>
      protocolFailure("FRAME_TOO_LARGE", "Terminal input frame is too large", closeReason, logContext, 1009)
    case WebSocketFrame.Binary(data, _) =>
      terminal.write(Chunk.array(data.toArray)) *> activity.set(System.nanoTime()).as(List.empty[WebSocketFrame])
    case WebSocketFrame.Text(_, last) if !last => protocolFailure("UNSUPPORTED_FRAGMENT", "", closeReason, logContext, 1002)
    case WebSocketFrame.Text(value, _) if value.getBytes(StandardCharsets.UTF_8).length > config.maxControlBytes =>
      protocolFailure("CONTROL_TOO_LARGE", "Terminal control frame is too large", closeReason, logContext, 1009)
    case WebSocketFrame.Text(value, _) =>
      val parsed = parser.parse(value).toOption
      parsed.flatMap(_.hcursor.downField("type").as[String].toOption) match {
        case Some("resize") =>
          val cursor = parsed.get.hcursor
          (cursor.get[Int]("columns").toOption, cursor.get[Int]("rows").toOption) match {
            case (Some(columns), Some(rows)) if columns >= 1 && columns <= config.maxColumns && rows >= 1 && rows <= config.maxRows =>
              terminal.resize(TerminalSize(columns, rows)) *> activity.set(System.nanoTime()).as(List.empty[WebSocketFrame])
            case _ => protocolFailure("INVALID_SIZE", "Terminal size is outside allowed limits", closeReason, logContext, 1002)
          }
        case _ => protocolFailure("INVALID_CONTROL", "Unsupported terminal control message", closeReason, logContext, 1002)
      }
    case _: WebSocketFrame.Close => closeReason.set("CLIENT_CLOSE").as(List(closeFrame("CLIENT_CLOSE", 1000)))
    case _: WebSocketFrame.Ping | _: WebSocketFrame.Pong => IO.pure(List.empty)
    case _ => protocolFailure("INVALID_FRAME", "Unsupported terminal frame", closeReason, logContext, 1002)
  }

  private def protocolFailure(
    code: String,
    message: String,
    closeReason: Ref[IO, String],
    logContext: String,
    closeCode: Int
  ): IO[List[WebSocketFrame]] =
    closeReason.set(code) *>
      logger.warn(s"terminal.protocol.failed $logContext errorType=$code closeReason=$code") *>
      IO.pure((if (message.isEmpty) List.empty else List(WebSocketFrame.Text(control("error", code, message).noSpaces))) :+
        closeFrame(code, closeCode))

  private def logOpenFailure(failure: Throwable, logContext: String): IO[Unit] = failure match {
    case expected: TerminalOpenFailure =>
      logger.warn(s"terminal.open.failed $logContext errorType=${expected.getClass.getSimpleName}")
    case expected: integration.ssh.SshTransportFailure =>
      logger.warn(expected)(s"terminal.open.failed $logContext errorType=${errorType(expected)}")
    case unexpected =>
      logger.error(unexpected)(s"terminal.open.failed $logContext errorType=${errorType(unexpected)}")
  }

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
  private def control(kind: String, code: String, message: String): Json = Json.obj(
    "type" -> Json.fromString(kind), "protocolVersion" -> Json.fromInt(1),
    "code" -> Json.fromString(code), "message" -> Json.fromString(message)
  )
  private def closeFrame(reason: String, code: Int): WebSocketFrame =
    WebSocketFrame.Close(code, reason.take(100)).fold(throw _, identity)
  private def failureCode(error: Throwable): String = error match {
    case TerminalOpenFailure.Capacity => "TERMINAL_CAPACITY"
    case _ => "SSH_UNAVAILABLE"
  }
  private def errorType(error: Throwable): String = error.getClass.getSimpleName
  private def safeFailure(error: Throwable): String = error match {
    case TerminalOpenFailure.Capacity => "Terminal capacity is full"
    case _: integration.ssh.SshTransportFailure => "SSH connection failed"
    case _ => "Terminal could not be opened"
  }
}
