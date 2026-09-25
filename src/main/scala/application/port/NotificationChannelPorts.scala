package ru.bitec.app.ops
package application.port

import domain.notification.{NotificationChannel, NotificationChannelCredential}

import java.util.UUID

/** A channel credential as it is stored: opaque bytes plus what they are bound to.
  *
  * The same shape as an SSH secret and, deliberately, a different table: the lifetime of a
  * channel credential follows its channel, not a connection.
  */
final case class NotificationChannelSecret(
  id: UUID,
  organizationId: UUID,
  kind: String,
  nonce: Array[Byte],
  ciphertext: Array[Byte]
)

trait NotificationChannelSecretRepository[F[_]] {
  def save(secret: NotificationChannelSecret): F[Unit]
  def find(organizationId: UUID, id: UUID): F[Option[NotificationChannelSecret]]
  def delete(organizationId: UUID, id: UUID): F[Unit]
}

/** Encrypts and decrypts channel credentials. Implemented in the integration layer on the one
  * encryption primitive the application has.
  */
trait NotificationChannelCryptography {
  def encrypt(
    id: UUID,
    organizationId: UUID,
    credential: NotificationChannelCredential
  ): NotificationChannelSecret

  def decrypt(secret: NotificationChannelSecret): NotificationChannelCredential
}

trait NotificationChannelRepository[F[_]] {

  /** Every channel of one organization, by name, for the settings page. */
  def listByOrganization(organizationId: UUID): F[List[NotificationChannel]]

  def findById(organizationId: UUID, id: UUID): F[Option[NotificationChannel]]

  /** Locks the row for the rest of the transaction, so a concurrent edit of the same channel
    * waits instead of overwriting what it never read.
    */
  def findByIdForUpdate(organizationId: UUID, id: UUID): F[Option[NotificationChannel]]

  /** Insert or replace, by id. */
  def save(channel: NotificationChannel): F[Unit]
}
