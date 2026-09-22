package ru.bitec.app.ops
package domain.auth

import java.time.Instant
import java.util.UUID

final case class UserAccount(
  id: UUID,
  email: String,
  passwordHash: String,
  displayName: String,
  isActive: Boolean,
  createdAt: Instant,
  updatedAt: Instant
)

object UserAccount {
  def normalizeEmail(email: String): String = email.trim.toLowerCase(java.util.Locale.ROOT)
}
