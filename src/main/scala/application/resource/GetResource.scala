package ru.bitec.app.ops
package application.resource

import application.port.ResourceRepository

import ru.bitec.app.ops.domain.resource.Resource

import java.util.UUID

final case class GetResource[F[_]](
                                  resourceRepository: ResourceRepository[F]
                                  ) {
  def execute(organizationId: UUID, resourceId: UUID): F[Option[Resource]] = {
    resourceRepository.findById(organizationId, resourceId)
  }

}
