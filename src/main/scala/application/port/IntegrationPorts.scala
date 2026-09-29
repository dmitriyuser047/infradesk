package ru.bitec.app.ops
package application.port

import domain.integration.{Integration, IntegrationCredential}
import java.util.UUID

final case class IntegrationSecret(id: UUID, organizationId: UUID, kind: String,
  nonce: Array[Byte], ciphertext: Array[Byte])

trait IntegrationSecretRepository[F[_]] {
  def save(secret: IntegrationSecret): F[Unit]
  def find(organizationId: UUID, id: UUID): F[Option[IntegrationSecret]]
  def delete(organizationId: UUID, id: UUID): F[Unit]
}

trait IntegrationCryptography {
  def encrypt(id: UUID, organizationId: UUID, credential: IntegrationCredential): IntegrationSecret
  def decrypt(secret: IntegrationSecret): IntegrationCredential
}

trait IntegrationRepository[F[_]] {
  def listByOrganization(organizationId: UUID): F[List[Integration]]
  def findById(organizationId: UUID, id: UUID): F[Option[Integration]]
  def findByIdForUpdate(organizationId: UUID, id: UUID): F[Option[Integration]]
  def save(integration: Integration): F[Unit]
  def delete(organizationId: UUID, id: UUID): F[Unit]
}
