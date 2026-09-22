package ru.bitec.app.ops
package application.port

import domain.organization.Organization

import java.util.UUID

trait OrganizationRepository[F[_]] {
  def findActiveById(id: UUID): F[Option[Organization]]
}
