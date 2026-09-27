package ru.bitec.app.ops
package application.port

import domain.auth.{AuthSession, AuthenticatedSession, UserSessionSummary}

import java.time.Instant
import java.util.UUID

trait AuthSessionRepository[F[_]] {
  def create(session: AuthSession): F[Unit]

  /** Resolves a live session from its token hash, returning the user and the session's own id so a
    * handler can tell the current session apart from the others. Revoked and expired sessions, and
    * inactive users, resolve to nothing.
    */
  def findAuthenticatedSessionByTokenHash(tokenHash: String, now: Instant): F[Option[AuthenticatedSession]]

  def revokeByTokenHash(tokenHash: String, now: Instant): F[Unit]

  /** The live sessions of one user, newest first. Bounded to that user; never another's. */
  def listActiveByUser(userId: UUID, now: Instant): F[List[UserSessionSummary]]

  /** Revokes one live session of a user. Returns whether a row changed, so a session that is not
    * this user's, does not exist, or is already revoked is a no-op the caller can report as absent.
    */
  def revokeByIdForUser(userId: UUID, sessionId: UUID, now: Instant): F[Boolean]

  /** Revokes every live session of a user except the one named. Returns how many were revoked. */
  def revokeOthersForUser(userId: UUID, exceptSessionId: UUID, now: Instant): F[Int]

  /** Revokes every live session of a user, the current one included. Returns how many were revoked. */
  def revokeAllForUser(userId: UUID, now: Instant): F[Int]
}
