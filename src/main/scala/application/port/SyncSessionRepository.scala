package ru.bitec.app.ops
package application.port

import domain.connection.Connection
import domain.sync.SyncSession

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** What claiming a synchronization slot did: whether it was taken, and which abandoned sessions
  * it had to retire first.
  */
final case class SyncSessionClaim(created: Boolean, recovered: List[SyncSession])

/** How long one synchronization attempt against this connection may legitimately take.
  *
  * The transport decides it: the application only asks, so a connector's own timeouts, and not a
  * constant, define when its attempt can be considered abandoned.
  */
trait ConnectionSyncBudget {
  def maxAttemptDuration(connection: Connection): FiniteDuration
}

trait SyncSessionRepository[F[_]] {
  def findLatestByConnection(
    organizationId: UUID,
    connectionId: UUID
  ): F[Option[SyncSession]]

  def findRecentByConnection(organizationId: UUID, connectionId: UUID, limit: Int): F[List[SyncSession]]
  def findById(organizationId: UUID, connectionId: UUID, sessionId: UUID): F[Option[SyncSession]]

  def create(session: SyncSession): F[Unit]
  def tryCreate(session: SyncSession): F[Boolean]
  /** Retires the running sessions of this connection whose own deadline has passed, then tries
    * to claim the slot. Returns the retired sessions themselves, so the caller can journal them
    * without asking for them again.
    */
  def recoverStaleAndTryCreate(
    session: SyncSession,
    at: Instant,
    errorCode: String,
    errorMessage: String
  ): F[SyncSessionClaim]
  def complete(organizationId: UUID, id: UUID, finishedAt: Instant): F[Unit]
  def fail(organizationId: UUID, id: UUID, finishedAt: Instant, errorCode: String, errorMessage: String): F[Unit]
}
