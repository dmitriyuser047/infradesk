package ru.bitec.app.ops
package application.port

import domain.organization.Organization

import java.time.Instant
import java.util.UUID

trait OrganizationRepository[F[_]] {
  def findActiveById(id: UUID): F[Option[Organization]]
}

/** First-boot provisioning only: an installer names the organization its first administrator owns. */
trait OrganizationProvisioning[F[_]] {
  /** Creates the organization unless one with this id or code already exists; never changes one. */
  def createIfMissing(id: UUID, code: String, name: String, at: Instant): F[Unit]
}
