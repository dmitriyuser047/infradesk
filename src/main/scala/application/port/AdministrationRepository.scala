package ru.bitec.app.ops
package application.port

import domain.auth.OrganizationRole
import java.time.Instant
import java.util.UUID

final case class AdministrationUser(id: UUID, email: String, displayName: String, isActive: Boolean,
  isAdministrator: Boolean, updatedAt: Instant)
final case class AdministrationOrganization(id: UUID, code: String, name: String)
final case class AdministrationMember(user: AdministrationUser, organizationId: UUID, role: OrganizationRole,
  isActive: Boolean, updatedAt: Instant, organizationName: String)
final case class AdministrationRequest(actorId: UUID, kind: String, fingerprint: String, targetId: UUID)
final case class AdministrationAudit(id: UUID, actorId: UUID, action: String, targetId: UUID,
  organizationId: Option[UUID], occurredAt: Instant)

/** Administration projections contain no credentials. Writes share a short access-change lock;
  * this serializes last-owner/admin checks and authorization with the changes they protect.
  */
trait AdministrationRepository[F[_]] {
  def lockAccessChanges: F[Unit]
  def isAdministrator(userId: UUID): F[Boolean]
  def administratorProvisioned: F[Boolean]
  def bootstrapFirstAdministrator(userId: UUID, at: Instant): F[Unit]
  def findUser(userId: UUID): F[Option[AdministrationUser]]
  def listUsers(after: Option[UUID], limit: Int): F[List[AdministrationUser]]
  def listOrganizations(after: Option[UUID], limit: Int): F[List[AdministrationOrganization]]
  def listMembers(organizationId: UUID, after: Option[UUID], limit: Int): F[List[AdministrationMember]]
  def listUserMemberships(userId: UUID, after: Option[UUID], limit: Int): F[List[AdministrationMember]]
  def findMember(organizationId: UUID, userId: UUID): F[Option[AdministrationMember]]
  def saveMember(organizationId: UUID, userId: UUID, role: OrganizationRole, active: Boolean, at: Instant): F[Unit]
  def countOtherOwners(organizationId: UUID, userId: UUID): F[Long]
  def hasSoleOwnership(userId: UUID): F[Boolean]
  def countOtherAdministrators(userId: UUID): F[Long]
  def setUserStatus(userId: UUID, active: Boolean, administrator: Boolean, expectedUpdatedAt: Instant, at: Instant): F[Boolean]
  def revokeUserSessions(userId: UUID, at: Instant): F[Unit]
  def createOrganization(id: UUID, code: String, name: String, at: Instant): F[Boolean]
  def findRequest(id: UUID): F[Option[AdministrationRequest]]
  def saveRequest(id: UUID, request: AdministrationRequest, at: Instant): F[Unit]
  def recordAudit(event: AdministrationAudit): F[Unit]
  def listAudit(after: Option[UUID], limit: Int): F[List[AdministrationAudit]]
}
