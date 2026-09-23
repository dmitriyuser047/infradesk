package ru.bitec.app.ops
package infrastructure.http

import application.navigation.{GetEnvironmentContext, GetOrganization, ListEnvironments, ListProjects}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import infrastructure.http.mapper.NavigationHttpMapper
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

final class NavigationRoutes[Tx[_]](
  getOrganization: GetOrganization[Tx],
  listProjects: ListProjects[Tx],
  listEnvironments: ListEnvironments[Tx],
  getEnvironmentContext: GetEnvironmentContext[Tx],
  transactionRunner: TransactionRunner[IO, Tx]
) {
  import HttpJsonCodecs._

  private val organizationNotFound = ApiErrorResponse(
    "ORGANIZATION_NOT_FOUND",
    "Organization was not found"
  )
  private val projectNotFound = ApiErrorResponse(
    "PROJECT_NOT_FOUND",
    "Project was not found"
  )
  private val internalError = ApiErrorResponse(
    "INTERNAL_ERROR",
    "Internal server error"
  )
  private val environmentNotFound = ApiErrorResponse(
    "ENVIRONMENT_NOT_FOUND",
    "Environment was not found"
  )

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue =>
      parseUuid(organizationIdValue, "organizationId") match {
        case Left(error) => BadRequest(error)
        case Right(organizationId) =>
          transactionRunner.run(getOrganization.execute(organizationId)).attempt.flatMap {
            case Right(Some(organization)) => Ok(NavigationHttpMapper.organizationResponse(organization))
            case Right(None) => NotFound(organizationNotFound)
            case Left(_) => InternalServerError(internalError)
          }
      }

    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "projects" =>
      parseUuid(organizationIdValue, "organizationId") match {
        case Left(error) => BadRequest(error)
        case Right(organizationId) =>
          transactionRunner.run(listProjects.execute(organizationId)).attempt.flatMap {
            case Right(Some(projects)) => Ok(projects.map(NavigationHttpMapper.projectResponse))
            case Right(None) => NotFound(organizationNotFound)
            case Left(_) => InternalServerError(internalError)
          }
      }

    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "projects" / projectIdValue / "environments" =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(projectIdValue, "projectId")) match {
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
        case (Right(organizationId), Right(projectId)) =>
          transactionRunner.run(listEnvironments.execute(organizationId, projectId)).attempt.flatMap {
            case Right(Some(environments)) => Ok(environments.map(NavigationHttpMapper.environmentResponse))
            case Right(None) => NotFound(projectNotFound)
            case Left(_) => InternalServerError(internalError)
          }
      }

    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "environments" / environmentIdValue / "context" =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(environmentIdValue, "environmentId")) match {
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
        case (Right(organizationId), Right(environmentId)) =>
          transactionRunner.run(getEnvironmentContext.execute(organizationId, environmentId)).attempt.flatMap {
            case Right(Some(context)) => Ok(NavigationHttpMapper.environmentContextResponse(context))
            case Right(None) => NotFound(environmentNotFound)
            case Left(_) => InternalServerError(internalError)
          }
      }
  }

  private def parseUuid(
    value: String,
    parameterName: String
  ): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(value)).toEither.leftMap { _ =>
      ApiErrorResponse("INVALID_REQUEST", s"Invalid $parameterName")
    }
}
