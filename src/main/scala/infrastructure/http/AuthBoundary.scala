package ru.bitec.app.ops
package infrastructure.http

import application.auth.Authentication
import cats.data.Kleisli
import cats.effect.IO
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import org.http4s.{HttpApp, Request, Response, Status}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

/** Authentication and organization access, and nothing else.
  *
  * The boundary answers "who is this and may they see this organization at all"; what each
  * operation requires is stated by the route that implements it, through
  * [[OrganizationAuthorization]]. There is no catalogue of privileged URLs here.
  */
final class AuthBoundary[Tx[_]](
  authRoutes: AuthRoutes[Tx],
  authentication: Authentication[Tx],
  organizationRoutes: HttpApp[IO]
) {
  import HttpJsonCodecs._

  private val unauthenticated = ApiErrorResponse("UNAUTHENTICATED", "Authentication required")
  private val organizationNotFound = ApiErrorResponse("ORGANIZATION_NOT_FOUND", "Organization was not found")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")
  private val invalidOrganizationId = ApiErrorResponse("INVALID_REQUEST", "Invalid organizationId")
  private val publicApp = authRoutes.public.orNotFound

  val app: HttpApp[IO] = Kleisli { request: Request[IO] =>
    request.uri.path.renderString.split('/').filter(_.nonEmpty).toList match {
      case List("api", "v1", "auth", "login") if request.method == POST => publicApp.run(request)
      case List("api", "v1", "auth", "logout") if request.method == POST => publicApp.run(request)
      case List("api", "v1", "me") if request.method == GET =>
        authenticated(request)(authRoutes.me)
      case List("api", "v1", "me", "organizations") if request.method == GET =>
        authenticated(request)(authRoutes.organizations)
      case "api" :: "v1" :: "organizations" :: organizationIdValue :: _ =>
        authenticated(request) { user =>
          Try(UUID.fromString(organizationIdValue)).toOption match {
            // Every organization route runs with an access context, so a path that cannot carry
            // one is rejected here instead of reaching a handler without it.
            case None => BadRequest(invalidOrganizationId)
            case Some(organizationId) =>
              authentication.organizationRole(user.id, organizationId).attempt.flatMap {
                case Right(Some(role)) =>
                  val context = OrganizationAccessContext(user, organizationId, role)
                  organizationRoutes.run(OrganizationAuthorization.withContext(request, context))
                // A user who is not an active member learns nothing about the organization.
                case Right(None) => NotFound(organizationNotFound)
                case Left(_) => InternalServerError(internalError)
              }
          }
        }
      case "api" :: "v1" :: _ =>
        authenticated(request)(_ => organizationRoutes.run(request))
      case _ => organizationRoutes.run(request)
    }
  }

  private def authenticated(request: Request[IO])(
    next: domain.auth.AuthenticatedUser => IO[Response[IO]]
  ): IO[Response[IO]] =
    request.cookies.find(_.name == AuthRoutes.CookieName).map(_.content).filter(_.nonEmpty) match {
      case None => IO.pure(Response[IO](status = Status.Unauthorized).withEntity(unauthenticated))
      case Some(rawToken) =>
        authentication.authenticate(rawToken).attempt.flatMap {
          case Right(Some(user)) => next(user)
          case Right(None) => IO.pure(Response[IO](status = Status.Unauthorized).withEntity(unauthenticated))
          case Left(_) => InternalServerError(internalError)
        }
    }
}
