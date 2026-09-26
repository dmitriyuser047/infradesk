package ru.bitec.app.ops
package application.port

import domain.auth.UserAccount

import java.time.Instant
import java.util.UUID

trait UserAccountRepository[F[_]] {
  def findByEmail(email: String): F[Option[UserAccount]]
  def findActiveById(id: UUID): F[Option[UserAccount]]
  def createIfMissing(user: UserAccount): F[Unit]

  /** Replaces the password hash of one active account. Returns whether a row was changed, so a
    * caller can tell an applied change from an account that was deactivated in the meantime.
    */
  def updatePasswordHash(id: UUID, passwordHash: String, updatedAt: Instant): F[Boolean]

  /** Replaces the display name of one active account. Returns whether a row was changed. */
  def updateDisplayName(id: UUID, displayName: String, updatedAt: Instant): F[Boolean]
}
