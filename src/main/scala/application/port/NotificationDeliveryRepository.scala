package ru.bitec.app.ops
package application.port

import domain.notification.NotificationDelivery

import java.time.Instant
import java.util.UUID

/** Which deliveries a worker is responsible for.
  *
  * A worker serves one kind of target. The legacy worker sends through the webhook its
  * deployment configures and must never pick up a delivery addressed to a configured channel,
  * whose transport it has no way of speaking; a managed worker is the other way round.
  */
sealed trait NotificationDeliveryScope

object NotificationDeliveryScope {

  /** Deliveries of the environment-configured webhook: the ones with no channel row. */
  case object Legacy extends NotificationDeliveryScope

  /** Deliveries addressed to a configured channel. */
  case object Managed extends NotificationDeliveryScope
}

/** The notification outbox.
  *
  * Writes happen inside the business transaction that produced the event; claiming and finishing
  * a delivery are separate short transactions around the network call.
  */
trait NotificationDeliveryRepository[F[_]] {

  /** Idempotent: recording the same incident event twice leaves one row. */
  def saveAll(deliveries: List[NotificationDelivery]): F[Unit]

  def findById(organizationId: UUID, id: UUID): F[Option[NotificationDelivery]]

  /** Atomically leases due deliveries of one scope to one dispatcher instance.
    *
    * The scope is part of the claim rather than a filter applied afterwards: a worker cannot
    * take a delivery it is not able to send and then have to put it back.
    */
  def claimPending(
    scope: NotificationDeliveryScope,
    claimedBy: UUID,
    limit: Int,
    leaseSeconds: Long
  ): F[List[NotificationDelivery]]

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
