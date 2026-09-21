package ru.bitec.app.ops
package application.port

import domain.resource.Resource

import java.util.UUID

trait ConnectionSynchronizer[F[_]] {
  def execute(organizationId: UUID, connectionId: UUID): F[List[Resource]]
}
