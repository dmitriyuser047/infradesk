package ru.bitec.app.ops
package application.port

import domain.audit.{AuditCursor, AuditEvent}

import java.util.UUID

/** The audit journal.
  *
  * Append-only on purpose: the port offers no update and no delete, so application code cannot
  * rewrite history. Every method is scoped by organization, like the rest of the tenant data.
  */
trait AuditEventRepository[F[_]] {

  def save(event: AuditEvent): F[Unit]

  def saveAll(events: List[AuditEvent]): F[Unit]

  /** Newest first, continuing after `before` when a previous page supplied a cursor. */
  def listByOrganization(
    organizationId: UUID,
    before: Option[AuditCursor],
    limit: Int
  ): F[List[AuditEvent]]
}
