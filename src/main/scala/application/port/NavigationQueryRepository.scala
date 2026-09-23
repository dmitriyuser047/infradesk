package ru.bitec.app.ops
package application.port

import application.navigation.EnvironmentContext

import java.util.UUID

trait NavigationQueryRepository[F[_]] {
  def findEnvironmentContext(
    organizationId: UUID,
    environmentId: UUID
  ): F[Option[EnvironmentContext]]
}
