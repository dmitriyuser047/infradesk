package ru.bitec.app.ops
package application.port

import domain.resource.ResourceType

import java.util.UUID
import scala.language.higherKinds

trait ResourceTypeRepository[F[_]] {

  def findById(id: UUID): F[Option[ResourceType]]

  def findByCode(code: String): F[Option[ResourceType]]
}