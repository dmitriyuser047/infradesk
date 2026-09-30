package ru.bitec.app.ops
package application.port

import domain.integration.{IntegrationConfigDeployment, IntegrationConfigProfileBinding}
import java.time.Instant
import java.util.UUID

/** Only a point read of one revision yields plaintext. List and history operations never do. */
final case class IntegrationSecureRevision(revisionId: UUID, organizationId: UUID, profileId: UUID,
  revisionNumber: Int, contentSha256: String, canonicalJson: String) {
  override def toString: String =
    s"IntegrationSecureRevision($revisionId,$organizationId,$profileId,$revisionNumber,$contentSha256,<redacted>)"
}

trait IntegrationConfigProfileRepository[F[_]] {
  def binding(organizationId: UUID, integrationId: UUID, objectId: UUID): F[Option[IntegrationConfigProfileBinding]]
  def detachAll(organizationId: UUID, integrationId: UUID, at: Instant): F[Unit]
  def insertBinding(value: IntegrationConfigProfileBinding): F[Boolean]
  def insertSecureRevision(revisionId: UUID, organizationId: UUID, profileId: UUID,
    canonicalJson: String, at: Instant): F[Unit]
  def secureRevision(organizationId: UUID, profileId: UUID, revisionNumber: Int): F[Option[IntegrationSecureRevision]]
  def revisionHash(organizationId: UUID, profileId: UUID, revisionNumber: Int): F[Option[String]]
}

trait IntegrationConfigDeploymentRepository[F[_]] {
  def findByRequest(organizationId: UUID, requestId: UUID): F[Option[IntegrationConfigDeployment]]
  def insertOrFind(value: IntegrationConfigDeployment): F[(IntegrationConfigDeployment, Boolean)]
  def recent(organizationId: UUID, integrationId: UUID, objectId: UUID, limit: Int): F[List[IntegrationConfigDeployment]]
  def recentForBinding(organizationId: UUID, bindingId: UUID, limit: Int): F[List[IntegrationConfigDeployment]]
  def latestSucceededHash(organizationId: UUID, bindingId: UUID): F[Option[String]]
  def hasActive(organizationId: UUID, integrationId: UUID): F[Boolean]
  def recoverAndClaim(owner: UUID, token: UUID, at: Instant, recoverAfter: Instant,
    limit: Int): F[(List[(UUID, UUID)], List[IntegrationConfigDeployment])]
  def complete(value: IntegrationConfigDeployment, token: UUID, at: Instant,
    status: String, errorCode: Option[String], errorMessage: Option[String]): F[Boolean]
}
