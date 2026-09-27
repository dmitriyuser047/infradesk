package ru.bitec.app.ops
package infrastructure.http

import application.connection.{OpenSshTerminal, TerminalOpenFailure}
import cats.effect.{IO, Ref}
import cats.syntax.all._
import domain.auth.OrganizationPermission
import fs2.{Chunk, Pipe, Stream}
import infrastructure.config.TerminalConfig
import io.circe.{Json, parser}
import org.http4s.{Header, Headers, HttpRoutes, Request, Response}
import infrastructure.http.dto.HttpJsonCodecs
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.CIString
import integration.ssh.TerminalSize

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration._
import scala.util.Try

final class TerminalRoutes[Tx[_]](
  terminals: OpenSshTerminal[Tx],
  config: TerminalConfig,
  authorization: OrganizationAuthorization
) {
  import HttpJsonCodecs._

  private val protocol = "infradesk-terminal-v1"

  def routes(builder: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / orgValue / "connections" / connectionValue / "terminal" =>
      authorization.require(request, OrganizationPermission.OpenTerminal) { context =>
        (uuid(orgValue), uuid(connectionValue)) match {
          case (Some(orgId), Some(connectionId)) if orgId == context.organizationId =>
            if (!TerminalOrigin.sameOrigin(request)) Forbidden(error("ORIGIN_REJECTED", "Request origin is not allowed"))
            else terminals.prepare(orgId, connectionId).attempt.flatMap {
              case Right(prepared) =>
                terminals.hasCapacity.flatMap {
                  case false => ServiceUnavailable(error("TERMINAL_CAPACITY", "Terminal capacity is full"))
                  case true => session(builder, prepared, request.headers.headers
                    .find(_.name == CIString("Sec-WebSocket-Protocol"))
                    .exists(_.value.split(',').exists(_.trim == protocol)))
                }
              case Left(TerminalOpenFailure.NotFound) => NotFound(error("CONNECTION_NOT_FOUND", "Connection was not found"))
              case Left(TerminalOpenFailure.Unsupported) => BadRequest(error("TERMINAL_UNSUPPORTED", "Connection does not support SSH terminal"))
              case Left(TerminalOpenFailure.Inactive) => BadRequest(error("CONNECTION_INACTIVE", "Connection is inactive"))
              case Left(TerminalOpenFailure.InvalidConfiguration) => BadRequest(error("TERMINAL_UNAVAILABLE", "SSH terminal is unavailable"))
              case Left(_) => InternalServerError(error("INTERNAL_ERROR", "Internal server error"))
            }
          case _ => BadRequest(error("INVALID_REQUEST", "Invalid connection identity"))
        }
      }
  }

  private def session(
    builder: WebSocketBuilder2[IO],
    prepared: terminals.Prepared,
    protocolRequested: Boolean
  ): IO[Response[IO]] =
    Ref.of[IO, Long](System.nanoTime()).flatMap { activity =>
      val exchange: Pipe[IO, WebSocketFrame, WebSocketFrame] = input =>
        Stream.resource(terminals.resource(prepared, TerminalSize(config.initialColumns, config.initialRows))
          .attempt).flatMap {
          case Left(failure) => Stream.emit(WebSocketFrame.Text(control("error", "SSH_UNAVAILABLE", safeFailure(failure)).noSpaces))
          case Right(terminal) =>
            val ready = control("ready", "", "").mapObject(_.add("protocolVersion", Json.fromInt(1))
              .add("columns", Json.fromInt(config.initialColumns))
              .add("rows", Json.fromInt(config.initialRows)))
            val output = terminal.output.chunks.flatMap { bytes =>
              val frames = bytes.toVector.grouped(config.maxFrameBytes).map(group =>
                WebSocketFrame.Binary(Chunk.array(group.toArray).toByteVector)).toList
              Stream.emits(frames)
            }
            val outgoing = Stream.emit(WebSocketFrame.Text(ready.noSpaces)) ++
              output.evalTap(_ => activity.set(System.nanoTime())) ++
              Stream.emit(WebSocketFrame.Text(control("closed", "REMOTE_EOF", "SSH shell closed").noSpaces))
            val incoming = input.evalMap(frame => process(frame, terminal, activity).attempt.map {
              case Right(frames) => frames
              case Left(_) => List(WebSocketFrame.Text(control("error", "SSH_IO_FAILED", "Terminal transport failed").noSpaces), close("SSH_FAILURE"))
            }).flatMap(frames => Stream.emits(frames)).takeThrough {
              case _: WebSocketFrame.Close => false
              case _ => true
            }
            val timeout = Stream.awakeEvery[IO](1.second).evalMap { _ =>
              activity.get.flatMap { last =>
                IO(System.nanoTime()).flatMap { now =>
                  val elapsed = (now - last).nanos
                  if (elapsed >= config.idleTimeout) IO.pure(Some(close("IDLE_TIMEOUT")))
                  else IO.pure(None)
                }
              }
            }.unNone.take(1)
            val lifetime = Stream.sleep_[IO](config.maxLifetime) ++ Stream.emit(close("MAX_LIFETIME"))
            outgoing.mergeHaltBoth(incoming).mergeHaltBoth(timeout).mergeHaltBoth(lifetime)
              .onFinalize(IO.unit)
        }
      val socketBuilder = builder.withDefragment(false)
      val negotiated = if (protocolRequested)
        socketBuilder.withHeaders(Headers(Header.Raw(CIString("Sec-WebSocket-Protocol"), protocol)))
      else socketBuilder
      negotiated.build(exchange)
    }

  private def process(
    frame: WebSocketFrame,
    terminal: integration.ssh.InteractiveSshTerminal[IO],
    activity: Ref[IO, Long]
  ): IO[List[WebSocketFrame]] = frame match {
    case WebSocketFrame.Binary(data, last) if !last => IO.pure(List(close("UNSUPPORTED_FRAGMENT")))
    case WebSocketFrame.Binary(data, _) if data.size > config.maxFrameBytes =>
      IO.pure(List(WebSocketFrame.Text(control("error", "FRAME_TOO_LARGE", "Terminal input frame is too large").noSpaces), close("PROTOCOL_ERROR")))
    case WebSocketFrame.Binary(data, _) =>
      terminal.write(Chunk.array(data.toArray)).as(List.empty[WebSocketFrame]) *> activity.set(System.nanoTime()).as(List.empty[WebSocketFrame])
    case WebSocketFrame.Text(value, last) if !last => IO.pure(List(close("UNSUPPORTED_FRAGMENT")))
    case WebSocketFrame.Text(value, _) if value.getBytes(StandardCharsets.UTF_8).length > config.maxControlBytes =>
      IO.pure(List(WebSocketFrame.Text(control("error", "CONTROL_TOO_LARGE", "Terminal control frame is too large").noSpaces), close("PROTOCOL_ERROR")))
    case WebSocketFrame.Text(value, _) =>
      parser.parse(value).toOption.flatMap(_.hcursor.downField("type").as[String].toOption) match {
        case Some("resize") =>
          val cursor = parser.parse(value).toOption.get.hcursor
          (cursor.get[Int]("columns").toOption, cursor.get[Int]("rows").toOption) match {
            case (Some(columns), Some(rows)) if columns >= 1 && columns <= config.maxColumns && rows >= 1 && rows <= config.maxRows =>
              terminal.resize(TerminalSize(columns, rows)).as(List.empty[WebSocketFrame]) *>
                activity.set(System.nanoTime()).as(List.empty[WebSocketFrame])
            case _ => IO.pure(List(WebSocketFrame.Text(control("error", "INVALID_SIZE", "Terminal size is outside allowed limits").noSpaces), close("PROTOCOL_ERROR")))
          }
        case _ => IO.pure(List(WebSocketFrame.Text(control("error", "INVALID_CONTROL", "Unsupported terminal control message").noSpaces), close("PROTOCOL_ERROR")))
      }
    case _: WebSocketFrame.Close => IO.pure(List(close("CLIENT_CLOSE")))
    case _: WebSocketFrame.Ping | _: WebSocketFrame.Pong => IO.pure(List.empty)
    case _ => IO.pure(List(WebSocketFrame.Text(control("error", "INVALID_FRAME", "Unsupported terminal frame").noSpaces), close("PROTOCOL_ERROR")))
  }

  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption
  private def error(code: String, message: String): infrastructure.http.dto.ApiErrorResponse =
    infrastructure.http.dto.ApiErrorResponse(code, message)
  private def control(kind: String, code: String, message: String): Json = Json.obj(
    "type" -> Json.fromString(kind),
    "protocolVersion" -> Json.fromInt(1),
    "code" -> Json.fromString(code),
    "message" -> Json.fromString(message)
  )
  private def close(reason: String): WebSocketFrame =
    WebSocketFrame.Close(if (reason == "PROTOCOL_ERROR" || reason == "UNSUPPORTED_FRAGMENT") 1002 else 1000,
      reason.take(100)).fold(throw _, identity)
  private def safeFailure(error: Throwable): String = error match {
    case _: integration.ssh.SshTransportFailure => "SSH connection failed"
    case _ => "Terminal could not be opened"
  }
}
