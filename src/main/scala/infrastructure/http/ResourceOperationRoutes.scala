package ru.bitec.app.ops
package infrastructure.http

import application.operation._
import application.port.TransactionRunner
import cats.effect.IO
import domain.auth.OrganizationPermission
import domain.operation.{OperationExecution, OperationExecutionCursor, ResourceOperationCode}
import infrastructure.http.dto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.time.Instant
import java.util.UUID
import scala.util.Try

final class ResourceOperationRoutes[Tx[_]](
  preparation: ResourceOperationPreparation[Tx],
  executeOperation: ExecuteResourceOperation[Tx],
  listExecutions: ListResourceOperationExecutions[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization
) {
  import HttpJsonCodecs._

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resourceRaw / "operations" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { context =>
        uuid(resourceRaw).fold(BadRequest(_), resourceId =>
          runner.run(preparation.availability(context.organizationId, resourceId)).attempt.flatMap {
            case Right(value) => Ok(AvailableResourceOperationsResponse(value.operations.map(_.code), value.unavailableReason))
            case Left(error) => respond(error)
          })
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "resources" / resourceRaw / "operations" / operationRaw / "executions" =>
      authorization.require(request, OrganizationPermission.ExecuteOperations) { context =>
        (uuid(resourceRaw), ResourceOperationCode.fromCode(operationRaw).left.map(_ => invalidOperation)) match {
          case (Left(error), _) => BadRequest(error)
          case (_, Left(error)) => BadRequest(error)
          case (Right(resourceId), Right(operation)) =>
            executeOperation.execute(context.actor, resourceId, operation).attempt.flatMap {
              case Right(value) => Created(toResponse(value))
              case Left(error) => respond(error)
            }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resourceRaw / "operation-executions" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { context =>
        val params = request.uri.query.params
        (uuid(resourceRaw), cursor(params.get("beforeStartedAt"), params.get("beforeId")), limit(params.get("limit"))) match {
          case (Left(error), _, _) => BadRequest(error)
          case (_, Left(error), _) => BadRequest(error)
          case (_, _, Left(error)) => BadRequest(error)
          case (Right(resourceId), Right(before), Right(pageSize)) =>
            runner.run(listExecutions.execute(context.organizationId, resourceId, before, pageSize)).attempt.flatMap {
              case Right(Some(values)) => Ok(values.map(toResponse))
              case Right(None) => NotFound(notFound)
              case Left(error) => respond(error)
            }
        }
      }
  }

  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid resourceId")
  private val invalidCursor = ApiErrorResponse("INVALID_REQUEST", "Invalid operation cursor")
  private val invalidLimit = ApiErrorResponse("INVALID_REQUEST", "Invalid limit")
  private val invalidOperation = ApiErrorResponse("INVALID_OPERATION", "Invalid operation code")
  private val notFound = ApiErrorResponse("RESOURCE_NOT_FOUND", "Resource was not found")
  private val internal = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  private def uuid(value: String): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(value)).toEither.left.map(_ => invalid)
  private def cursor(at: Option[String], id: Option[String]): Either[ApiErrorResponse, Option[OperationExecutionCursor]] =
    (at, id) match {
      case (None, None) => Right(None)
      case (Some(a), Some(i)) => for {
        parsedAt <- Try(Instant.parse(a)).toEither.left.map(_ => invalidCursor)
        parsedId <- Try(UUID.fromString(i)).toEither.left.map(_ => invalidCursor)
      } yield Some(OperationExecutionCursor(parsedAt, parsedId))
      case _ => Left(invalidCursor)
    }
  private def limit(value: Option[String]): Either[ApiErrorResponse, Int] = value match {
    case None => Right(20)
    case Some(raw) => Try(raw.toInt).toOption.filter(v => v > 0 && v <= 100).toRight(invalidLimit)
  }
  private def toResponse(value: OperationExecution): OperationExecutionResponse =
    OperationExecutionResponse(value.id, value.resourceId, value.operation.code, value.status.code,
      value.actorUserId, value.startedAt, value.finishedAt, value.errorCode, value.errorMessage)
  private def respond(error: Throwable): IO[org.http4s.Response[IO]] = error match {
    case _: OperationResourceNotFound => NotFound(notFound)
    case value: OperationUnsupported => Conflict(ApiErrorResponse(value.code, value.safeMessage))
    case value: OperationTargetAmbiguous => Conflict(ApiErrorResponse(value.code, value.safeMessage))
    case value: OperationAlreadyRunning => Conflict(ApiErrorResponse(value.code, value.safeMessage))
    case _ => InternalServerError(internal)
  }
}

