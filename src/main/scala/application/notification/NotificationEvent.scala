package ru.bitec.app.ops
package application.notification

import domain.incident.IncidentReason
import domain.notification.{NotificationContext, NotificationDelivery, NotificationEventType}

import java.time.Instant
import java.util.UUID

/** What a channel adapter needs to deliver, without any persistence or retry bookkeeping.
  *
  * `eventId` is the delivery id and stays the same across retries, so a receiver can deduplicate.
  */
final case class NotificationEvent(
  eventId: UUID,
  eventType: NotificationEventType,
  occurredAt: Instant,
  organizationId: UUID,
  resourceId: UUID,
  monitorRuleId: UUID,
  incidentId: UUID,
  reason: IncidentReason,
  /** The descriptive snapshot a human-facing channel renders from and a webhook enriches with,
    * carried from the delivery so no transport reads the database to build a message.
    */
  context: Option[NotificationContext] = None
)

object NotificationEvent {
  def from(delivery: NotificationDelivery): NotificationEvent =
    NotificationEvent(
      delivery.id,
      delivery.eventType,
      delivery.occurredAt,
      delivery.organizationId,
      delivery.resourceId,
      delivery.monitorRuleId,
      delivery.incidentId,
      delivery.reason,
      delivery.context
    )
}
