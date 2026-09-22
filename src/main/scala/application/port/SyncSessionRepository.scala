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

  def create(session: SyncSession): F[Unit]
  def complete(organizationId: UUID, id: UUID, finishedAt: Instant): F[Unit]
  def fail(organizationId: UUID, id: UUID, finishedAt: Instant): F[Unit]
}
