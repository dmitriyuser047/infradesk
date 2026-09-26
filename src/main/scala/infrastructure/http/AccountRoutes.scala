package ru.bitec.app.ops
package infrastructure.http

import application.account.{AccountError, ChangePassword, UpdateAccountProfile}
import cats.effect.IO
import domain.auth.AuthenticatedUser
import infrastructure.http.dto.{
  ApiErrorResponse,
  ChangePasswordRequest,
  HttpJsonCodecs,
  MeResponse,
  UpdateAccountProfileRequest
}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._
import org.http4s.{Request, Response, Status}
import org.typelevel.log4cats.Logger

/** Self-service account operations for the signed-in user.
  *
  * Both handlers act on `user`, the account the authentication boundary resolved from the session
  * cookie. The request body is only what to change; it never names the account, so a user cannot
  * reach another account, and an owner has no more reach here than a member.
  *
  * A refused change — the wrong current password, a password that is too short — is an ordinary
  * outcome carried by a stable code, logged at info, never as an error and never with the values a
  * person typed. Only an unexpected failure is logged as a throwable, and its message is not shown
  * to the client.
  */
final class AccountRoutes[Tx[_]](
  changePassword: ChangePassword[Tx],
  updateProfile: UpdateAccountProfile[Tx],
  logger: Logger[IO]
) {
  import HttpJsonCodecs._
  import CirceEntityDecoder._
  import CirceEntityEncoder._

  private val invalidRequest = ApiErrorResponse("INVALID_REQUEST", "The request could not be read")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  def change(user: AuthenticatedUser, request: Request[IO]): IO[Response[IO]] =
    request.as[ChangePasswordRequest].attempt.flatMap {
      case Left(_) => BadRequest(invalidRequest)
      case Right(body) =>
        changePassword.execute(user.id, body.currentPassword, body.newPassword).attempt.flatMap {
          case Right(Right(_)) =>
            log(s"account.password.changed userId=${user.id}") *> NoContent()
          case Right(Left(error)) =>
            log(s"account.password.rejected userId=${user.id} code=${error.code}") *>
              failure(error)
          case Left(throwable) => unexpected("account.password.change.failed", user, throwable)
        }
    }

  def updateDisplayName(user: AuthenticatedUser, request: Request[IO]): IO[Response[IO]] =
    request.as[UpdateAccountProfileRequest].attempt.flatMap {
      case Left(_) => BadRequest(invalidRequest)
      case Right(body) =>
        updateProfile.execute(user.id, body.displayName).attempt.flatMap {
          case Right(Right(displayName)) =>
            log(s"account.profile.updated userId=${user.id}") *>
              Ok(MeResponse(user.id, user.email, displayName))
          case Right(Left(error)) =>
            log(s"account.profile.rejected userId=${user.id} code=${error.code}") *> failure(error)
          case Left(throwable) => unexpected("account.profile.update.failed", user, throwable)
        }
    }

  /** Maps an account error to its status and stable code. Everything except the two "not this
    * request's fault" cases is a rejected input, which is a bad request.
    */
  private def failure(error: AccountError): IO[Response[IO]] = {
    val status = error match {
      case AccountError.AccountNotFound => Status.NotFound
      case _ => Status.BadRequest
    }
    IO.pure(Response[IO](status = status)
      .withEntity(ApiErrorResponse(error.code, message(error))))
  }

  private def message(error: AccountError): String = error match {
    case AccountError.CurrentPasswordInvalid => "Current password is incorrect"
    case AccountError.PasswordTooShort =>
      s"Password must contain at least ${application.account.AccountValidation.MinPasswordLength} characters"
    case AccountError.PasswordTooLong =>
      s"Password must be at most ${application.account.AccountValidation.MaxPasswordLength} characters"
    case AccountError.PasswordInvalidCharacters => "Password contains invalid characters"
    case AccountError.PasswordSameAsCurrent => "New password must be different from the current password"
    case AccountError.DisplayNameEmpty => "Name must not be empty"
    case AccountError.DisplayNameTooLong =>
      s"Name must be at most ${application.account.AccountValidation.MaxDisplayNameLength} characters"
    case AccountError.DisplayNameInvalidCharacters => "Name contains invalid characters"
    case AccountError.AccountNotFound => "Account not found"
  }

  private def unexpected(event: String, user: AuthenticatedUser, throwable: Throwable): IO[Response[IO]] =
    logger.error(throwable)(s"$event userId=${user.id} errorType=${throwable.getClass.getSimpleName}")
      .handleErrorWith(_ => IO.unit) *> InternalServerError(internalError)

  private def log(message: String): IO[Unit] =
    logger.info(message).handleErrorWith(_ => IO.unit)
}
