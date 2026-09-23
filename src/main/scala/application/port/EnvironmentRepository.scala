package ru.bitec.app.ops
package application.port

import domain.enviroment.Environment

import java.util.UUID

trait EnvironmentRepository[F[_]] {
  def tryCreate(environment: Environment): F[Boolean]
  def findActiveByProject(
    organizationId: UUID,
    projectId: UUID
  ): F[List[Environment]]
}
