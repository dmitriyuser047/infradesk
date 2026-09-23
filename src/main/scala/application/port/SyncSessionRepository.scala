package ru.bitec.app.ops
package application.port

import domain.sync.SyncSession

import java.time.Instant
import java.util.UUID

trait SyncSessionRepository[F[_]] {
  def findLatestByConnection(
    organizationId: UUID,
    connectionId: UUID
  ): F[Option[SyncSession]]

  def findRecentByConnection(organizationId: UUID, connectionId: UUID, limit: Int): F[List[SyncSession]]
  def findById(organizationId: UUID, connectionId: UUID, sessionId: UUID): F[Option[SyncSession]]

  def create(session: SyncSession): F[Unit]
  def tryCreate(session: SyncSession): F[Boolean]
  def recoverStaleAndTryCreate(
    session: SyncSession,
    staleBefore: Instant,
    recoveredAt: Instant,
    errorCode: String,
    errorMessage: String
  ): F[Boolean]
  def complete(organizationId: UUID, id: UUID, finishedAt: Instant): F[Unit]
  def fail(organizationId: UUID, id: UUID, finishedAt: Instant, errorCode: String, errorMessage: String): F[Unit]
}
