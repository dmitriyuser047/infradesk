package ru.bitec.app.ops
package infrastructure.http

import application.connection.{GetConnection, ListConnections}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import infrastructure.http.mapper.ConnectionHttpMapper
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

final class ConnectionRoutes[Tx[_]](
  getConnection: GetConnection[Tx],
  listConnections: ListConnections[Tx],
  transactionRunner: TransactionRunner[IO, Tx]
) {
  import HttpJsonCodecs._

  private val connectionNotFound = ApiErrorResponse(
    "CONNECTION_NOT_FOUND",
    "Connection was not found"
  )
  private val internalError = ApiErrorResponse(
    "INTERNAL_ERROR",
    "Internal server error"
  )

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "connections" =>
      parseUuid(organizationIdValue, "organizationId") match {
        case Right(organizationId) =>
          transactionRunner.run(listConnections.execute(organizationId)).attempt.flatMap {
            case Right(connections) => Ok(connections.map(ConnectionHttpMapper.toResponse))
            case Left(_) => InternalServerError(internalError)
          }
        case Left(error) => BadRequest(error)
      }

    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "connections" / connectionIdValue =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(connectionIdValue, "connectionId")) match {
        case (Right(organizationId), Right(connectionId)) =>
          transactionRunner.run(getConnection.execute(organizationId, connectionId)).attempt.flatMap {
            case Right(Some(connection)) => Ok(ConnectionHttpMapper.toResponse(connection))
            case Right(None) => NotFound(connectionNotFound)
            case Left(_) => InternalServerError(internalError)
          }
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
      }
  }

  private def parseUuid(value: String, parameterName: String): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(value)).toEither.leftMap { _ =>
      ApiErrorResponse("INVALID_REQUEST", s"Invalid $parameterName")
    }
}
