package ru.bitec.app.ops
package application.account

import application.audit.AuditRecorder
import application.auth.{ActorContext, PasswordHasher}
import application.port.{OrganizationMembershipRepository, TimeProvider, TransactionRunner, UserAccountRepository}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}

import java.time.Instant
import java.util.UUID

/** Changes the password of the authenticated account, and of no other.
  *
  * The account is the one the session identifies; the caller passes only that user's id, never an
  * id or an email from the request body, so a user can only ever change their own password. The
  * current password is verified through the same [[PasswordHasher]] the login uses, and the new
  * one is hashed by it too: there is one hashing path in the application and this is not a second.
  *
  * Hashing is expensive and blocking, so — exactly as the login does — the two database steps run
  * as their own short transactions and BCrypt runs between them, never inside a transaction holding
  * a connection. The write itself is one transaction: the password update and the audit entries
  * commit together, so a failed audit rolls the password change back.
  *
  * Two concurrent changes race on a plain bounded UPDATE, last writer wins. That is acceptable and
  * needs no lock: both requests proved the current password, so both are authorized, and neither
  * can corrupt the row — one of the two new passwords simply prevails. No JVM mutex is held across
  * the hashing or the network.
  */
final class ChangePassword[Tx[_]: Monad](
  users: UserAccountRepository[Tx],
  memberships: OrganizationMembershipRepository[Tx],
  audit: AuditRecorder[Tx],
  runner: TransactionRunner[IO, Tx],
  passwords: PasswordHasher,
  time: TimeProvider[IO]
) {

  def execute(userId: UUID, currentPassword: String, newPassword: String): IO[Either[AccountError, Unit]] =
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
                  applied <- runner.run(write(userId, hash, now))
                } yield Either.cond(applied, (), AccountError.AccountNotFound)
            }
        }
    }

  private def write(userId: UUID, hash: String, now: Instant): Tx[Boolean] =
    for {
      updated <- users.updatePasswordHash(userId, hash, now)
      _ <- if (updated) recordAudit(userId) else ().pure[Tx]
    } yield updated

  /** A password change is a security event for every organization the user can act in, so each of
    * their journals records it. For the common single-organization user this is one row. The
    * memberships are read once here, not per row.
    */
  private def recordAudit(userId: UUID): Tx[Unit] =
    memberships.listActiveOrganizations(userId).flatMap(_.traverse_(org =>
      audit.record(ActorContext(userId, org.id), AuditAction.AccountPasswordChanged,
        AuditTargetType.Account, Some(userId))))
}
