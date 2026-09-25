package ru.bitec.app.ops
package infrastructure.http

import application.overview.GetOperationsOverview
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.connection.ConnectionScope
import infrastructure.http.dto._
import org.http4s.{HttpRoutes, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

/** The operations overview of the organization, a project or an environment. Reading only.
  *
  * The overview shows what the other read endpoints already show a member, so it needs nothing
  * beyond reading the organization.
  */
final class OperationsOverviewRoutes[Tx[_]](
  getOperationsOverview: GetOperationsOverview[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization
) {
  import HttpJsonCodecs._

  private val invalidProject = ApiErrorResponse("INVALID_REQUEST", "Invalid projectId")
  private val invalidEnvironment = ApiErrorResponse("INVALID_REQUEST", "Invalid environmentId")
  private val environmentWithoutProject =
    ApiErrorResponse("INVALID_REQUEST", "environmentId requires projectId")
  private val projectNotFound = ApiErrorResponse("PROJECT_NOT_FOUND", "Project was not found")
  private val environmentNotFound = ApiErrorResponse("ENVIRONMENT_NOT_FOUND", "Environment was not found")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "overview" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { context =>
        val params = request.uri.query.params
        scope(params.get("projectId"), params.get("environmentId")) match {
          case Left(error) => BadRequest(error)
          case Right(selected) =>
            // A project or environment of another organization is not found, not forbidden.
            runner.run(getOperationsOverview.execute(context.organizationId, selected)).attempt.flatMap {
              case Right(Some(overview)) => Ok(OperationsOverviewResponse.from(overview))
              case Right(None) => notFound(selected)
              case Left(_) => InternalServerError(internalError)
            }
        }
      }
  }

  private def notFound(scope: ConnectionScope): IO[Response[IO]] = scope match {
    case _: ConnectionScope.Environment => NotFound(environmentNotFound)
    case _ => NotFound(projectNotFound)
  }

  private def scope(
    projectRaw: Option[String],
    environmentRaw: Option[String]
  ): Either[ApiErrorResponse, ConnectionScope] =
    (projectRaw, environmentRaw) match {
      case (None, None) => Right(ConnectionScope.Organization)
      case (None, Some(_)) => Left(environmentWithoutProject)
      case (Some(project), None) => uuid(project, invalidProject).map(ConnectionScope.Project)
      case (Some(project), Some(environment)) =>
        (uuid(project, invalidProject), uuid(environment, invalidEnvironment))
          .mapN(ConnectionScope.Environment)
    }

  private def uuid(raw: String, error: ApiErrorResponse): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(raw)).toEither.leftMap(_ => error)
}
