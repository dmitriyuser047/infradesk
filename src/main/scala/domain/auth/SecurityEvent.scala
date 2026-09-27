package ru.bitec.app.ops
package domain.auth

import java.time.Instant
import java.util.UUID

sealed trait SecurityEventType { def code: String }
object SecurityEventType {
  case object LoginSucceeded extends SecurityEventType { val code = "LOGIN_SUCCEEDED" }
  case object PasswordChanged extends SecurityEventType { val code = "PASSWORD_CHANGED" }
  case object SessionRevoked extends SecurityEventType { val code = "SESSION_REVOKED" }
  case object OtherSessionsRevoked extends SecurityEventType { val code = "OTHER_SESSIONS_REVOKED" }
  case object AllSessionsRevoked extends SecurityEventType { val code = "ALL_SESSIONS_REVOKED" }

  val all: List[SecurityEventType] = List(LoginSucceeded, PasswordChanged, SessionRevoked,
    OtherSessionsRevoked, AllSessionsRevoked)
  def fromCode(value: String): Either[IllegalArgumentException, SecurityEventType] =
    all.find(_.code == value).toRight(new IllegalArgumentException(s"Unsupported security event type '$value'"))
}

/** Account-owned security history. It deliberately carries no credential, token, token hash or
  * arbitrary metadata. A source is a trusted client IP saved only for successful logins.
  */
final case class SecurityEvent(
  id: UUID,
  userId: UUID,
  eventType: SecurityEventType,
  occurredAt: Instant,
  sessionId: Option[UUID],
  source: Option[String],
  affectedSessionCount: Option[Int]
)

final case class SecurityEventCursor(occurredAt: Instant, id: UUID)
final case class SecurityEventPage(items: List[SecurityEvent], nextCursor: Option[SecurityEventCursor])
