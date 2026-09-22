package ru.bitec.app.ops
package application.port

import domain.resource.Resource

import java.util.UUID

final case class ConnectionSyncResult(sessionId: UUID, resources: List[Resource])

trait ConnectionSynchronizer[F[_]] {
  def execute(organizationId: UUID, connectionId: UUID): F[ConnectionSyncResult]
}

trait ConnectionSyncRunner[F[_]] {
  def execute(organizationId: UUID, connectionId: UUID): F[ConnectionSyncResult]
}
