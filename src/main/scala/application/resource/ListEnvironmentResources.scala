package ru.bitec.app.ops
package application.resource

import application.port.ResourceRepository
import domain.resource.Resource

import java.util.UUID

final case class ListEnvironmentResources[Tx[_]](resourceRepository: ResourceRepository[Tx]) {
  def execute(organizationId: UUID, environmentId: UUID): Tx[List[Resource]] =
    resourceRepository.findActiveByEnvironment(organizationId, environmentId)
}
