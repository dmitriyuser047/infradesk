package ru.bitec.app.ops
package domain.terminal

import java.time.Instant
import java.util.UUID

sealed trait TerminalSessionState { def code: String }
object TerminalSessionState {
  case object Opening extends TerminalSessionState { val code = "OPENING" }
  case object Active extends TerminalSessionState { val code = "ACTIVE" }
  case object Revoked extends TerminalSessionState { val code = "REVOKED" }
  case object Closed extends TerminalSessionState { val code = "CLOSED" }
}

sealed abstract class TerminalCloseReason(val code: String)
object TerminalCloseReason {
  case object ClientClose extends TerminalCloseReason("CLIENT_CLOSE")
  case object RemoteEof extends TerminalCloseReason("REMOTE_EOF")
  case object IdleTimeout extends TerminalCloseReason("IDLE_TIMEOUT")
  case object MaxLifetime extends TerminalCloseReason("MAX_LIFETIME")
  case object ServerShutdown extends TerminalCloseReason("SERVER_SHUTDOWN")
  case object LeaseExpired extends TerminalCloseReason("LEASE_EXPIRED")
  case object SshOpenFailed extends TerminalCloseReason("SSH_OPEN_FAILED")
  case object SshFailure extends TerminalCloseReason("SSH_FAILURE")
  case object ProtocolError extends TerminalCloseReason("PROTOCOL_ERROR")
  case object AuthSessionEnded extends TerminalCloseReason("AUTH_SESSION_ENDED")
  case object PermissionRevoked extends TerminalCloseReason("TERMINAL_PERMISSION_REVOKED")
  case object ConnectionChanged extends TerminalCloseReason("CONNECTION_CHANGED")
  case object SessionRevoked extends TerminalCloseReason("TERMINAL_SESSION_REVOKED")
  case object ValidationFailed extends TerminalCloseReason("SESSION_VALIDATION_FAILED")
  /** The socket was lost and no socket resumed the shell before the detach timeout. */
  case object DetachTimeout extends TerminalCloseReason("DETACH_TIMEOUT")
  val All: List[TerminalCloseReason] = List(ClientClose, RemoteEof, IdleTimeout, MaxLifetime,
    ServerShutdown, LeaseExpired, SshOpenFailed, SshFailure, ProtocolError, AuthSessionEnded,
    PermissionRevoked, ConnectionChanged, SessionRevoked, ValidationFailed, DetachTimeout)
  def fromCode(code: String): Option[TerminalCloseReason] = All.find(_.code == code)
}

/** Durable lifecycle identifiers only. Terminal bytes and SSH configuration are never persisted. */
final case class TerminalSession(
  id: UUID, organizationId: UUID, connectionId: UUID, actorUserId: UUID, authSessionId: UUID,
  state: TerminalSessionState, leaseOwner: UUID, leaseToken: UUID, leaseExpiresAt: Instant,
  connectionUpdatedAt: Instant, createdAt: Instant, openedAt: Option[Instant],
  closedAt: Option[Instant], closeReason: Option[TerminalCloseReason]
)

sealed trait TerminalClaimResult
object TerminalClaimResult {
  final case class Claimed(session: TerminalSession) extends TerminalClaimResult
  case object CapacityRejected extends TerminalClaimResult
  case object OrganizationUnavailable extends TerminalClaimResult
}
sealed trait TerminalRenewResult
object TerminalRenewResult {
  final case class Renewed(until: Instant) extends TerminalRenewResult
  final case class Revoked(reason: TerminalCloseReason) extends TerminalRenewResult
  case object Lost extends TerminalRenewResult
}
