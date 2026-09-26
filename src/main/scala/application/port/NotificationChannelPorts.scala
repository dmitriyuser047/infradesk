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

/** A channel and its credential as one read, for the moment of sending.
  *
  * What the channel holds now, not what it held when the delivery was recorded: a credential
  * rotated after an incident is the one that gets used.
  */
final case class NotificationChannelDispatchTarget(
  channel: NotificationChannel,
  /** Absent only if the credential row went missing under the channel that points at it, which
    * the foreign key does not allow; it is still told apart from a missing channel.
    */
  secret: Option[NotificationChannelSecret]
)

/** The send-time read model: one statement, scoped to the tenant of the delivery.
  *
  * Separate from the lifecycle repository because it answers a different question and is the
  * only place a credential is read outside of writing one.
  */
trait NotificationChannelDispatchQuery[F[_]] {
  def find(organizationId: UUID, channelId: UUID): F[Option[NotificationChannelDispatchTarget]]
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
