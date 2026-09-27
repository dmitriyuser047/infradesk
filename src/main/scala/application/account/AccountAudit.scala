package ru.bitec.app.ops
package application.account

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.OrganizationMembershipRepository
import cats.Monad
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}

import java.util.UUID

/** Journals a self-service account action inside the transaction that performed it.
  *
  * An account action is not tied to one organization, but the journal is per-organization, so the
  * event is recorded once for each organization the user can act in — one row for the common
  * single-organization user. The memberships are read once here, never per row, and the entry
  * carries identifiers only: the target is the account, so no session, token, password or hash
  * reaches the journal.
  */
final class AccountAudit[Tx[_]: Monad](
  audit: AuditRecorder[Tx],
  memberships: OrganizationMembershipRepository[Tx]
) {
  def record(userId: UUID, action: AuditAction): Tx[Unit] =
    memberships.listActiveOrganizations(userId).flatMap(_.traverse_(org =>
      audit.record(ActorContext(userId, org.id), action, AuditTargetType.Account, Some(userId))))
}
