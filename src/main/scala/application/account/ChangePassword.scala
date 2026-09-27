package ru.bitec.app.ops
package application.account

import application.auth.PasswordHasher
import application.port.{AuthSessionRepository, SecurityEventRepository, TimeProvider, TransactionRunner, UserAccountRepository}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.audit.AuditAction
import domain.auth.{SecurityEvent, SecurityEventType}

import java.time.Instant
import java.util.UUID

/** Changes the password of the authenticated account, and of no other.
  *
  * The account and the current session both come from the session the request arrived on, never
  * from the body, so a user can only change their own password. The current password is verified
  * through the same [[PasswordHasher]] the login uses, and the new one is hashed by it too: there
  * is one hashing path in the application and this is not a second.
  *
  * Hashing is expensive and blocking, so — exactly as the login does — the read and the write are
  * their own short transactions and BCrypt runs between them, never inside a transaction holding a
  * connection. The write is one transaction and does three things together: a compare-and-set of
  * the password hash, the revocation of every other session, and the audit entry. They commit or
  * roll back as one.
  *
  * The compare-and-set is what makes two concurrent changes safe. Both may verify the same old
  * hash, but the UPDATE carries the hash each request read as its expected value, so only the first
  * write matches and succeeds; the second finds a different hash, changes nothing, revokes nothing,
  * writes no audit, and is refused with a stable code. The user can retry with the now-current
  * password. No lock is held across the hashing.
  */
final class ChangePassword[Tx[_]: Monad](
  users: UserAccountRepository[Tx],
  sessions: AuthSessionRepository[Tx],
  securityEvents: SecurityEventRepository[Tx],
  accountAudit: AccountAudit[Tx],
  runner: TransactionRunner[IO, Tx],
  passwords: PasswordHasher,
  time: TimeProvider[IO]
) {

  def execute(
    userId: UUID,
    currentSessionId: UUID,
    currentPassword: String,
    newPassword: String
  ): IO[Either[AccountError, Unit]] =
    runner.run(users.findActiveById(userId)).flatMap {
      case None => IO.pure(Left(AccountError.AccountNotFound))
      case Some(user) =>
        passwords.verify(currentPassword, user.passwordHash).flatMap {
          case false => IO.pure(Left(AccountError.CurrentPasswordInvalid))
          case true =>
            AccountValidation.password(newPassword, currentPassword) match {
              case Left(error) => IO.pure(Left(error))
              case Right(_) =>
                for {
                  hash <- passwords.hash(newPassword)
                  now <- time.now
                  applied <- runner.run(write(userId, currentSessionId, user.passwordHash, hash, now))
                } yield Either.cond(applied, (), AccountError.AccountStateChanged)
            }
        }
    }

  /** The whole security effect of a password change, in one transaction. If the compare-and-set
    * finds a different hash it changes nothing, and neither the revocation nor the audit runs.
    */
  private def write(
    userId: UUID,
    currentSessionId: UUID,
    expectedHash: String,
    newHash: String,
    now: Instant
  ): Tx[Boolean] =
    users.compareAndSetPasswordHash(userId, expectedHash, newHash, now).flatMap {
      case false => false.pure[Tx]
      case true =>
        // The current session survives; every other session of this user is ended, so a stolen or
        // stale one stops working the moment the password changes.
        sessions.revokeOthersForUser(userId, currentSessionId, now) *>
          accountAudit.record(userId, AuditAction.AccountPasswordChanged) *>
          securityEvents.save(SecurityEvent(UUID.randomUUID(), userId, SecurityEventType.PasswordChanged,
            now, Some(currentSessionId), None, None)).as(true)
    }
}
