package ru.bitec.app.ops
package infrastructure.http

import application.connection.{GetConnectionSyncSession, ListConnectionSyncSessions, RunManualConnectionSync}
import application.connector.{ConnectionSyncInactive, ConnectionSyncNotFound, SyncAlreadyRunning}
import application.port.TransactionRunner
import cats.effect.IO
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import infrastructure.http.mapper.SyncSessionHttpMapper
import org.http4s.{HttpRoutes, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

final class ConnectionSyncRoutes[Tx[_]](
  listSessions: ListConnectionSyncSessions[Tx],
  getSession: GetConnectionSyncSession[Tx],
  manualSync: RunManualConnectionSync[Tx],
  runner: TransactionRunner[IO, Tx]
) {
  import HttpJsonCodecs._

  private val connectionNotFound = ApiErrorResponse("CONNECTION_NOT_FOUND", "Connection was not found")
  private val sessionNotFound = ApiErrorResponse("SYNC_SESSION_NOT_FOUND", "Synchronization session was not found")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case POST -> Root / "api" / "v1" / "organizations" / org / "connections" / connection / "sync" =>
      withIds(org, connection) { (orgId, connectionId) =>
        manualSync.execute(orgId, connectionId).attempt.flatMap {
          case Right(session) => Ok(SyncSessionHttpMapper.toResponse(session))
          case Left(_: ConnectionSyncNotFound) => NotFound(connectionNotFound)
          case Left(_: ConnectionSyncInactive) => Conflict(ApiErrorResponse("CONNECTION_INACTIVE", "Connection is inactive"))
          case Left(_: SyncAlreadyRunning) => Conflict(ApiErrorResponse("SYNC_ALREADY_RUNNING", "Synchronization is already running"))
          case Left(_) => InternalServerError(internalError)
        }
      }

    case GET -> Root / "api" / "v1" / "organizations" / org / "connections" / connection / "sync-sessions" =>
      withIds(org, connection) { (orgId, connectionId) =>
        runner.run(listSessions.execute(orgId, connectionId)).attempt.flatMap {
          case Right(Some(sessions)) => Ok(sessions.map(SyncSessionHttpMapper.toResponse))
          case Right(None) => NotFound(connectionNotFound)
          case Left(_) => InternalServerError(internalError)
        }
      }

    case GET -> Root / "api" / "v1" / "organizations" / org / "connections" / connection / "sync-sessions" / session =>
      withThreeIds(org, connection, session) { (orgId, connectionId, sessionId) =>
        runner.run(getSession.execute(orgId, connectionId, sessionId)).attempt.flatMap {
          case Right(Some(found)) => Ok(SyncSessionHttpMapper.toResponse(found))
          case Right(None) => NotFound(sessionNotFound)
          case Left(_) => InternalServerError(internalError)
        }
      }
  }

  private def withIds(org: String, connection: String)(next: (UUID, UUID) => IO[Response[IO]]): IO[Response[IO]] =
    (parse(org), parse(connection)) match {
      case (Some(orgId), Some(connectionId)) => next(orgId, connectionId)
      case _ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid connection path"))
    }

  private def withThreeIds(org: String, connection: String, session: String)(
    next: (UUID, UUID, UUID) => IO[Response[IO]]
  ): IO[Response[IO]] =
    (parse(org), parse(connection), parse(session)) match {
      case (Some(orgId), Some(connectionId), Some(sessionId)) => next(orgId, connectionId, sessionId)
      case _ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid synchronization session path"))
    }

  private def parse(value: String): Option[UUID] = Try(UUID.fromString(value)).toOption
}
