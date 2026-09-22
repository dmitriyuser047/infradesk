package ru.bitec.app.ops
package application.port

import ru.bitec.app.ops.domain.resource.Resource

import java.time.Instant
import java.util.UUID
import scala.language.higherKinds

trait ResourceRepository[F[_]] {

  def findById(organizationId: UUID, id:UUID): F[Option[Resource]]

  def findActiveByEnvironment(organizationId: UUID, environmentId: UUID): F[List[Resource]]

  def save(resource: Resource): F[Unit]

  def deactivateIfExclusiveToConnection(
                                        organizationId: UUID,
                                        id: UUID,
                                        connectionId: UUID,
                                        now: Instant
                                      ): F[Unit]

}
