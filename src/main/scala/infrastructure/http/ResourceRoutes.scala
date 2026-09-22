package ru.bitec.app.ops
package infrastructure.http

import application.port.TransactionRunner
import application.resource.{GetResource, ListEnvironmentResources}
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import infrastructure.http.mapper.ResourceHttpMapper

import cats.effect.IO
import cats.syntax.all._
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityEncoder._

import java.util.UUID
import scala.util.Try

final class ResourceRoutes[Tx[_]](
                                    getResource: GetResource[Tx],
                                    listEnvironmentResources: ListEnvironmentResources[Tx],
                                    transactionRunner: TransactionRunner[IO, Tx]
                                  ) {

  import HttpJsonCodecs._

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "resources" / resourceIdValue =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(resourceIdValue, "resourceId")) match {
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
        case (Right(organizationId), Right(resourceId)) =>
          transactionRunner
            .run(getResource.execute(organizationId, resourceId))
            .attempt
            .flatMap {
              case Right(Some(resource)) =>
                ResourceHttpMapper.toResponse(resource) match {
                  case Right(response) => Ok(response)
                  case Left(_) => internalServerError
                }
              case Right(None) => NotFound(ApiErrorResponse("RESOURCE_NOT_FOUND", "Resource was not found"))
              case Left(_) => internalServerError
            }
      }

    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "environments" / environmentIdValue / "resources" =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(environmentIdValue, "environmentId")) match {
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
        case (Right(organizationId), Right(environmentId)) =>
          transactionRunner
            .run(listEnvironmentResources.execute(organizationId, environmentId))
            .attempt
            .flatMap {
              case Right(resources) =>
                resources.traverse(ResourceHttpMapper.toResponse) match {
                  case Right(responses) => Ok(responses)
                  case Left(_) => internalServerError
                }
              case Left(_) => internalServerError
            }
      }
  }

  private def parseUuid(value: String, parameterName: String): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(value)).toEither.leftMap { _ =>
      ApiErrorResponse("INVALID_REQUEST", s"Invalid $parameterName")
    }

  private def internalServerError: IO[org.http4s.Response[IO]] =
    InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
}
