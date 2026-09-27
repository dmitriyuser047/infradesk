package ru.bitec.app.ops
package application.account

import application.port.{TimeProvider, TransactionRunner, UserAccountRepository}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.audit.AuditAction

import java.time.Instant
import java.util.UUID

/** Updates the profile of the authenticated account. Today that is the display name only; the
  * email is an identity/login field and is not changed here.
  *
  * The account is the session's, never an id from the body. The update and its audit entries are
  * one transaction, so a failed audit rolls the change back. The returned value is the normalized
  * name that was stored, so the caller can answer with it without a re-read.
  */
final class UpdateAccountProfile[Tx[_]: Monad](
  users: UserAccountRepository[Tx],
  accountAudit: AccountAudit[Tx],
  runner: TransactionRunner[IO, Tx],
  time: TimeProvider[IO]
) {

  def execute(userId: UUID, rawDisplayName: String): IO[Either[AccountError, String]] =
    AccountValidation.displayName(rawDisplayName) match {
      case Left(error) => IO.pure(Left(error))
      case Right(displayName) =>
        for {
          now <- time.now
          applied <- runner.run(write(userId, displayName, now))
        } yield Either.cond(applied, displayName, AccountError.AccountNotFound)
    }

  private def write(userId: UUID, displayName: String, now: Instant): Tx[Boolean] =
    users.updateDisplayName(userId, displayName, now).flatMap {
      case false => false.pure[Tx]
      case true => accountAudit.record(userId, AuditAction.AccountProfileUpdated).as(true)
    }
}
