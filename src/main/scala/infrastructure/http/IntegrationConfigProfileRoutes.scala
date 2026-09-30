package ru.bitec.app.ops
package infrastructure.http

import application.configuration.ConfigurationError
import application.integration.{AdoptIntegrationConfigProfile, IntegrationConfigProfiles, IntegrationError}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration.IntegrationConfigDeployment
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.UUID
import scala.util.Try

/** Point reads of decrypted config and explicit mutations require both configuration and integration rights. */
final class IntegrationConfigProfileRoutes[Tx[_]](service: IntegrationConfigProfiles[Tx],
  authorization: OrganizationAuthorization) {
  import HttpJsonCodecs._

  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid config profile request")
  private val missing = ApiErrorResponse("INTEGRATION_CONFIG_PROFILE_NOT_FOUND", "Config profile was not found")
  private val maxRequestBytes = 256 * 1024 + 4096

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "config-management" / "adopt" =>
      access(request, org, integration, obj, deploy = false) { (ctx, id, objectId) =>
        body(request).flatMap { json =>
          val c = json.hcursor
          (c.get[String]("code"), c.get[String]("name"), c.get[Option[String]]("description")) match {
            case (Right(code), Right(name), Right(description)) =>
              service.adopt(ctx.actor, id, objectId, AdoptIntegrationConfigProfile(code, name, description))
                .flatMap(profile => Created(Json.obj("profileId" -> profile.id.toString.asJson,
                  "kind" -> profile.kind.code.asJson, "latestRevisionNumber" -> Json.fromInt(1))))
            case _ => BadRequest(invalid)
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "config-management" =>
      access(request, org, integration, obj, deploy = false) { (ctx, id, objectId) =>
        service.managed(ctx.organizationId, id, objectId).flatMap {
          case None => NotFound(missing)
          case Some(view) => Ok(Json.obj(
            "bindingId" -> view.binding.id.toString.asJson,
            "profile" -> Json.obj("id" -> view.profile.id.toString.asJson,
              "code" -> view.profile.code.asJson, "name" -> view.profile.name.asJson,
              "description" -> view.profile.description.asJson, "kind" -> view.profile.kind.code.asJson,
              "latestRevisionNumber" -> Json.fromInt(view.profile.latestRevisionNumber)),
            "latestSha256" -> view.latestSha256.asJson,
            "remoteSha256" -> view.remoteSha256.asJson,
            "latestDeployedSha256" -> view.latestDeployedSha256.asJson,
            "status" -> view.status.code.asJson,
            "nodesUsingProfile" -> Json.fromInt(view.nodesUsingProfile),
            "latestDeployment" -> view.latestDeployment.map(deployment).asJson))
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "config-management" / "revisions" =>
      access(request, org, integration, obj, deploy = false) { (ctx, id, objectId) =>
        val limit = request.params.get("limit").flatMap(_.toIntOption).getOrElse(50)
        val before = request.params.get("before").flatMap(_.toIntOption)
        if (limit < 1 || limit > 100 || before.exists(_ < 1)) BadRequest(invalid)
        else service.revisions(ctx.organizationId, id, objectId, before, limit).flatMap(rows => Ok(Json.obj(
          "items" -> Json.arr(rows.map(row => Json.obj(
            "revisionNumber" -> Json.fromInt(row.revisionNumber),
            "createdBy" -> row.createdBy.displayName.asJson,
            "createdAt" -> row.createdAt.toString.asJson)): _*))))
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "config-management" / "revisions" / revision =>
      access(request, org, integration, obj, deploy = false) { (ctx, id, objectId) =>
        revision.toIntOption.filter(_ > 0) match {
          case None => BadRequest(invalid)
          case Some(number) => service.revisionContent(ctx.organizationId, id, objectId, number).flatMap { value =>
            parse(value.canonicalJson).fold(_ => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error")),
              config => Ok(Json.obj("revisionNumber" -> Json.fromInt(number),
                "sha256" -> value.contentSha256.asJson, "config" -> config)))
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "config-management" / "revisions" =>
      access(request, org, integration, obj, deploy = false) { (ctx, id, objectId) =>
        body(request).flatMap { json =>
          if (json.hcursor.downField("variables").succeeded) BadRequest(invalid)
          else json.hcursor.downField("config").focus match {
            case Some(config) => service.appendRevision(ctx.actor, id, objectId, config)
              .flatMap(number => Created(Json.obj("revisionNumber" -> Json.fromInt(number))))
            case None => BadRequest(invalid)
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "config-management" / "revisions" / revision / "preview" =>
      access(request, org, integration, obj, deploy = false) { (ctx, id, objectId) =>
        revision.toIntOption.filter(_ > 0) match {
          case None => BadRequest(invalid)
          case Some(number) => service.preview(ctx.organizationId, id, objectId, number).flatMap(value => Ok(Json.obj(
            "revisionNumber" -> Json.fromInt(value.revisionNumber),
            "localSha256" -> value.localSha256.asJson,
            "remoteSha256" -> value.remoteSha256.asJson,
            "remoteUpdatedAt" -> value.remoteUpdatedAt.map(_.toString).asJson,
            "changed" -> value.changed.asJson,
            "diff" -> Json.obj("text" -> value.diff.text.asJson,
              "truncated" -> value.diff.truncated.asJson,
              "approximate" -> value.diff.approximate.asJson,
              "addedLines" -> Json.fromInt(value.diff.addedLines),
              "removedLines" -> Json.fromInt(value.diff.removedLines)))))
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "config-management" / "revisions" / revision / "deploy" =>
      access(request, org, integration, obj, deploy = true) { (ctx, id, objectId) =>
        revision.toIntOption.filter(_ > 0) match {
          case None => BadRequest(invalid)
          case Some(number) => body(request).flatMap { json =>
            json.hcursor.get[String]("requestId").toOption.flatMap(uuid(_)) match {
              case None => BadRequest(invalid)
              case Some(requestId) => service.requestDeploy(ctx.actor, id, objectId, number, requestId)
                .flatMap(value => Accepted(deployment(value)))
            }
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / integration /
        "inventory" / "objects" / obj / "config-management" / "deployments" =>
      access(request, org, integration, obj, deploy = false) { (ctx, id, objectId) =>
        val limit = request.params.get("limit").flatMap(_.toIntOption).getOrElse(50)
        if (limit < 1 || limit > 100) BadRequest(invalid)
        else service.history(ctx.organizationId, id, objectId, limit).flatMap(rows =>
          Ok(Json.obj("items" -> Json.arr(rows.map(deployment): _*))))
      }
  }

  private def deployment(value: IntegrationConfigDeployment): Json = Json.obj(
    "id" -> value.id.toString.asJson, "requestId" -> value.requestId.toString.asJson,
    "revisionNumber" -> Json.fromInt(value.revisionNumber), "status" -> value.status.code.asJson,
    "requestedByUserId" -> value.requestedByUserId.toString.asJson,
    "createdAt" -> value.createdAt.toString.asJson,
    "startedAt" -> value.startedAt.map(_.toString).asJson,
    "finishedAt" -> value.finishedAt.map(_.toString).asJson,
    "expectedRemoteSha256" -> value.expectedRemoteSha256.asJson,
    "desiredSha256" -> value.desiredSha256.asJson,
    "errorCode" -> value.errorCode.asJson)

  private def access(request: Request[IO], org: String, integration: String, obj: String, deploy: Boolean)(
    next: (OrganizationAccessContext, UUID, UUID) => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageIntegrations) { _ =>
      authorization.require(request, OrganizationPermission.ManageConfigurations) { context =>
        val authorized = if (deploy) authorization.require(request, OrganizationPermission.ExecuteOperations)(
          _ => nextIfValid(context, org, integration, obj)(next))
        else nextIfValid(context, org, integration, obj)(next)
        respond(authorized)
      }
    }

  private def nextIfValid(context: OrganizationAccessContext, org: String,
    integration: String, obj: String)(next: (OrganizationAccessContext, UUID, UUID) => IO[Response[IO]]) =
    (uuid(org), uuid(integration), uuid(obj)) match {
      case (Some(o), Some(i), Some(x)) if o == context.organizationId => next(context, i, x)
      case (Some(_), Some(_), Some(_)) => NotFound(missing)
      case _ => BadRequest(invalid)
    }

  private def body(request: Request[IO]): IO[Json] =
    request.body.take(maxRequestBytes.toLong + 1).compile.to(Array).flatMap { bytes =>
      if (bytes.length > maxRequestBytes) IO.raiseError(IntegrationError("INVALID_REQUEST", "Request is too large"))
      else IO.delay(StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString).flatMap(raw =>
          parse(raw).fold(_ => IO.raiseError(IntegrationError("INVALID_REQUEST", "Invalid JSON")), IO.pure))
    }

  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption

  private def respond(action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case error: IntegrationError => error.code match {
      case "INTEGRATION_CONFIG_PROFILE_NOT_FOUND" | "INTEGRATION_NOT_FOUND" =>
        NotFound(ApiErrorResponse(error.code, error.getMessage))
      case "INTEGRATION_CONFIG_PROFILE_ALREADY_MANAGED" | "INTEGRATION_CONFIG_PROFILE_CHANGED" |
          "INTEGRATION_CONFIG_DEPLOYMENT_ALREADY_RUNNING" | "CONFIG_DEPLOYMENT_REQUEST_ID_CONFLICT" |
          "INTEGRATION_CONFIG_REQUIRES_REFRESH" | "INTEGRATION_CONFIG_PROFILE_INACTIVE" =>
        Conflict(ApiErrorResponse(error.code, error.getMessage))
      case "INVALID_REQUEST" | "INVALID_INTEGRATION_CONFIG" => BadRequest(ApiErrorResponse(error.code, error.getMessage))
      case _ => UnprocessableEntity(ApiErrorResponse(error.code, error.getMessage))
    }
    case error: ConfigurationError if error.code == ConfigurationError.InvalidMetadataCode =>
      BadRequest(ApiErrorResponse(error.code, error.getMessage))
    case error: ConfigurationError => Conflict(ApiErrorResponse(error.code, error.getMessage))
    case _ => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
  }
}
