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
  accountRoutes: AccountRoutes[Tx],
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
        authenticated(request)(session => authRoutes.me(session.user))
      case List("api", "v1", "me", "organizations") if request.method == GET =>
        authenticated(request)(session => authRoutes.organizations(session.user))
      case List("api", "v1", "organizations") if request.method == POST =>
        authenticated(request)(session => organizationRoutes.run(AuthenticatedRequest.attach(request,session)))
      case "api" :: "v1" :: "administration" :: _ =>
        authenticated(request)(session => organizationRoutes.run(AuthenticatedRequest.attach(request,session)))
      // Self-service account operations: the account is the session's, so these need only
      // authentication and never an organization in the path.
      case List("api", "v1", "account", "change-password") if request.method == POST =>
        authenticated(request)(session => accountRoutes.change(session, request))
      case List("api", "v1", "account") if request.method == PATCH =>
        authenticated(request)(session => accountRoutes.updateDisplayName(session.user, request))
      case List("api", "v1", "account", "sessions") if request.method == GET =>
        authenticated(request)(accountRoutes.sessions)
      case List("api", "v1", "account", "security-events") if request.method == GET =>
        authenticated(request)(session => accountRoutes.securityEvents(session, request))
      case List("api", "v1", "account", "sessions", "revoke-others") if request.method == POST =>
        authenticated(request)(accountRoutes.revokeOthers)
      case List("api", "v1", "account", "sessions", "revoke-all") if request.method == POST =>
        authenticated(request)(accountRoutes.revokeAll)
      case List("api", "v1", "account", "sessions", sessionId) if request.method == DELETE =>
        authenticated(request)(session => accountRoutes.revoke(session, sessionId))
      case "api" :: "v1" :: "organizations" :: organizationIdValue :: _ =>
        authenticated(request) { session =>
          val user = session.user
          Try(UUID.fromString(organizationIdValue)).toOption match {
            // Every organization route runs with an access context, so a path that cannot carry
            // one is rejected here instead of reaching a handler without it.
            case None => BadRequest(invalidOrganizationId)
            case Some(organizationId) =>
              authentication.organizationRole(user.id, organizationId).attempt.flatMap {
                case Right(Some(role)) =>
                  val context = OrganizationAccessContext(user, organizationId, role, Some(session.sessionId))
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
    next: domain.auth.AuthenticatedSession => IO[Response[IO]]
  ): IO[Response[IO]] =
    request.cookies.find(_.name == AuthRoutes.CookieName).map(_.content).filter(_.nonEmpty) match {
      case None => IO.pure(Response[IO](status = Status.Unauthorized).withEntity(unauthenticated))
      case Some(rawToken) =>
        authentication.authenticate(rawToken).attempt.flatMap {
          case Right(Some(session)) => next(session)
          case Right(None) => IO.pure(Response[IO](status = Status.Unauthorized).withEntity(unauthenticated))
          case Left(_) => InternalServerError(internalError)
        }
    }
}
