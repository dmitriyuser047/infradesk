package ru.bitec.app.ops
package application.notification

import application.port.{NotificationSendRequest, NotificationSendResult, NotificationSender}
import cats.effect.IO
import domain.incident.IncidentReason
import domain.notification.{NotificationChannelType, NotificationDeliveryTarget, NotificationEventType}

import java.time.Instant
import java.util.UUID

/** Sends one synthetic event through a channel's current, real transport, so a person can see it
  * reach a chat, an inbox or a webhook receiver without waiting for an incident.
  *
  * This reuses the exact sender the managed dispatcher delivers real notifications through: the
  * same tenant-safe read of the channel and its secret, the same short transaction, the same
  * decryption, the same transport chosen by the channel's current settings. There is no delivery
  * row for it to write and no lease to hold, since nothing here is retried; a disabled channel is
  * exactly as testable as an enabled one, because `enabled` decides which real deliveries are
  * recorded, not what a sender is allowed to send.
  *
  * The event carries nil identifiers rather than a new event type of its own: a real incident can
  * never have one, which marks the message a receiver sees as a test without a schema change or a
  * new line in `NotificationMessage`.
  */
final class TestNotificationChannel(sender: NotificationSender[IO]) {

  import TestNotificationChannel._

  def execute(
    organizationId: UUID,
    channelId: UUID,
    channelType: NotificationChannelType
  ): IO[NotificationSendResult] =
    sender.send(NotificationSendRequest(
      syntheticEvent(organizationId),
      NotificationDeliveryTarget.Managed(channelId, channelType)
    ))

  private def syntheticEvent(organizationId: UUID): NotificationEvent =
    NotificationEvent(
      eventId = UUID.randomUUID(),
      eventType = NotificationEventType.IncidentOpened,
      occurredAt = Instant.now(),
      organizationId = organizationId,
      resourceId = NilId,
      monitorRuleId = NilId,
      incidentId = NilId,
      reason = IncidentReason.ThresholdViolation
    )
}

object TestNotificationChannel {
  private val NilId = new UUID(0L, 0L)
}
