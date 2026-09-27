package ru.bitec.app.ops
package infrastructure.http.dto

/** Self-service account requests. Neither carries a user id or an email: the account is the one
  * the session identifies, so these bodies say only what to change, never whose account.
  */
final case class ChangePasswordRequest(currentPassword: String, newPassword: String)
final case class UpdateAccountProfileRequest(displayName: String)

/** One session as the account page sees it: a non-secret id, its lifetime, and whether it is the
  * session making the request. No token and no device metadata. */
final case class SessionResponse(
  id: java.util.UUID,
  createdAt: java.time.Instant,
  expiresAt: java.time.Instant,
  current: Boolean
)

final case class SecurityEventResponse(
  id: java.util.UUID,
  eventType: String,
  occurredAt: java.time.Instant,
  sessionId: Option[java.util.UUID],
  source: Option[String],
  affectedSessionCount: Option[Int]
)
final case class SecurityEventPageResponse(items: List[SecurityEventResponse], nextCursor: Option[SecurityEventCursorResponse])
final case class SecurityEventCursorResponse(occurredAt: java.time.Instant, id: java.util.UUID)
