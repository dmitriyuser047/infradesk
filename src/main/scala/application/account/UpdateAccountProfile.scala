package ru.bitec.app.ops
package application.account

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{OrganizationMembershipRepository, TimeProvider, TransactionRunner, UserAccountRepository}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}

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
  memberships: OrganizationMembershipRepository[Tx],
  audit: AuditRecorder[Tx],
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
    for {
      updated <- users.updateDisplayName(userId, displayName, now)
      _ <- if (updated) recordAudit(userId) else ().pure[Tx]
    } yield updated

  private def recordAudit(userId: UUID): Tx[Unit] =
    memberships.listActiveOrganizations(userId).flatMap(_.traverse_(org =>
      audit.record(ActorContext(userId, org.id), AuditAction.AccountProfileUpdated,
        AuditTargetType.Account, Some(userId))))
}
