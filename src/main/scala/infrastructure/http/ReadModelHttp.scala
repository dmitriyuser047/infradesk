package ru.bitec.app.ops
package infrastructure.http

import application.incident.IncidentPageRequest
import application.port.IncidentCursor
import cats.effect.IO
import cats.syntax.all._
import domain.incident.IncidentStatus
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import infrastructure.http.middleware.RequestIdMiddleware
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.http4s.{Request, Response}
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** What the read-model routes share: one incident page grammar and one way to fail. */
object ReadModelHttp {
  import HttpJsonCodecs._

  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  /** `status`, `limit` and a two-part cursor, `beforeOpenedAt` with `beforeId`, that travel together
    * so a page continues after one exact row even when several incidents opened at the same instant.
    */
  def incidentPage(request: Request[IO]): Either[ApiErrorResponse, IncidentPageRequest] = {
    val params = request.uri.query.params
    val status: Either[ApiErrorResponse, Option[IncidentStatus]] =
      params.get("status").traverse(IncidentStatus.fromCode).leftMap(_ => invalid("status"))
    val cursor: Either[ApiErrorResponse, Option[IncidentCursor]] = (params.get("beforeOpenedAt"), params.get("beforeId")) match {
      case (None, None) => Right(None)
      case (Some(openedAt), Some(id)) =>
        (Try(Instant.parse(openedAt)).toOption, Try(UUID.fromString(id)).toOption)
          .mapN(IncidentCursor.apply)
          .map(Option(_))
          .toRight(invalid("incident cursor"))
      case _ => Left(invalid("incident cursor"))
    }
    val limit: Either[ApiErrorResponse, Int] = params.get("limit") match {
      case None => Right(IncidentPageRequest.DefaultLimit)
      case Some(raw) =>
        Try(raw.toInt).toOption.filter(value => value > 0 && value <= IncidentPageRequest.MaxLimit).toRight(invalid("limit"))
    }
    (status, cursor, limit).mapN(IncidentPageRequest.apply)
  }

  /** An unexpected read failure: logged with its cause and safe identifiers, answered generically.
    * The client never sees the exception; the log never sees request bodies or credentials.
    */
  def failed(logger: Logger[IO], request: Request[IO], operation: String, context: (String, Any)*)(error: Throwable): IO[Response[IO]] =
    failedAs(logger, "read.failed", request, operation, context: _*)(error)

  /** The same, under an event name of the caller's: `configuration.request.failed`, for one. */
  def failedAs(logger: Logger[IO], event: String, request: Request[IO], operation: String, context: (String, Any)*)(
    error: Throwable
  ): IO[Response[IO]] = {
    // A client may supply the id; only a plain token reaches the log, never a crafted line.
    val requestId = request.headers.headers.find(_.name == RequestIdMiddleware.HeaderName).map(_.value)
      .filter(_.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
    val fields = (context.map { case (key, value) => s"$key=$value" } ++
      requestId.map(value => s"requestId=$value") :+ s"errorType=${error.getClass.getSimpleName}").mkString(" ")
    logger.error(error)(s"$event operation=$operation $fields").attempt *> InternalServerError(internalError)
  }

  def invalid(name: String): ApiErrorResponse = ApiErrorResponse("INVALID_REQUEST", s"Invalid $name")
}
