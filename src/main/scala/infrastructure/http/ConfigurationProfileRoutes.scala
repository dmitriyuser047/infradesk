package ru.bitec.app.ops
package infrastructure.http

import application.configuration.{
  ConfigurationError,
  ConfigurationProfileDetail,
  ConfigurationProfileManagement,
  ConfigurationProfileQueries,
  ConfigurationProfileValidation,
  CreateConfigurationProfileCommand
}
import application.port.{ConfigurationActor, ConfigurationRevisionView, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.configuration.{ConfigurationDiagnostic, ConfigurationTemplateRenderer, ConfigurationValidation, ValidatedConfiguration}
import infrastructure.http.dto._
import io.circe.{Decoder, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

/** Configuration profiles and their immutable revisions.
  *
  * Every route requires the configuration capability, reads included: server configuration is
  * sensitive even without secrets. The organization comes from the access context, so another
  * tenant's profile is "not found". Content is validated before any transaction; nothing here
  * runs a command, opens a connection or writes a file. Templates, values and rendered text are
  * never logged.
  */
final class ConfigurationProfileRoutes[Tx[_]](
  queries: ConfigurationProfileQueries[Tx],
  management: ConfigurationProfileManagement[Tx],
  writes: TransactionRunner[IO, Tx],
  reads: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._
  import ConfigurationDtos.Codecs._

  private val invalidRequest = ApiErrorResponse("INVALID_REQUEST", "Invalid configuration profile request")
  private val notFound = ApiErrorResponse(ConfigurationError.NotFoundCode, "Configuration profile was not found")
  private val revisionNotFound = ApiErrorResponse(ConfigurationError.RevisionNotFoundCode, "Configuration revision was not found")

  /** A 256 KiB template may grow when escaped as JSON; anything past this is not a profile. */
  private val MaxBodyBytes = 2 * 1024 * 1024

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" =>
      manage(request) { context =>
        val params = request.uri.query.params
        (archivedFilter(params.get("archived")), limit(params.get("limit"))) match {
          case (Some(archived), Some(size)) =>
            respond(request, "configuration.list", context)(
              reads.run(queries.list(context.organizationId, archived, size)).flatMap(profiles => Ok(profiles)))
          case _ => BadRequest(invalidRequest)
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / "validate" =>
      manage(request) { _ =>
        body[ValidateConfigurationRequest](request) { body =>
          ConfigurationDtos.definitions(body.variables) match {
            case Left(_) => BadRequest(invalidRequest)
            case Right(definitions) => Ok(validation(body.template, definitions, body.previewValues))
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" =>
      manage(request) { context =>
        body[CreateConfigurationProfileRequest](request) { body =>
          (ConfigurationProfileValidation.code(body.code), ConfigurationProfileValidation.metadata(body.name, body.description)) match {
            case (Left(error), _) => BadRequest(ApiErrorResponse(error.code, error.getMessage))
            case (_, Left(error)) => BadRequest(ApiErrorResponse(error.code, error.getMessage))
            case (Right(code), Right(metadata)) =>
              withContent(body.template, body.variables) { content =>
                respond(request, "configuration.create", context)(
                  writes.run(management.create(context.actor, CreateConfigurationProfileCommand(code, metadata, content)))
                    .flatMap { case (profile, revision) =>
                      Created(ConfigurationProfileDetail(profile, ConfigurationRevisionView(revision, actorOf(context))))
                    })
              }
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / id =>
      manage(request) { context =>
        withProfileId(id) { profileId =>
          respond(request, "configuration.detail", context, profileId)(
            reads.run(queries.detail(context.organizationId, profileId)).flatMap {
              case Some(detail) => Ok(detail)
              case None => NotFound(notFound)
            })
        }
      }

    case request @ PATCH -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / id =>
      manage(request) { context =>
        withProfileId(id) { profileId =>
          body[UpdateConfigurationProfileRequest](request) { body =>
            ConfigurationProfileValidation.metadata(body.name, body.description) match {
              case Left(error) => BadRequest(ApiErrorResponse(error.code, error.getMessage))
              case Right(metadata) =>
                respond(request, "configuration.update", context, profileId)(
                  writes.run(management.updateMetadata(context.actor, profileId, metadata))
                    .flatMap(profile => Ok(ConfigurationDtos.Codecs.profileJson(profile, None))))
            }
          }
        }
      }

    // Archiving, not deletion: the profile and every revision stay, and new revisions stop.
    case request @ DELETE -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / id =>
      manage(request) { context =>
        withProfileId(id) { profileId =>
          respond(request, "configuration.archive", context, profileId)(
            writes.run(management.archive(context.actor, profileId))
              .flatMap(profile => Ok(ConfigurationDtos.Codecs.profileJson(profile, None))))
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / id / "revisions" =>
      manage(request) { context =>
        withProfileId(id) { profileId =>
          body[CreateConfigurationRevisionRequest](request) { body =>
            withContent(body.template, body.variables) { content =>
              // The number is the server's: whatever the client last saw, this is the next one.
              respond(request, "configuration.revision.create", context, profileId)(
                writes.run(management.appendRevision(context.actor, profileId, content))
                  .flatMap(revision => Created(ConfigurationRevisionView(revision, actorOf(context)))))
            }
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / id / "revisions" =>
      manage(request) { context =>
        withProfileId(id) { profileId =>
          val params = request.uri.query.params
          (revisionCursor(params.get("before")), limit(params.get("limit"))) match {
            case (Some(before), Some(size)) =>
              respond(request, "configuration.revision.list", context, profileId)(
                reads.run(queries.revisions(context.organizationId, profileId, before, size)).flatMap {
                  case Some(revisions) => Ok(revisions)
                  case None => NotFound(notFound)
                })
            case _ => BadRequest(invalidRequest)
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / id / "revisions" / number =>
      manage(request) { context =>
        withProfileId(id) { profileId =>
          Try(number.toInt).toOption.filter(_ >= 1) match {
            case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid revisionNumber"))
            case Some(revisionNumber) =>
              respond(request, "configuration.revision.detail", context, profileId)(
                reads.run(queries.revision(context.organizationId, profileId, revisionNumber)).flatMap {
                  case Some(revision) => Ok(revision)
                  case None => NotFound(revisionNotFound)
                })
          }
        }
      }
  }

  /** Validation, and a preview when every referenced variable resolves. Nothing is stored. */
  private def validation(
    template: String,
    definitions: List[domain.configuration.ConfigurationVariableDefinition],
    previewValues: Option[List[ConfigurationValueRequest]]
  ): ConfigurationValidationResponse =
    ConfigurationValidation.validate(template, definitions) match {
      case Left(errors) =>
        ConfigurationValidationResponse(valid = false, referencedVariables(template), errors, None, None)
      case Right(content) =>
        ConfigurationTemplateRenderer.render(content, ConfigurationDtos.values(previewValues.getOrElse(List.empty))) match {
          case Right(rendered) => ConfigurationValidationResponse(valid = true, content.referencedVariables, content.warnings, Some(rendered), None)
          case Left(error) => ConfigurationValidationResponse(valid = true, content.referencedVariables, content.warnings, None, Some(error))
        }
    }

  /** What a template references even when it does not validate, for the editor's hints. */
  private def referencedVariables(template: String): List[String] =
    domain.configuration.ConfigurationTemplateParser.parse(template).toOption.toList.flatten.collect {
      case domain.configuration.TemplatePart.Placeholder(name, _, _) => name
    }.distinct

  private def withContent(template: String, variables: List[ConfigurationVariableRequest])(
    next: ValidatedConfiguration => IO[Response[IO]]
  ): IO[Response[IO]] =
    ConfigurationDtos.definitions(variables) match {
      case Left(_) => BadRequest(invalidRequest)
      case Right(definitions) =>
        ConfigurationValidation.validate(template, definitions) match {
          case Right(content) => next(content)
          case Left(errors) => BadRequest(invalidContent(errors))
        }
    }

  private def invalidContent(errors: List[ConfigurationDiagnostic]): Json =
    Json.obj(
      "code" -> Json.fromString("INVALID_CONFIGURATION"),
      "message" -> Json.fromString("The configuration content is not valid"),
      "diagnostics" -> io.circe.Encoder.encodeList(diagnosticEncoder)(errors)
    )

  private def manage(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageConfigurations)(next)

  private def actorOf(context: OrganizationAccessContext): ConfigurationActor =
    ConfigurationActor(context.user.id, context.user.displayName)

  /** Reads at most the limit, then decodes; a larger body is refused unread. */
  private def body[A: Decoder](request: Request[IO])(next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(MaxBodyBytes.toLong + 1).compile.toVector.map(_.toArray).flatMap { bytes =>
      if (bytes.length > MaxBodyBytes) PayloadTooLarge(ApiErrorResponse("PAYLOAD_TOO_LARGE", "Request body is too large"))
      else io.circe.parser.decode[A](new String(bytes, StandardCharsets.UTF_8)).fold(_ => BadRequest(invalidRequest), next)
    }

  private def withProfileId(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption match {
      case Some(id) => next(id)
      case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid profileId"))
    }

  private def archivedFilter(raw: Option[String]): Option[Boolean] = raw match {
    case None | Some("false") => Some(false)
    case Some("true") => Some(true)
    case _ => None
  }

  private def limit(raw: Option[String]): Option[Int] = raw match {
    case None => Some(ConfigurationProfileQueries.DefaultLimit)
    case Some(value) => Try(value.toInt).toOption.filter(size => size > 0 && size <= ConfigurationProfileQueries.MaxLimit)
  }

  private def revisionCursor(raw: Option[String]): Option[Option[Int]] = raw match {
    case None => Some(None)
    case Some(value) => Try(value.toInt).toOption.filter(_ >= 1).map(Some(_))
  }

  /** Business outcomes answer by their code; anything else is logged with safe identifiers and
    * answered without detail. Expected outcomes are not logged as failures.
    */
  private def respond(request: Request[IO], operation: String, context: OrganizationAccessContext, profileId: UUID*)(
    action: IO[Response[IO]]
  ): IO[Response[IO]] =
    action.handleErrorWith {
      case error: ConfigurationError => error.code match {
        case ConfigurationError.NotFoundCode => NotFound(notFound)
        case ConfigurationError.CodeExistsCode | ConfigurationError.ArchivedCode => Conflict(ApiErrorResponse(error.code, error.getMessage))
        case code => BadRequest(ApiErrorResponse(code, error.getMessage))
      }
      case error =>
        ReadModelHttp.failedAs(logger, "configuration.request.failed", request, operation,
          List[(String, Any)]("organizationId" -> context.organizationId, "actorUserId" -> context.user.id) ++
            profileId.map(id => "profileId" -> id): _*)(error)
    }
}
