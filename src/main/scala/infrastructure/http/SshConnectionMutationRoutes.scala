package ru.bitec.app.ops
package infrastructure.http

import application.connection.{ConnectionManagementError, SshConnectionManagement}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs, SaveSshConnectionRequest, TestSshConnectionRequest, TestSshConnectionResponse}
import infrastructure.http.mapper.{ConnectionHttpMapper, SshConnectionCommandMapper}
import org.http4s.{HttpRoutes, Response}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

final class SshConnectionMutationRoutes[Tx[_]](
  management: SshConnectionManagement[Tx],
  authorization: OrganizationAuthorization
) {
  import HttpJsonCodecs._
  import CirceEntityDecoder._
  import CirceEntityEncoder._

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // Probing a host with submitted credentials is part of managing connections, not a read.
    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "connections" / "ssh" / "test" =>
      authorization.require(request, OrganizationPermission.ManageConnections) { _ =>
        withOrganization(org) { _ =>
          request.as[TestSshConnectionRequest].attempt.flatMap {
            case Left(_) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid SSH test request"))
            case Right(body) => respond(IO.fromEither(SshConnectionCommandMapper.test(body))
              .flatMap(management.test).flatMap(fingerprint =>
              Ok(TestSshConnectionResponse(true, fingerprint))))
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "connections" =>
      authorization.require(request, OrganizationPermission.ManageConnections) { context =>
        withOrganization(org) { _ =>
          request.as[SaveSshConnectionRequest].attempt.flatMap {
            case Left(_) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid connection request"))
            case Right(body) => respond(IO.fromEither(SshConnectionCommandMapper.create(body))
              .flatMap(management.create(context.actor, _)).flatMap(value =>
              Created(ConnectionHttpMapper.toResponse(value))))
          }
        }
      }

    case request @ PUT -> Root / "api" / "v1" / "organizations" / org / "connections" / connection =>
      authorization.require(request, OrganizationPermission.ManageConnections) { context =>
        withIds(org, connection) { (_, connectionId) =>
          request.as[SaveSshConnectionRequest].attempt.flatMap {
            case Left(_) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid connection request"))
            case Right(body) => respond(IO.fromEither(SshConnectionCommandMapper.update(body))
              .flatMap(management.update(context.actor, connectionId, _)).flatMap(value =>
              Ok(ConnectionHttpMapper.toResponse(value))))
          }
        }
      }

    case request @ DELETE -> Root / "api" / "v1" / "organizations" / org / "connections" / connection =>
      authorization.require(request, OrganizationPermission.ManageConnections) { context =>
        withIds(org, connection) { (_, connectionId) =>
          respond(management.deactivate(context.actor, connectionId).flatMap(_ => NoContent()))
        }
      }
  }

  private def withOrganization(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption match {
      case Some(id) => next(id)
      case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid organizationId"))
    }

  private def withIds(org: String, connection: String)(next: (UUID, UUID) => IO[Response[IO]]): IO[Response[IO]] =
    (Try(UUID.fromString(org)).toOption, Try(UUID.fromString(connection)).toOption) match {
      case (Some(orgId), Some(connectionId)) => next(orgId, connectionId)
      case _ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid connection path"))
    }

  private def respond(action: IO[Response[IO]]): IO[Response[IO]] =
    action.handleErrorWith {
      case error: ConnectionManagementError =>
        val body = ApiErrorResponse(error.code, error.getMessage)
        error.code match {
          case "CONNECTION_NOT_FOUND" | "PROJECT_NOT_FOUND" | "ENVIRONMENT_NOT_FOUND" => NotFound(body)
          case "CONNECTION_CODE_ALREADY_EXISTS" | "SSH_HOST_KEY_MISMATCH" => Conflict(body)
          case "SSH_CONNECTION_FAILED" => UnprocessableEntity(body)
          case _ => BadRequest(body)
        }
      case error: _root_.org.postgresql.util.PSQLException if error.getSQLState == "23505" &&
        Option(error.getServerErrorMessage).exists(_.getConstraint == "ux_connection_org_code_ci") =>
        Conflict(ApiErrorResponse("CONNECTION_CODE_ALREADY_EXISTS", "Connection code already exists"))
      case _ => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
    }
}
