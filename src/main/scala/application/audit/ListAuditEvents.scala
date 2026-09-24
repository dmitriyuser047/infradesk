package ru.bitec.app.ops
package application.audit

import application.port.AuditEventRepository
import domain.audit.{AuditCursor, AuditEvent}

import java.util.UUID

/** Reads a bounded page of the journal, newest first. */
final class ListAuditEvents[Tx[_]](auditEvents: AuditEventRepository[Tx]) {

  def execute(
    organizationId: UUID,
    before: Option[AuditCursor],
    limit: Int
  ): Tx[List[AuditEvent]] =
    auditEvents.listByOrganization(organizationId, before, ListAuditEvents.boundedLimit(limit))
}

object ListAuditEvents {
  val DefaultLimit: Int = 50
  val MaxLimit: Int = 200

  /** A caller can ask for less than the default but never for an unbounded page. */
  def boundedLimit(limit: Int): Int = math.max(1, math.min(limit, MaxLimit))
}
