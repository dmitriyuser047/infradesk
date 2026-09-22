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
