package ru.bitec.app.ops
package infrastructure.http

import application.connection.{GetConnection, ListConnections}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import infrastructure.http.mapper.ConnectionHttpMapper
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.UUID
import scala.util.Try

final class ConnectionRoutes[Tx[_]](
  getConnection: GetConnection[Tx],
  listConnections: ListConnections[Tx],
  transactionRunner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO] = Slf4jLogger.getLoggerFromName[IO]("infrastructure.http.ConnectionRoutes")
) {
  import HttpJsonCodecs._

  private val connectionNotFound = ApiErrorResponse(
    "CONNECTION_NOT_FOUND",
    "Connection was not found"
  )

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "connections" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { _ =>
      parseUuid(organizationIdValue, "organizationId") match {
        case Right(organizationId) =>
          IO.monotonic.flatMap { started =>
            transactionRunner.run(listConnections.execute(organizationId)).attempt.flatMap {
              case Right(connections) => Ok(connections.map(ConnectionHttpMapper.toResponse))
              case Left(error) => ReadModelHttp.failedAs(logger, "connection.read.failed", request, "list",
                "organizationId" -> organizationId)(error)
            }.flatTap(response => IO.monotonic.flatMap { finished =>
              logger.info(s"connection.read.completed organizationId=$organizationId status=${response.status.code} " +
                s"durationMs=${(finished - started).toMillis}")
            })
          }
        case Left(error) => BadRequest(error)
      }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "connections" / connectionIdValue =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { _ =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(connectionIdValue, "connectionId")) match {
        case (Right(organizationId), Right(connectionId)) =>
          transactionRunner.run(getConnection.execute(organizationId, connectionId)).attempt.flatMap {
            case Right(Some(connection)) => Ok(ConnectionHttpMapper.toResponse(connection))
            case Right(None) => NotFound(connectionNotFound)
            case Left(error) => ReadModelHttp.failedAs(logger, "connection.read.failed", request, "detail",
              "organizationId" -> organizationId, "connectionId" -> connectionId)(error)
          }
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
      }
      }
  }

  private def parseUuid(value: String, parameterName: String): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(value)).toEither.leftMap { _ =>
      ApiErrorResponse("INVALID_REQUEST", s"Invalid $parameterName")
    }
}
