package ru.bitec.app.ops
package infrastructure.http

import application.incident.GetIncidentDetail
import application.monitor.{AcknowledgeIncident, CreateMaintenanceWindow, MaintenanceError, MaintenanceWindows}
import application.port.{MaintenanceWindowView, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs, InfrastructureContextResponses}
import io.circe.{Decoder, Json}
import io.circe.syntax._
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.util.Try

/**
 * Maintenance windows (`/maintenance-windows`) and incident acknowledgement
 * (`/incidents/{id}/acknowledge`). Reading windows needs membership; planning, cancelling and
 * acknowledging need the monitoring capability.
 */
final class MaintenanceRoutes[Tx[_]](
  windows: MaintenanceWindows[Tx],
  acknowledge: AcknowledgeIncident[Tx],
  incidents: GetIncidentDetail[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._
  import InfrastructureContextResponses.Codecs._

  private final case class CreateBody(resourceId: UUID, startsAt: Instant, endsAt: Instant, reason: String)
  private implicit val createBodyDecoder: Decoder[CreateBody] =
    Decoder.forProduct4("resourceId", "startsAt", "endsAt", "reason")(CreateBody.apply)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "maintenance-windows" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { context =>
        withOrganization(org, context.organizationId) {
          respond(request, "maintenance.list")(windows.list(context.organizationId).flatMap { case (now, values) =>
            Ok(Json.obj("now" -> now.asJson, "windows" -> values.map(window(_, now)).asJson))
          })
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "maintenance-windows" =>
      authorization.require(request, OrganizationPermission.ManageMonitoring) { context =>
        withOrganization(org, context.organizationId) {
          request.as[CreateBody].attempt.flatMap {
            case Left(_) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid maintenance window request"))
            case Right(body) => respond(request, "maintenance.create")(
              windows.create(context.actor, CreateMaintenanceWindow(body.resourceId, body.startsAt, body.endsAt, body.reason))
                .flatMap(created => IO.realTimeInstant.flatMap(now => Created(window(created, now)))))
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "maintenance-windows" / id / "cancel" =>
      authorization.require(request, OrganizationPermission.ManageMonitoring) { context =>
        withOrganization(org, context.organizationId) {
          uuid(id).fold(BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid maintenance window id"))) { windowId =>
            respond(request, "maintenance.cancel")(windows.cancel(context.actor, windowId)
              .flatMap(cancelled => IO.realTimeInstant.flatMap(now => Ok(window(cancelled, now)))))
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "incidents" / id / "acknowledge" =>
      authorization.require(request, OrganizationPermission.ManageMonitoring) { context =>
        withOrganization(org, context.organizationId) {
          uuid(id).fold(BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid incident id"))) { incidentId =>
            // The answer is the incident as stored afterwards, whoever acknowledged it first.
            respond(request, "incidents.acknowledge")(acknowledge.execute(context.actor, incidentId) *>
              runner.run(incidents.execute(context.organizationId, incidentId)).flatMap {
                case Some(item) => Ok(InfrastructureContextResponses.incident(item))
                case None => NotFound(ApiErrorResponse("INCIDENT_NOT_FOUND", "Incident was not found"))
              })
          }
        }
      }
  }

  private def window(view: MaintenanceWindowView, now: Instant): Json = {
    val value = view.window
    Json.obj(
      "id" -> value.id.asJson,
      "resource" -> Json.obj("id" -> value.resourceId.asJson, "name" -> view.resourceName.asJson,
        "resourceTypeCode" -> view.resourceTypeCode.asJson),
      "startsAt" -> value.startsAt.asJson,
      "endsAt" -> value.endsAt.asJson,
      "effectiveEnd" -> value.effectiveEnd.asJson,
      "state" -> value.state(now).code.asJson,
      "reason" -> value.reason.asJson,
      "createdByName" -> view.createdByName.asJson,
      "createdAt" -> value.createdAt.asJson,
      "cancelledAt" -> value.cancelledAt.asJson,
      "cancelledByName" -> view.cancelledByName.asJson
    )
  }

  private def respond(request: Request[IO], operation: String)(action: IO[Response[IO]]): IO[Response[IO]] =
    action.handleErrorWith {
      case error: MaintenanceError =>
        val body = ApiErrorResponse(error.code, error.getMessage)
        error.code match {
          case "RESOURCE_NOT_FOUND" | "MAINTENANCE_WINDOW_NOT_FOUND" | "INCIDENT_NOT_FOUND" => NotFound(body)
          case "MAINTENANCE_WINDOW_FINISHED" | "INCIDENT_NOT_OPEN" => Conflict(body)
          case _ => BadRequest(body)
        }
      case failure =>
        logger.error(failure)(s"$operation.failed path=${request.uri.path}") *>
          InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
    }

  private def withOrganization(raw: String, allowed: UUID)(next: => IO[Response[IO]]): IO[Response[IO]] =
    if (uuid(raw).contains(allowed)) next else BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid organizationId"))

  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption
}
