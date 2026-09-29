package ru.bitec.app.ops
package application.port

import domain.terminal._
import java.time.Instant
import java.util.UUID

trait TerminalSessionRepository[Tx[_]] {
  def claim(session: TerminalSession, userLimit: Int, organizationLimit: Int): Tx[TerminalClaimResult]
  def activate(organizationId: UUID, sessionId: UUID, token: UUID, now: Instant, until: Instant): Tx[Boolean]
  def renew(organizationId: UUID, sessionId: UUID, token: UUID, now: Instant, until: Instant): Tx[TerminalRenewResult]
  def closeOwned(organizationId: UUID, sessionId: UUID, token: UUID, now: Instant, reason: TerminalCloseReason): Tx[Boolean]
  def revoke(organizationId: UUID, sessionId: UUID, now: Instant): Tx[Boolean]
  def reapExpired(now: Instant, limit: Int): Tx[Int]
}
