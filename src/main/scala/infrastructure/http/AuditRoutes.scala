package ru.bitec.app.ops
package infrastructure.http

import application.audit.ListAuditEvents
import application.port.TransactionRunner
import cats.effect.IO
import domain.audit.{AuditCursor, AuditEvent}
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, AuditEventResponse, HttpJsonCodecs}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Read-only access to the audit journal. The journal has no write API on purpose. */
final class AuditRoutes[Tx[_]](
  listAuditEvents: ListAuditEvents[Tx],
  transactionRunner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization
) {
  import HttpJsonCodecs._

  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")
  private val invalidCursor = ApiErrorResponse("INVALID_REQUEST", "Invalid audit cursor")
  private val invalidLimit = ApiErrorResponse("INVALID_REQUEST", "Invalid limit")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "audit-events" =>
      authorization.require(request, OrganizationPermission.ViewAudit) { context =>
        val params = request.uri.query.params
        (cursor(params.get("beforeOccurredAt"), params.get("beforeId")), limit(params.get("limit"))) match {
          case (Left(error), _) => BadRequest(error)
          case (_, Left(error)) => BadRequest(error)
          case (Right(before), Right(pageSize)) =>
            transactionRunner
              .run(listAuditEvents.execute(context.organizationId, before, pageSize))
              .attempt
              .flatMap {
                case Right(events) => Ok(events.map(toResponse))
                case Left(_) => InternalServerError(internalError)
              }
        }
      }
  }

  private def toResponse(event: AuditEvent): AuditEventResponse =
    AuditEventResponse(event.id, event.actorUserId, event.action.code, event.targetType.code,
      event.targetId, event.occurredAt)

  /** Both cursor parts travel together: a page continues after one exact row, never after a
    * timestamp that several rows may share.
    */
  private def cursor(
    occurredAt: Option[String],
    id: Option[String]
  ): Either[ApiErrorResponse, Option[AuditCursor]] =
    (occurredAt, id) match {
      case (None, None) => Right(None)
      case (Some(rawOccurredAt), Some(rawId)) =>
        (Try(Instant.parse(rawOccurredAt)).toOption, Try(UUID.fromString(rawId)).toOption) match {
          case (Some(parsedOccurredAt), Some(parsedId)) =>
            Right(Some(AuditCursor(parsedOccurredAt, parsedId)))
          case _ => Left(invalidCursor)
        }
      case _ => Left(invalidCursor)
    }

  private def limit(raw: Option[String]): Either[ApiErrorResponse, Int] = raw match {
    case None => Right(ListAuditEvents.DefaultLimit)
    case Some(value) =>
      Try(value.toInt).toOption.filter(parsed => parsed > 0 && parsed <= ListAuditEvents.MaxLimit)
        .toRight(invalidLimit)
  }
}
