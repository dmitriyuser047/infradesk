package ru.bitec.app.ops
package infrastructure.http

import application.account.{
  AccountError,
  ChangePassword,
  ListUserSessions,
  RevokeAllUserSessions,
  RevokeOtherUserSessions,
  RevokeUserSession,
  UpdateAccountProfile
}
import application.auth.ListSecurityEvents
import application.port.TransactionRunner
import cats.effect.IO
import domain.auth.{AuthenticatedSession, AuthenticatedUser}
import infrastructure.http.dto.{
  ApiErrorResponse,
  ChangePasswordRequest,
  HttpJsonCodecs,
  MeResponse,
  SessionResponse,
  SecurityEventCursorResponse,
  SecurityEventPageResponse,
  SecurityEventResponse,
  UpdateAccountProfileRequest
}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._
import org.http4s.{Request, Response, Status}
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.util.Try

/** Self-service account operations for the signed-in user: profile, password and sessions.
  *
  * Every handler acts on the session the authentication boundary resolved — its user and its own
  * id. The body says only what to change; it never names an account or a session other than by an
  * id that the query then scopes to this user, so a user cannot reach another account or another
  * user's session, and an owner has no more reach here than a member. The raw session token is
  * never read, returned or logged; sessions are addressed by their row id.
  *
  * A refused change is an ordinary outcome carried by a stable code, logged at info, never as an
  * error and never with the values a person typed. Only an unexpected failure is logged as a
  * throwable, and its message is not shown to the client.
  */
final class AccountRoutes[Tx[_]](
  changePassword: ChangePassword[Tx],
  updateProfile: UpdateAccountProfile[Tx],
  listSessions: ListUserSessions[Tx],
  revokeSession: RevokeUserSession[Tx],
  revokeOtherSessions: RevokeOtherUserSessions[Tx],
  revokeAllSessions: RevokeAllUserSessions[Tx],
  listSecurityEvents: ListSecurityEvents[Tx],
  runner: TransactionRunner[IO, Tx],
  settings: AuthSettings,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._
  import CirceEntityDecoder._
  import CirceEntityEncoder._

  private val invalidRequest = ApiErrorResponse("INVALID_REQUEST", "The request could not be read")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")
  private val sessionNotFound = ApiErrorResponse(AccountError.SessionNotFound.code, "Session not found")

  def change(session: AuthenticatedSession, request: Request[IO]): IO[Response[IO]] =
    request.as[ChangePasswordRequest].attempt.flatMap {
      case Left(_) => BadRequest(invalidRequest)
      case Right(body) =>
        changePassword.execute(session.user.id, session.sessionId, body.currentPassword, body.newPassword)
          .attempt.flatMap {
            case Right(Right(_)) =>
              log(s"account.password.changed userId=${session.user.id}") *> NoContent()
            case Right(Left(error)) =>
              log(s"account.password.rejected userId=${session.user.id} code=${error.code}") *> failure(error)
            case Left(throwable) => unexpected("account.password.change.failed", session.user, throwable)
          }
    }

  def updateDisplayName(user: AuthenticatedUser, request: Request[IO]): IO[Response[IO]] =
    request.as[UpdateAccountProfileRequest].attempt.flatMap {
      case Left(_) => BadRequest(invalidRequest)
      case Right(body) =>
        updateProfile.execute(user.id, body.displayName).attempt.flatMap {
          case Right(Right(displayName)) =>
            log(s"account.profile.updated userId=${user.id}") *> Ok(MeResponse(user.id, user.email, displayName))
          case Right(Left(error)) =>
            log(s"account.profile.rejected userId=${user.id} code=${error.code}") *> failure(error)
          case Left(throwable) => unexpected("account.profile.update.failed", user, throwable)
        }
    }

  def sessions(session: AuthenticatedSession): IO[Response[IO]] =
    listSessions.execute(session.user.id).attempt.flatMap {
      case Right(rows) => Ok(rows.map(row =>
        SessionResponse(row.id, row.createdAt, row.expiresAt, current = row.id == session.sessionId)))
      case Left(throwable) => unexpected("account.sessions.list.failed", session.user, throwable)
    }

  def securityEvents(session: AuthenticatedSession, request: Request[IO]): IO[Response[IO]] = {
    val params = request.uri.query.params
    (securityCursor(params.get("beforeOccurredAt"), params.get("beforeId")), securityLimit(params.get("limit"))) match {
      case (Left(error), _) => BadRequest(error)
      case (_, Left(error)) => BadRequest(error)
      case (Right(before), Right(limit)) =>
        runner.run(listSecurityEvents.execute(session.user.id, before, limit)).attempt.flatMap {
          case Right(page) => Ok(SecurityEventPageResponse(page.items.map(event => SecurityEventResponse(event.id,
            event.eventType.code, event.occurredAt, event.sessionId, event.source, event.affectedSessionCount)),
            page.nextCursor.map(cursor => SecurityEventCursorResponse(cursor.occurredAt, cursor.id))))
          case Left(throwable) => unexpected("account.security-events.list.failed", session.user, throwable)
        }
    }
  }

  def revoke(session: AuthenticatedSession, rawSessionId: String): IO[Response[IO]] =
    Try(UUID.fromString(rawSessionId)).toOption match {
      // A malformed id is treated as an absent session, so the endpoint reveals nothing about which
      // ids exist.
      case None => NotFound(sessionNotFound)
      case Some(sessionId) =>
        revokeSession.execute(session.user.id, session.sessionId, sessionId).attempt.flatMap {
          case Right(Right(_)) =>
            log(s"account.session.revoked userId=${session.user.id} sessionId=$sessionId") *> NoContent()
          case Right(Left(error)) =>
            log(s"account.session.revoke.rejected userId=${session.user.id} code=${error.code}") *> failure(error)
          case Left(throwable) => unexpected("account.session.revoke.failed", session.user, throwable)
        }
    }

  def revokeOthers(session: AuthenticatedSession): IO[Response[IO]] =
    revokeOtherSessions.execute(session.user.id, session.sessionId).attempt.flatMap {
      case Right(count) => log(s"account.sessions.revoke-others userId=${session.user.id} revoked=$count") *> NoContent()
      case Left(throwable) => unexpected("account.sessions.revoke-others.failed", session.user, throwable)
    }

  def revokeAll(session: AuthenticatedSession): IO[Response[IO]] =
    revokeAllSessions.execute(session.user.id).attempt.flatMap {
      case Right(count) =>
        // The current session is gone too, so the cookie is cleared and the next request is
        // unauthenticated.
        log(s"account.sessions.revoke-all userId=${session.user.id} revoked=$count") *>
          NoContent().map(_.addCookie(AuthRoutes.clearCookie(settings)))
      case Left(throwable) => unexpected("account.sessions.revoke-all.failed", session.user, throwable)
    }

  private def failure(error: AccountError): IO[Response[IO]] = {
    val status = error match {
      case AccountError.AccountNotFound | AccountError.SessionNotFound => Status.NotFound
      case AccountError.AccountStateChanged => Status.Conflict
      case _ => Status.BadRequest
    }
    IO.pure(Response[IO](status = status).withEntity(ApiErrorResponse(error.code, message(error))))
  }

  private def securityCursor(at: Option[String], id: Option[String]) = (at, id) match {
    case (None, None) => Right(None)
    case (Some(rawAt), Some(rawId)) =>
      (Try(java.time.Instant.parse(rawAt)).toOption, Try(UUID.fromString(rawId)).toOption) match {
        case (Some(parsedAt), Some(parsedId)) => Right(Some(domain.auth.SecurityEventCursor(parsedAt, parsedId)))
        case _ => Left(ApiErrorResponse("INVALID_REQUEST", "Invalid security event cursor"))
      }
    case _ => Left(ApiErrorResponse("INVALID_REQUEST", "Invalid security event cursor"))
  }

  private def securityLimit(raw: Option[String]) = raw match {
    case None => Right(ListSecurityEvents.DefaultLimit)
    case Some(value) => Try(value.toInt).toOption.filter(v => v > 0 && v <= ListSecurityEvents.MaxLimit)
      .toRight(ApiErrorResponse("INVALID_REQUEST", "Invalid limit"))
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
    case AccountError.AccountStateChanged => "The account changed during the request; please try again"
    case AccountError.SessionNotFound => "Session not found"
    case AccountError.SessionIsCurrent => "The current session is ended by signing out"
  }

  private def unexpected(event: String, user: AuthenticatedUser, throwable: Throwable): IO[Response[IO]] =
    logger.error(throwable)(s"$event userId=${user.id} errorType=${throwable.getClass.getSimpleName}")
      .handleErrorWith(_ => IO.unit) *> InternalServerError(internalError)

  private def log(message: String): IO[Unit] =
    logger.info(message).handleErrorWith(_ => IO.unit)
}
