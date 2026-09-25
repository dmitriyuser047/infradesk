package ru.bitec.app.ops
package domain.notification

import domain.incident.IncidentReason

import java.time.Instant
import java.util.UUID

/** What happened to an incident, as an outbound event. */
sealed trait NotificationEventType {
  def code: String
}

object NotificationEventType {

  case object IncidentOpened extends NotificationEventType {
    override val code: String = "INCIDENT_OPENED"
  }

  case object IncidentResolved extends NotificationEventType {
    override val code: String = "INCIDENT_RESOLVED"
  }

  val All: List[NotificationEventType] = List(IncidentOpened, IncidentResolved)

  def fromCode(code: String): Either[IllegalArgumentException, NotificationEventType] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported notification event type '$code'"))
}

/** Where one delivery is addressed.
  *
  * A managed delivery names the channel row it belongs to, so two Telegram channels of the same
  * organization are two destinations rather than one. The legacy target is the webhook the
  * deployment configures through its environment: it has no channel row, and never will.
  *
  * The identity is the channel, not its configuration. What the channel held when the delivery
  * was recorded is not copied here: a sender resolves the channel as it stands at the moment it
  * sends, which is why a credential rotated after an incident still works.
  */
sealed trait NotificationDeliveryTarget {
  def channelType: NotificationChannelType

  /** The channel row this delivery is addressed to, absent for the legacy webhook. */
  def channelId: Option[UUID]
}

object NotificationDeliveryTarget {

  /** The webhook of INFRADESK_NOTIFICATION_WEBHOOK_URL, which predates configured channels. */
  case object LegacyWebhook extends NotificationDeliveryTarget {
    override val channelType: NotificationChannelType = NotificationChannelType.Webhook
    override val channelId: Option[UUID] = None
  }

  /** One configured channel of an organization.
    *
    * `channelType` is what the channel was when the delivery was recorded. It says how the event
    * was routed; the sender still reads the channel itself.
    */
  final case class Managed(
    id: UUID,
    channelType: NotificationChannelType
  ) extends NotificationDeliveryTarget {
    override val channelId: Option[UUID] = Some(id)
  }
}

sealed trait NotificationDeliveryStatus {
  def code: String
}

object NotificationDeliveryStatus {

  /** Waiting to be delivered, possibly after a failed attempt. */
  case object Pending extends NotificationDeliveryStatus {
    override val code: String = "PENDING"
  }

  case object Sent extends NotificationDeliveryStatus {
    override val code: String = "SENT"
  }

  /** Given up on: a permanent failure or too many attempts. */
  case object Dead extends NotificationDeliveryStatus {
    override val code: String = "DEAD"
  }

  val All: List[NotificationDeliveryStatus] = List(Pending, Sent, Dead)

  def fromCode(code: String): Either[IllegalArgumentException, NotificationDeliveryStatus] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported notification delivery status '$code'"))
}

/** One outbound event, written in the same transaction as the incident change it reports.
  *
  * `occurredAt` is when the incident changed; `createdAt` is when this row was written. The id is
  * the stable event id the receiver sees on every attempt, which is what makes retries safe to
  * deduplicate on the other side.
  */
final case class NotificationDelivery(
  id: UUID,
  organizationId: UUID,
  incidentId: UUID,
  resourceId: UUID,
  monitorRuleId: UUID,
  eventType: NotificationEventType,
  reason: IncidentReason,
  target: NotificationDeliveryTarget,
  occurredAt: Instant,
  status: NotificationDeliveryStatus,
  attemptCount: Long,
  nextAttemptAt: Instant,
  claimedBy: Option[UUID],
  claimedUntil: Option[Instant],
  sentAt: Option[Instant],
  lastErrorCode: Option[String],
  createdAt: Instant,
  updatedAt: Instant
) {
  /** Which transport this delivery needs, whoever it is addressed to. */
  def channelType: NotificationChannelType = target.channelType
}
