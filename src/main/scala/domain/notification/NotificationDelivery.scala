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

/** Where an event is delivered. Webhook is the only channel this version ships. */
sealed trait NotificationChannel {
  def code: String
}

object NotificationChannel {

  case object Webhook extends NotificationChannel {
    override val code: String = "WEBHOOK"
  }

  val All: List[NotificationChannel] = List(Webhook)

  def fromCode(code: String): Either[IllegalArgumentException, NotificationChannel] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported notification channel '$code'"))
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
  channel: NotificationChannel,
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
)
