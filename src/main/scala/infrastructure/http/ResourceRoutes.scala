package ru.bitec.app.ops
package infrastructure.http

import application.port.TransactionRunner
import application.resource.{GetResource, GetResourceMetricHistory, InvalidMetricPeriodException, ListEnvironmentResources}
import infrastructure.http.mapper.MetricObservationHttpMapper
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
                                    getResourceMetricHistory: GetResourceMetricHistory[Tx],
                                    transactionRunner: TransactionRunner[IO, Tx]
                                  ) {

  import HttpJsonCodecs._

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "resources" / resourceIdValue / "metrics" =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(resourceIdValue, "resourceId"), parseInstant(request.uri.query.params.get("from"), "from"), parseInstant(request.uri.query.params.get("to"), "to")) match {
        case (Left(e), _, _, _) => BadRequest(e); case (_, Left(e), _, _) => BadRequest(e); case (_, _, Left(e), _) => BadRequest(e); case (_, _, _, Left(e)) => BadRequest(e)
        case (Right(org), Right(resource), Right(from), Right(to)) =>
          transactionRunner.run(getResourceMetricHistory.execute(org, resource, from, to)).attempt.flatMap {
            case Right(Some(values)) => Ok(values.map(MetricObservationHttpMapper.toResponse))
            case Right(None) => NotFound(ApiErrorResponse("RESOURCE_NOT_FOUND", "Resource was not found"))
            case Left(_: InvalidMetricPeriodException) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid period"))
            case Left(_) => internalServerError
          }
      }
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

  private def parseInstant(value: Option[String], parameterName: String): Either[ApiErrorResponse, java.time.Instant] =
    value.flatMap(v => Try(java.time.Instant.parse(v)).toOption).toRight(ApiErrorResponse("INVALID_REQUEST", s"Invalid $parameterName"))

  private def internalServerError: IO[org.http4s.Response[IO]] =
    InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
}
