package ru.bitec.app.ops
package infrastructure.http

import application.auth.{Authentication, Login}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.AuthenticatedUser
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs, LoginRequest, MeResponse, MyOrganizationResponse}
import org.http4s.{HttpRoutes, Request, Response, ResponseCookie, SameSite, Status}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._

final class AuthRoutes[Tx[_]](
  login: Login[Tx],
  authentication: Authentication[Tx],
  settings: AuthSettings
) {
  import HttpJsonCodecs._
  import CirceEntityDecoder._
  import CirceEntityEncoder._

  val public: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "auth" / "login" =>
      request.as[LoginRequest].attempt.flatMap {
        case Left(_) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid login request"))
        case Right(credentials) =>
          login.execute(credentials.email, credentials.password).attempt.flatMap {
            case Right(Some(result)) =>
              Ok(MeResponse(result.user.id, result.user.email, result.user.displayName))
                .map(_.addCookie(sessionCookie(result.rawToken)))
            case Right(None) => IO.pure(Response[IO](status = Status.Unauthorized)
              .withEntity(ApiErrorResponse("INVALID_CREDENTIALS", "Invalid email or password")))
            case Left(_) => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
          }
      }

    case request @ POST -> Root / "api" / "v1" / "auth" / "logout" =>
      sessionToken(request) match {
        case None => NoContent().map(_.addCookie(clearCookie))
        case Some(token) =>
          authentication.revoke(token).attempt.flatMap {
            case Right(_) => NoContent().map(_.addCookie(clearCookie))
            case Left(_) => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
              .map(_.addCookie(clearCookie))
          }
      }
  }

  def me(user: AuthenticatedUser): IO[Response[IO]] =
    Ok(MeResponse(user.id, user.email, user.displayName))

  def organizations(user: AuthenticatedUser): IO[Response[IO]] =
    authentication.organizations(user.id).attempt.flatMap {
      case Right(organizations) =>
        Ok(organizations.map(value =>
          MyOrganizationResponse(value.id, value.code, value.name, value.role.code)
        ))
      case Left(_) => InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
    }

  private def sessionCookie(rawToken: String): ResponseCookie = AuthRoutes.sessionCookie(rawToken, settings)

  private def clearCookie: ResponseCookie = AuthRoutes.clearCookie(settings)

  private def sessionToken(request: Request[IO]): Option[String] =
    request.cookies.find(_.name == AuthRoutes.CookieName).map(_.content).filter(_.nonEmpty)
}

object AuthRoutes {
  val CookieName = "infradesk_session"

  /** The session cookie is HttpOnly, path-wide, SameSite=Strict and Secure in production; the last
    * two are what make the same-origin API safe without a separate CSRF token. Built here so every
    * route that sets or clears it uses the one definition.
    */
  def sessionCookie(rawToken: String, settings: AuthSettings): ResponseCookie =
    ResponseCookie(
      name = CookieName,
      content = rawToken,
      maxAge = Some(settings.ttlSeconds),
      path = Some("/"),
      sameSite = Some(SameSite.Strict),
      secure = settings.secureCookie,
      httpOnly = true
    )

  def clearCookie(settings: AuthSettings): ResponseCookie =
    ResponseCookie(
      name = CookieName,
      content = "",
      maxAge = Some(0L),
      path = Some("/"),
      sameSite = Some(SameSite.Strict),
      secure = settings.secureCookie,
      httpOnly = true
    )
}
