package ru.bitec.app.ops
package application.port

import domain.auth.OrganizationMembership

import java.util.UUID

final case class MyOrganization(id: UUID, code: String, name: String, role: domain.auth.OrganizationRole)

trait OrganizationMembershipRepository[F[_]] {
  def findActiveRole(userId: UUID, organizationId: UUID): F[Option[domain.auth.OrganizationRole]]
  def hasActiveMembership(userId: UUID, organizationId: UUID): F[Boolean]
  def listActiveOrganizations(userId: UUID): F[List[MyOrganization]]
  def createIfMissing(membership: OrganizationMembership): F[Unit]
}
