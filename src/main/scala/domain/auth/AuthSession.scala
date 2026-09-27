package ru.bitec.app.ops
package domain.auth

import java.time.Instant
import java.util.UUID

final case class AuthSession(
  id: UUID,
  userId: UUID,
  tokenHash: String,
  createdAt: Instant,
  expiresAt: Instant,
  revokedAt: Option[Instant]
)

final case class AuthenticatedUser(id: UUID, email: String, displayName: String)

/** An authenticated request's user together with the session it arrived on.
  *
  * The session id is a stable, non-secret row identifier — never the raw token — so it is safe to
  * carry to a handler that needs to tell the current session apart from the others.
  */
final case class AuthenticatedSession(user: AuthenticatedUser, sessionId: UUID)

/** What is safe to show about a session: when it began, when it lapses, and nothing of the token.
  * There is no device, browser or IP metadata in the model, so none is invented here.
  */
final case class UserSessionSummary(id: UUID, createdAt: Instant, expiresAt: Instant)
