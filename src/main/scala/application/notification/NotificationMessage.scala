package ru.bitec.app.ops
package application.notification

import domain.incident.IncidentReason
import domain.notification.NotificationEventType

/** What a person reads when a notification arrives.
  *
  * Built from the event alone, by a pure function: a channel that wants a prettier message does
  * not get to make the worker load a resource, a rule or an organization on the way to sending.
  * What the event carries is what the message says.
  *
  * Webhooks keep their structured payload; this is for the transports that reach a human.
  */
final case class NotificationMessage(subject: String, text: String)

object NotificationMessage {

  def of(event: NotificationEvent): NotificationMessage = {
    val headline = eventLabel(event.eventType)
    NotificationMessage(
      subject = s"InfraDesk: $headline (${reasonLabel(event.reason)})",
      text = List(
        s"InfraDesk: $headline",
        s"Reason: ${reasonLabel(event.reason)}",
        s"Occurred at: ${event.occurredAt}",
        s"Incident: ${event.incidentId}",
        s"Resource: ${event.resourceId}",
        s"Monitor rule: ${event.monitorRuleId}",
        s"Event: ${event.eventId}"
      ).mkString("\n")
    )
  }

  private def eventLabel(eventType: NotificationEventType): String = eventType match {
    case NotificationEventType.IncidentOpened => "incident opened"
    case NotificationEventType.IncidentResolved => "incident resolved"
  }

  private def reasonLabel(reason: IncidentReason): String = reason match {
    case IncidentReason.ThresholdViolation => "threshold exceeded"
    case IncidentReason.NoData => "no data received"
  }
}
