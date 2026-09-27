package ru.bitec.app.ops
package application.port

import domain.auth.UserAccount

import java.time.Instant
import java.util.UUID

trait UserAccountRepository[F[_]] {
  def findByEmail(email: String): F[Option[UserAccount]]
  def findActiveById(id: UUID): F[Option[UserAccount]]
  def createIfMissing(user: UserAccount): F[Unit]

  /** Replaces the password hash of one active account only if it still holds the expected hash.
    *
    * The compare-and-set is what makes two concurrent password changes safe: each verified the
    * same old hash out of transaction, but only the write whose expected hash still matches
    * succeeds. Returns whether a row was changed; `false` means the account was deactivated or its
    * password already moved on since it was read.
    */
  def compareAndSetPasswordHash(
    id: UUID,
    expectedPasswordHash: String,
    newPasswordHash: String,
    updatedAt: Instant
  ): F[Boolean]

  /** Replaces the display name of one active account. Returns whether a row was changed. */
  def updateDisplayName(id: UUID, displayName: String, updatedAt: Instant): F[Boolean]
}
