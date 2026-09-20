package ru.bitec.app.ops
package application.port

import domain.connection.Connection

import java.util.UUID
import scala.language.higherKinds


trait ConnectionRepository[F[_]] {

  def findById(organizationId: UUID, id: UUID): F[Option[Connection]]

  def save(connection: Connection): F[Unit]
}
