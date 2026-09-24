package ru.bitec.app.ops
package application.port

import domain.sync.SyncSession

import java.time.Instant
import java.util.UUID

/** What claiming a synchronization slot did: whether it was taken, and which abandoned sessions
  * it had to retire first.
  */
final case class SyncSessionClaim(created: Boolean, recovered: List[SyncSession])

trait SyncSessionRepository[F[_]] {
  def findLatestByConnection(
    organizationId: UUID,
    connectionId: UUID
  ): F[Option[SyncSession]]

  def findRecentByConnection(organizationId: UUID, connectionId: UUID, limit: Int): F[List[SyncSession]]
  def findById(organizationId: UUID, connectionId: UUID, sessionId: UUID): F[Option[SyncSession]]

  def create(session: SyncSession): F[Unit]
  def tryCreate(session: SyncSession): F[Boolean]
  /** Returns the retired sessions themselves, so the caller can journal them without asking for
    * them again.
    */
  def recoverStaleAndTryCreate(
    session: SyncSession,
    staleBefore: Instant,
    recoveredAt: Instant,
    errorCode: String,
    errorMessage: String
  ): F[SyncSessionClaim]
  def complete(organizationId: UUID, id: UUID, finishedAt: Instant): F[Unit]
  def fail(organizationId: UUID, id: UUID, finishedAt: Instant, errorCode: String, errorMessage: String): F[Unit]
}
