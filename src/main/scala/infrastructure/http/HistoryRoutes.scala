package ru.bitec.app.ops
package infrastructure.http

import application.history.ListHistoryEvents
import application.port.{HistoryEventView, ResourceRepository, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.history.HistoryEventCursor
import infrastructure.http.dto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** The activity timeline: the organization's, and one resource's. Reading only. */
final class HistoryRoutes[Tx[_]](
  listHistoryEvents: ListHistoryEvents[Tx],
  resourceRepository: ResourceRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization
) {
  import HttpJsonCodecs._

  private val invalidResource = ApiErrorResponse("INVALID_REQUEST", "Invalid resourceId")
  private val invalidCursor = ApiErrorResponse("INVALID_REQUEST", "Invalid history cursor")
  private val invalidLimit = ApiErrorResponse("INVALID_REQUEST", "Invalid limit")
  private val resourceNotFound = ApiErrorResponse("RESOURCE_NOT_FOUND", "Resource was not found")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "history-events" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { context =>
        page(request) { (cursor, limit) =>
          respond(runner.run(listHistoryEvents.organization(context.organizationId, cursor, limit)))
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resourceRaw / "history-events" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { context =>
        Try(UUID.fromString(resourceRaw)).toOption match {
          case None => BadRequest(invalidResource)
          case Some(resourceId) =>
            page(request) { (cursor, limit) =>
              // A resource of another organization is not found, not forbidden.
              runner.run(resourceRepository.findById(context.organizationId, resourceId)).attempt.flatMap {
                case Left(_) => InternalServerError(internalError)
                case Right(None) => NotFound(resourceNotFound)
                case Right(Some(_)) =>
                  respond(runner.run(
                    listHistoryEvents.resource(context.organizationId, resourceId, cursor, limit)
                  ))
              }
            }
        }
      }
  }

  private def respond(program: IO[List[HistoryEventView]]): IO[org.http4s.Response[IO]] =
    program.attempt.flatMap {
      case Right(events) => Ok(events.map(HistoryEventResponse.from))
      case Left(_) => InternalServerError(internalError)
    }

  private def page(request: org.http4s.Request[IO])(
    next: (Option[HistoryEventCursor], Int) => IO[org.http4s.Response[IO]]
  ): IO[org.http4s.Response[IO]] = {
    val params = request.uri.query.params
    (cursor(params.get("beforeOccurredAt"), params.get("beforeId")), limit(params.get("limit"))) match {
      case (Left(error), _) => BadRequest(error)
      case (_, Left(error)) => BadRequest(error)
      case (Right(before), Right(pageSize)) => next(before, pageSize)
    }
  }

  /** Both halves of the cursor travel together, so a page continues after one exact row. */
  private def cursor(
    occurredAt: Option[String],
    id: Option[String]
  ): Either[ApiErrorResponse, Option[HistoryEventCursor]] =
    (occurredAt, id) match {
      case (None, None) => Right(None)
      case (Some(rawOccurredAt), Some(rawId)) =>
        for {
          parsedOccurredAt <- Try(Instant.parse(rawOccurredAt)).toEither.leftMap(_ => invalidCursor)
          parsedId <- Try(UUID.fromString(rawId)).toEither.leftMap(_ => invalidCursor)
        } yield Some(HistoryEventCursor(parsedOccurredAt, parsedId))
      case _ => Left(invalidCursor)
    }

  private def limit(raw: Option[String]): Either[ApiErrorResponse, Int] = raw match {
    case None => Right(ListHistoryEvents.DefaultLimit)
    case Some(value) =>
      Try(value.toInt).toOption.filter(parsed => parsed > 0 && parsed <= ListHistoryEvents.MaxLimit)
        .toRight(invalidLimit)
  }
}
