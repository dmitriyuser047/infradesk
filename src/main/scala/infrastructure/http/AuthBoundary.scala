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

final class AuthBoundary[Tx[_]](
  authRoutes: AuthRoutes[Tx],
  authentication: Authentication[Tx],
  organizationRoutes: HttpApp[IO]
) {
  import HttpJsonCodecs._

  private val unauthenticated = ApiErrorResponse("UNAUTHENTICATED", "Authentication required")
  private val organizationNotFound = ApiErrorResponse("ORGANIZATION_NOT_FOUND", "Organization was not found")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")
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
            case None => organizationRoutes.run(request)
            case Some(organizationId) =>
              authentication.hasOrganizationAccess(user.id, organizationId).attempt.flatMap {
                case Right(true) => organizationRoutes.run(request)
                case Right(false) => NotFound(organizationNotFound)
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
