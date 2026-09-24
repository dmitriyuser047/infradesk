package ru.bitec.app.ops
package application.port

import domain.notification.NotificationDelivery

import java.time.Instant
import java.util.UUID

/** The notification outbox.
  *
  * Writes happen inside the business transaction that produced the event; claiming and finishing
  * a delivery are separate short transactions around the network call.
  */
trait NotificationDeliveryRepository[F[_]] {

  /** Idempotent: recording the same incident event twice leaves one row. */
  def saveAll(deliveries: List[NotificationDelivery]): F[Unit]

  def findById(organizationId: UUID, id: UUID): F[Option[NotificationDelivery]]

  /** Atomically leases due deliveries to one dispatcher instance. */
  def claimPending(claimedBy: UUID, limit: Int, leaseSeconds: Long): F[List[NotificationDelivery]]

  /** Fenced by `claimedBy`: false means the lease expired and someone else owns the delivery. */
  def markSent(organizationId: UUID, id: UUID, claimedBy: UUID, sentAt: Instant): F[Boolean]

  def reschedule(
    organizationId: UUID,
    id: UUID,
    claimedBy: UUID,
    attemptCount: Long,
    nextAttemptAt: Instant,
    errorCode: String,
    updatedAt: Instant
  ): F[Boolean]

  def markDead(
    organizationId: UUID,
    id: UUID,
    claimedBy: UUID,
    attemptCount: Long,
    errorCode: String,
    updatedAt: Instant
  ): F[Boolean]
}
