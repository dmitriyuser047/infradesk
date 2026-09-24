package ru.bitec.app.ops
package infrastructure.http

import application.port.TransactionRunner
import application.workspace.{CreateEnvironment, CreateEnvironmentCommand, CreateProject, CreateProjectCommand, WorkspaceManagementError}
import cats.effect.IO
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, CreateEnvironmentRequest, CreateProjectRequest, HttpJsonCodecs}
import infrastructure.http.mapper.NavigationHttpMapper
import org.http4s.{HttpRoutes, Response}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

final class WorkspaceMutationRoutes[Tx[_]](
  createProject: CreateProject[Tx],
  createEnvironment: CreateEnvironment[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization
) {
  import HttpJsonCodecs._
  import CirceEntityDecoder._
  import CirceEntityEncoder._

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "projects" =>
      authorization.require(request, OrganizationPermission.ManageWorkspace) { context =>
        withOrganization(org) { _ =>
          request.as[CreateProjectRequest].attempt.flatMap {
            case Left(_) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid project request"))
            case Right(body) => respond(runner.run(createProject.execute(context.actor,
              CreateProjectCommand(body.code, body.name, body.description)))
              .flatMap(project => Created(NavigationHttpMapper.projectResponse(project))))
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "projects" / project / "environments" =>
      authorization.require(request, OrganizationPermission.ManageWorkspace) { context =>
        withProject(org, project) { (_, projectId) =>
          request.as[CreateEnvironmentRequest].attempt.flatMap {
            case Left(_) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid environment request"))
            case Right(body) => respond(runner.run(createEnvironment.execute(context.actor, projectId,
              CreateEnvironmentCommand(body.code, body.name, body.kind)))
              .flatMap(environment => Created(NavigationHttpMapper.environmentResponse(environment))))
          }
        }
      }
  }

  private def withOrganization(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption match {
      case Some(id) => next(id)
      case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid organizationId"))
    }

  private def withProject(org: String, project: String)(next: (UUID, UUID) => IO[Response[IO]]): IO[Response[IO]] =
    (Try(UUID.fromString(org)).toOption, Try(UUID.fromString(project)).toOption) match {
      case (Some(organizationId), Some(projectId)) => next(organizationId, projectId)
      case _ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid project path"))
    }

  private def respond(action: IO[Response[IO]]): IO[Response[IO]] =
    action.handleErrorWith {
      case error: WorkspaceManagementError =>
        val body = ApiErrorResponse(error.code, error.getMessage)
        error.code match {
          case "ORGANIZATION_NOT_FOUND" | "PROJECT_NOT_FOUND" => NotFound(body)
          case "PROJECT_CODE_ALREADY_EXISTS" | "ENVIRONMENT_CODE_ALREADY_EXISTS" => Conflict(body)
          case _ => BadRequest(body)
        }
      case _ => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
    }
}
