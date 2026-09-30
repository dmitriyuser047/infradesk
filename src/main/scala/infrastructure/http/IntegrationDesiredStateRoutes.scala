package ru.bitec.app.ops
package infrastructure.http

import application.integration.{IntegrationDesiredStates, IntegrationError}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration.{IntegrationDesiredNodeState, IntegrationManagementMode}
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.Json
import io.circe.syntax._
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.util.Try

/** Management mode and desired node state. These requests only record intent: no handler creates
  * an action or contacts Remnawave. Because intent leads to remote writes, every mutation needs
  * both MANAGE_INTEGRATIONS and EXECUTE_OPERATIONS; the backend decides, whatever the UI shows.
  */
final class IntegrationDesiredStateRoutes[Tx[_]](desired: IntegrationDesiredStates[Tx],
  runner: TransactionRunner[IO, Tx], authorization: OrganizationAuthorization, logger: Logger[IO]) {
  import CirceEntityDecoder._
  import CirceEntityEncoder._
  import HttpJsonCodecs._

  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid desired state request")
  private val notFound = ApiErrorResponse("INTEGRATION_NOT_FOUND", "Integration was not found")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ PUT -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "management-mode" =>
      mutation(request, org, integration, None) { (context, integrationId, _) =>
        field(request, "mode")(raw => IntegrationManagementMode.fromCode(raw).toOption) { mode =>
          runner.run(desired.setMode(context.actor, integrationId, mode)).flatMap(value =>
            Ok(IntegrationRoutes.toResponse(value)))
        }
      }

    case request @ PUT -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "desired-state" =>
      mutation(request, org, integration, Some(obj)) { (context, integrationId, objectId) =>
        field(request, "state")(IntegrationDesiredNodeState.fromCode) { state =>
          runner.run(desired.set(context.actor, integrationId, objectId, state)).flatMap(view =>
            log(s"integration.desired_state.set organizationId=${context.organizationId} integrationId=$integrationId " +
              s"inventoryObjectId=$objectId desiredStateId=${view.id} desiredStateVersion=${view.version} " +
              s"desiredState=${view.state.code}") *> Ok(IntegrationInventoryJson.desiredState(view)))
        }
      }

    case request @ DELETE -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "desired-state" =>
      mutation(request, org, integration, Some(obj)) { (context, integrationId, objectId) =>
        runner.run(desired.remove(context.actor, integrationId, objectId)) *>
          log(s"integration.desired_state.removed organizationId=${context.organizationId} " +
            s"integrationId=$integrationId inventoryObjectId=$objectId") *> NoContent()
      }
  }

  private def mutation(request: Request[IO], org: String, integration: String, obj: Option[String])(
    next: (OrganizationAccessContext, UUID, UUID) => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageIntegrations) { _ =>
      authorization.require(request, OrganizationPermission.ExecuteOperations) { context =>
        (uuid(org), uuid(integration), obj.fold(Option(context.organizationId))(uuid)) match {
          case (Some(organizationId), Some(integrationId), Some(objectId)) if organizationId == context.organizationId =>
            respond(next(context, integrationId, objectId))
          case (Some(_), Some(_), Some(_)) => NotFound(notFound)
          case _ => BadRequest(invalid)
        }
      }
    }

  private def field[A](request: Request[IO], name: String)(parse: String => Option[A])(
    next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.as[Json].attempt.map(_.toOption.flatMap(_.hcursor.get[String](name).toOption).flatMap(parse)).flatMap {
      case Some(value) => next(value)
      case None => BadRequest(invalid)
    }

  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption

  private def respond(action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case error: IntegrationError => error.code match {
      case "INTEGRATION_NOT_FOUND" | "INTEGRATION_OBJECT_NOT_FOUND" => NotFound(ApiErrorResponse(error.code, error.getMessage))
      case "INTEGRATION_ACTION_ALREADY_RUNNING" | IntegrationDesiredStates.RequiresSync |
          IntegrationDesiredStates.ModeRequired | IntegrationDesiredStates.ManagementActive =>
        Conflict(ApiErrorResponse(error.code, error.getMessage))
      case IntegrationDesiredStates.SubsystemDisabled => ServiceUnavailable(ApiErrorResponse(error.code, error.getMessage))
      case code => UnprocessableEntity(ApiErrorResponse(code, error.getMessage))
    }
    case _ => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
  }

  private def log(message: String): IO[Unit] = logger.info(message).handleErrorWith(_ => IO.unit)
}
