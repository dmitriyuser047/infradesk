package ru.bitec.app.ops
package infrastructure.http

import application.integration.{IntegrationActions, IntegrationError, IntegrationManagement}
import application.port.{IntegrationActionRepository, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration.{IntegrationActionCode, IntegrationActionExecution}
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.Json
import io.circe.syntax._
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.util.Try

final class IntegrationActionRoutes(actions: IntegrationActions[ConnectionIO],
  management: IntegrationManagement[ConnectionIO],
  repository: IntegrationActionRepository[ConnectionIO], runner: TransactionRunner[IO, ConnectionIO],
  authorization: OrganizationAuthorization, enabled: Boolean, logger: Logger[IO]) {
  import CirceEntityDecoder._
  import CirceEntityEncoder._
  import HttpJsonCodecs._

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "actions" =>
      authorization.require(request, OrganizationPermission.ExecuteOperations) { context =>
        if (!enabled) ServiceUnavailable(ApiErrorResponse("INTEGRATION_ACTIONS_DISABLED", "Integration actions are disabled"))
        else request.as[Json].attempt.flatMap { parsed => (ids(org, integration, obj), parsed) match {
          case (Some((organizationId, integrationId, objectId)), Right(body)) if organizationId == context.organizationId =>
            val decoded = for {
              rawRequest <- body.hcursor.get[String]("requestId").toOption
              requestId <- Try(UUID.fromString(rawRequest)).toOption
              rawAction <- body.hcursor.get[String]("action").toOption
              action <- IntegrationActionCode.fromCode(rawAction)
            } yield (requestId, action)
            decoded match {
              case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid integration action request"))
              case Some((requestId, action)) =>
                val submit = respond(runner.run(actions.request(context.actor,
                integrationId, objectId, requestId, action, body.hcursor.get[Boolean]("confirmDelete").toOption.contains(true))).flatMap(value =>
                  logger.info(s"integration.action.requested organizationId=$organizationId integrationId=$integrationId " +
                    s"inventoryObjectId=$objectId executionId=${value.id} actionCode=${action.code}")
                    .handleErrorWith(_ => IO.unit) *> Accepted(json(value))))
                if (action == IntegrationActionCode.NodeDelete)
                  authorization.require(request, OrganizationPermission.ManageIntegrations)(_ => submit)
                else submit
            }
          case (Some((organizationId, _, _)), _) if organizationId != context.organizationId =>
            NotFound(ApiErrorResponse("INTEGRATION_NOT_FOUND", "Integration was not found"))
          case _ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid integration action request"))
        }}
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "actions" / execution =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        ids(org, integration, execution) match {
          case Some((organizationId, integrationId, executionId)) if organizationId == context.organizationId =>
            respond(runner.run(repository.find(organizationId, integrationId, executionId)).flatMap {
              case Some(value) => Ok(json(value))
              case None => NotFound(ApiErrorResponse("INTEGRATION_ACTION_NOT_FOUND", "Action was not found"))
            })
          case Some(_) => NotFound(ApiErrorResponse("INTEGRATION_NOT_FOUND", "Integration was not found"))
          case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid action ID"))
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration / "actions" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        (Try(UUID.fromString(org)).toOption, Try(UUID.fromString(integration)).toOption,
          request.params.get("limit").fold(Option(50))(_.toIntOption.filter(n => n >= 1 && n <= 100))) match {
          case (Some(organizationId), Some(integrationId), Some(limit)) if organizationId == context.organizationId =>
            respond(runner.run(management.get(organizationId, integrationId)).flatMap {
              case None => NotFound(ApiErrorResponse("INTEGRATION_NOT_FOUND", "Integration was not found"))
              case Some(_) => runner.run(repository.recent(organizationId, integrationId, limit)).flatMap(values =>
                Ok(Json.obj("items" -> values.map(json).asJson)))
            })
          case (Some(_), Some(_), _) if org != context.organizationId.toString =>
            NotFound(ApiErrorResponse("INTEGRATION_NOT_FOUND", "Integration was not found"))
          case _ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid action list request"))
        }
      }
  }

  private def ids(a: String, b: String, c: String): Option[(UUID, UUID, UUID)] = for {
    x <- Try(UUID.fromString(a)).toOption
    y <- Try(UUID.fromString(b)).toOption
    z <- Try(UUID.fromString(c)).toOption
  } yield (x, y, z)

  private def respond(action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case error: IntegrationError =>
      val code = error.code
      if (code == "INTEGRATION_NOT_FOUND" || code == "INTEGRATION_OBJECT_NOT_FOUND")
        NotFound(ApiErrorResponse(code, error.getMessage))
      else if (code == "INTEGRATION_ACTION_ALREADY_RUNNING" || code == "INTEGRATION_ACTION_REQUEST_ID_CONFLICT" ||
        code == "INTEGRATION_ACTION_REQUIRES_REFRESH" ||
        code == "INTEGRATION_ACTION_CONFLICTS_WITH_DESIRED_STATE") Conflict(ApiErrorResponse(code, error.getMessage))
      else UnprocessableEntity(ApiErrorResponse(code, error.getMessage))
    case _ => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
  }

  private def json(value: IntegrationActionExecution): Json = Json.obj(
    "id" -> value.id.asJson,
    "requestId" -> value.requestId.asJson,
    "integrationId" -> value.integrationId.asJson,
    "inventoryObjectId" -> value.target.inventoryObjectId.asJson,
    "displayName" -> value.target.displayName.asJson,
    "requestedByUserId" -> value.requestedByUserId.asJson,
    "requestedByName" -> value.requestedByName.asJson,
    "action" -> value.action.code.asJson,
    "status" -> value.status.code.asJson,
    "createdAt" -> value.createdAt.asJson,
    "startedAt" -> value.startedAt.asJson,
    "finishedAt" -> value.finishedAt.asJson,
    "errorCode" -> value.errorCode.asJson,
    // Why the action exists, so history answers "who disabled this node?" without the logs.
    "source" -> value.source.code.asJson,
    "desiredStateId" -> value.desiredStateId.asJson,
    "desiredStateVersion" -> value.desiredStateVersion.asJson)
}
