package ru.bitec.app.ops
package application.port

import domain.enviroment.Environment

import java.util.UUID

trait EnvironmentRepository[F[_]] {
  def findActiveByProject(
    organizationId: UUID,
    projectId: UUID
  ): F[List[Environment]]
}
