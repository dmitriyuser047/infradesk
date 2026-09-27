package ru.bitec.app.ops
package application.account

import application.port.{AuthSessionRepository, SecurityEventRepository, TimeProvider, TransactionRunner}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.audit.AuditAction
import domain.auth.{SecurityEvent, SecurityEventType, UserSessionSummary}

import java.util.UUID

/** The live sessions of the authenticated user, newest first. Scoped to that user by the query
  * itself; another user's sessions are never read. Expiry and revocation follow the same rules the
  * authentication uses, so the list never shows a session that would no longer authenticate.
  */
final class ListUserSessions[Tx[_]](
  sessions: AuthSessionRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  time: TimeProvider[IO]
) {
  def execute(userId: UUID): IO[List[UserSessionSummary]] =
    time.now.flatMap(now => runner.run(sessions.listActiveByUser(userId, now)))
}

/** Revokes one other session of the authenticated user.
  *
  * Revoke-one is for other sessions; the current one is ended through logout, so an attempt to
  * revoke it is refused rather than silently logging the user out here. A session that is not this
  * user's, does not exist, or is already revoked reports as absent, without revealing which.
  */
final class RevokeUserSession[Tx[_]: Monad](
  sessions: AuthSessionRepository[Tx],
  securityEvents: SecurityEventRepository[Tx],
  accountAudit: AccountAudit[Tx],
  runner: TransactionRunner[IO, Tx],
  time: TimeProvider[IO]
) {
  def execute(userId: UUID, currentSessionId: UUID, targetSessionId: UUID): IO[Either[AccountError, Unit]] =
    if (targetSessionId == currentSessionId) IO.pure(Left(AccountError.SessionIsCurrent))
    else time.now.flatMap(now => runner.run(
      sessions.revokeByIdForUser(userId, targetSessionId, now).flatMap {
        case false => (Left(AccountError.SessionNotFound): Either[AccountError, Unit]).pure[Tx]
        case true => accountAudit.record(userId, AuditAction.AccountSessionRevoked) *>
          securityEvents.save(SecurityEvent(UUID.randomUUID(), userId, SecurityEventType.SessionRevoked,
            now, Some(targetSessionId), None, Some(1))).as(().asRight[AccountError])
      }
    ))
}

/** Revokes every session of the authenticated user except the current one. Always succeeds, even
  * when there are no others; the count of revoked sessions is returned for the response.
  */
final class RevokeOtherUserSessions[Tx[_]: Monad](
  sessions: AuthSessionRepository[Tx],
  securityEvents: SecurityEventRepository[Tx],
  accountAudit: AccountAudit[Tx],
  runner: TransactionRunner[IO, Tx],
  time: TimeProvider[IO]
) {
  def execute(userId: UUID, currentSessionId: UUID): IO[Int] =
    time.now.flatMap(now => runner.run(for {
      revoked <- sessions.revokeOthersForUser(userId, currentSessionId, now)
      _ <- accountAudit.record(userId, AuditAction.AccountOtherSessionsRevoked)
      _ <- securityEvents.save(SecurityEvent(UUID.randomUUID(), userId, SecurityEventType.OtherSessionsRevoked,
        now, None, None, Some(revoked)))
    } yield revoked))
}

/** Revokes every session of the authenticated user, the current one included. The caller clears
  * the session cookie afterwards, so the next request is unauthenticated.
  */
final class RevokeAllUserSessions[Tx[_]: Monad](
  sessions: AuthSessionRepository[Tx],
  securityEvents: SecurityEventRepository[Tx],
  accountAudit: AccountAudit[Tx],
  runner: TransactionRunner[IO, Tx],
  time: TimeProvider[IO]
) {
  def execute(userId: UUID): IO[Int] =
    time.now.flatMap(now => runner.run(for {
      revoked <- sessions.revokeAllForUser(userId, now)
      _ <- accountAudit.record(userId, AuditAction.AccountAllSessionsRevoked)
      _ <- securityEvents.save(SecurityEvent(UUID.randomUUID(), userId, SecurityEventType.AllSessionsRevoked,
        now, None, None, Some(revoked)))
    } yield revoked))
}
