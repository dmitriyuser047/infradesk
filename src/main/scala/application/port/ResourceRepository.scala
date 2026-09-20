package ru.bitec.app.ops
package application.port

import ru.bitec.app.ops.domain.resource.Resource

import java.util.UUID
import scala.language.higherKinds

trait ResourceRepository[F[_]] {

  def findById(organizationId: UUID, id:UUID): F[Option[Resource]]

  def save(resource: Resource): F[Unit]

}
