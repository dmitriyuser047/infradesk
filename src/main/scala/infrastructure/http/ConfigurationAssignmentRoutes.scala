package ru.bitec.app.ops
package infrastructure.http

import application.configuration.{
  ConfigurationAssignmentError,
  ConfigurationAssignmentQueries,
  ConfigurationAssignmentValidation,
  ConfigurationAssignments
}
import application.port.{ConfigurationAssignmentCursor, ConfigurationAssignmentFilter, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import infrastructure.http.dto._
import io.circe.{Decoder, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Configuration assignments: which exact profile revision, with which values, a file on a node
  * should hold. Desired state only.
  *
  * Every route requires the configuration capability, reads included. The organization comes from
  * the access context, so another tenant's assignment, resource or profile is "not found". Nothing
  * here opens a connection, writes a file or runs a command, and values, templates, rendered text
  * and target paths are never logged.
  */
final class ConfigurationAssignmentRoutes[Tx[_]](
  assignments: ConfigurationAssignments[IO, Tx],
  queries: ConfigurationAssignmentQueries[Tx],
  reads: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._
  import ConfigurationAssignmentDtos.Codecs._

  private val invalidRequest = ApiErrorResponse("INVALID_REQUEST", "Invalid configuration assignment request")
  private val notFound = ApiErrorResponse(ConfigurationAssignmentError.NotFoundCode, "Configuration assignment was not found")

  /** 100 values of 4096 characters, as JSON, fit well inside this; anything larger is not a request. */
  private val MaxBodyBytes = 2 * 1024 * 1024

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" =>
      manage(request) { context =>
        page(request) match {
          case Left(error) => BadRequest(error)
          case Right((filter, cursor, size)) =>
            respond(request, "configuration.assignment.list", context)(
              reads.run(queries.list(context.organizationId, filter, cursor, size)).flatMap(items => Ok(items)))
        }
      }

    // Rendering a draft: nothing is stored, nothing leaves the process.
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" / "preview" =>
      manage(request) { context =>
        body[PreviewConfigurationAssignmentRequest](request) { body =>
          respond(request, "configuration.assignment.preview", context, "profileId" -> body.profileId)(
            if (body.profileRevisionNumber < 1) BadRequest(ReadModelHttp.invalid("profileRevisionNumber"))
            else assignments.preview(context.organizationId, body.profileId, body.profileRevisionNumber,
              ConfigurationDtos.values(body.values)).flatMap(preview => Ok(preview)))
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" =>
      manage(request) { context =>
        body[CreateConfigurationAssignmentRequest](request) { body =>
          ConfigurationAssignmentValidation.draft(body.profileRevisionNumber, body.targetPath, ConfigurationDtos.values(body.values)) match {
            case Left(error) => BadRequest(errorBody(error))
            case Right(draft) =>
              respond(request, "configuration.assignment.create", context,
                "resourceId" -> body.resourceId, "profileId" -> body.profileId, "profileRevisionNumber" -> draft.profileRevisionNumber)(
                assignments.create(context.actor, body.resourceId, body.profileId, draft)
                  .flatMap(id => detail(context.organizationId, id)(Created(_))))
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" / id =>
      manage(request) { context =>
        withAssignmentId(id) { assignmentId =>
          respond(request, "configuration.assignment.detail", context, "assignmentId" -> assignmentId)(
            detail(context.organizationId, assignmentId)(Ok(_)))
        }
      }

    case request @ PATCH -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" / id =>
      manage(request) { context =>
        withAssignmentId(id) { assignmentId =>
          body[UpdateConfigurationAssignmentRequest](request) { body =>
            ConfigurationAssignmentValidation.draft(body.profileRevisionNumber, body.targetPath, ConfigurationDtos.values(body.values)) match {
              case Left(error) => BadRequest(errorBody(error))
              case Right(draft) =>
                respond(request, "configuration.assignment.update", context,
                  "assignmentId" -> assignmentId, "profileRevisionNumber" -> draft.profileRevisionNumber)(
                  assignments.update(context.actor, assignmentId, body.expectedVersion, draft)
                    *> detail(context.organizationId, assignmentId)(Ok(_)))
            }
          }
        }
      }

    // Removal from the desired state, kept as history. The file on the server is not touched.
    case request @ DELETE -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" / id =>
      manage(request) { context =>
        withAssignmentId(id) { assignmentId =>
          expectedVersion(request.uri.query.params.get("expectedVersion")) match {
            case None => BadRequest(ReadModelHttp.invalid("expectedVersion"))
            case Some(expected) =>
              respond(request, "configuration.assignment.remove", context, "assignmentId" -> assignmentId)(
                assignments.remove(context.actor, assignmentId, expected) *> detail(context.organizationId, assignmentId)(Ok(_)))
          }
        }
      }
  }

  private def detail(organizationId: UUID, id: UUID)(answer: Json => IO[Response[IO]]): IO[Response[IO]] =
    reads.run(queries.detail(organizationId, id)).flatMap {
      case Some(value) => answer(detailEncoder(value))
      case None => NotFound(notFound)
    }

  /** `resourceId`, `profileId`, `limit` and a two-part cursor, `beforeCreatedAt` with `beforeId`,
    * that travel together so a page continues after one exact row.
    */
  private def page(request: Request[IO]): Either[ApiErrorResponse, (ConfigurationAssignmentFilter, Option[ConfigurationAssignmentCursor], Int)] = {
    val params = request.uri.query.params
    def id(name: String): Either[ApiErrorResponse, Option[UUID]] =
      params.get(name).traverse(raw => Try(UUID.fromString(raw)).toOption.toRight(ReadModelHttp.invalid(name)))
    val cursor: Either[ApiErrorResponse, Option[ConfigurationAssignmentCursor]] = (params.get("beforeCreatedAt"), params.get("beforeId")) match {
      case (None, None) => Right(None)
      case (Some(createdAt), Some(beforeId)) =>
        (Try(Instant.parse(createdAt)).toOption, Try(UUID.fromString(beforeId)).toOption)
          .mapN(ConfigurationAssignmentCursor.apply).map(Option(_)).toRight(ReadModelHttp.invalid("assignment cursor"))
      case _ => Left(ReadModelHttp.invalid("assignment cursor"))
    }
    val limit: Either[ApiErrorResponse, Int] = params.get("limit") match {
      case None => Right(ConfigurationAssignmentQueries.DefaultLimit)
      case Some(raw) =>
        Try(raw.toInt).toOption.filter(size => size > 0 && size <= ConfigurationAssignmentQueries.MaxLimit).toRight(ReadModelHttp.invalid("limit"))
    }
    (id("resourceId"), id("profileId"), cursor, limit).mapN((resourceId, profileId, before, size) =>
      (ConfigurationAssignmentFilter(resourceId, profileId), before, size))
  }

  private def expectedVersion(raw: Option[String]): Option[Int] =
    raw.flatMap(value => Try(value.toInt).toOption.filter(_ >= 1))

  private def errorBody(error: ConfigurationAssignmentError): Json =
    Json.obj(
      "code" -> Json.fromString(error.code),
      "message" -> Json.fromString(error.getMessage),
      "variableName" -> error.variableName.fold(Json.Null)(Json.fromString)
    )

  private def manage(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageConfigurations)(next)

  /** Reads at most the limit, then decodes; a larger body is refused unread. */
  private def body[A: Decoder](request: Request[IO])(next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(MaxBodyBytes.toLong + 1).compile.toVector.map(_.toArray).flatMap { bytes =>
      if (bytes.length > MaxBodyBytes) PayloadTooLarge(ApiErrorResponse("PAYLOAD_TOO_LARGE", "Request body is too large"))
      else io.circe.parser.decode[A](new String(bytes, StandardCharsets.UTF_8)).fold(_ => BadRequest(invalidRequest), next)
    }

  private def withAssignmentId(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption match {
      case Some(id) => next(id)
      case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid assignmentId"))
    }

  /** Business outcomes answer by their code: a missing or foreign reference is "not found", a lost
    * race or a state that forbids the change is a conflict, a bad value is a bad request. Anything
    * else is logged with safe identifiers only and answered without detail.
    */
  private def respond(request: Request[IO], operation: String, context: OrganizationAccessContext, ids: (String, Any)*)(
    action: IO[Response[IO]]
  ): IO[Response[IO]] =
    action.handleErrorWith {
      case error: ConfigurationAssignmentError => error.code match {
        case ConfigurationAssignmentError.NotFoundCode | ConfigurationAssignmentError.TargetNotFoundCode |
             application.configuration.ConfigurationError.NotFoundCode |
             application.configuration.ConfigurationError.RevisionNotFoundCode => NotFound(errorBody(error))
        case ConfigurationAssignmentError.PathConflictCode | ConfigurationAssignmentError.ChangedCode |
             ConfigurationAssignmentError.TargetInactiveCode |
             application.configuration.ConfigurationError.ArchivedCode => Conflict(errorBody(error))
        case _ => BadRequest(errorBody(error))
      }
      case error =>
        ReadModelHttp.failedAs(logger, "configuration.request.failed", request, operation,
          List[(String, Any)]("organizationId" -> context.organizationId, "actorUserId" -> context.user.id) ++ ids: _*)(error)
    }
}
