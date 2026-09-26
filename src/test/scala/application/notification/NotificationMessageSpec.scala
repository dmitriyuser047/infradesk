package ru.bitec.app.ops
package application.notification

import domain.incident.IncidentReason
import domain.notification.NotificationEventType
import munit.FunSuite

import java.time.Instant
import java.util.UUID

/** What a notification says when it reaches a person. */
final class NotificationMessageSpec extends FunSuite {

  test("a message is built from the event and says what happened") {
    val opened = NotificationMessage.of(event(NotificationEventType.IncidentOpened,
      IncidentReason.ThresholdViolation))
    val resolved = NotificationMessage.of(event(NotificationEventType.IncidentResolved,
      IncidentReason.NoData))

    assertEquals(opened.subject, "InfraDesk: incident opened (threshold exceeded)")
    assertEquals(resolved.subject, "InfraDesk: incident resolved (no data received)")
    assert(opened.text.contains("Reason: threshold exceeded"))
    assert(opened.text.contains("Occurred at: 2026-09-25T10:00:00Z"))
    assert(opened.text.contains("Incident: a0000000-0000-0000-0000-000000000001"))
    assert(opened.text.contains("Event: c0000000-0000-0000-0000-000000000001"))
    // The same event always reads the same: nothing here depends on a clock or a lookup.
    assertEquals(NotificationMessage.of(event(NotificationEventType.IncidentOpened,
      IncidentReason.ThresholdViolation)), opened)
  }

  private def event(
    eventType: NotificationEventType,
    reason: IncidentReason
  ): NotificationEvent =
    NotificationEvent(
      eventId = UUID.fromString("c0000000-0000-0000-0000-000000000001"),
      eventType = eventType,
      occurredAt = Instant.parse("2026-09-25T10:00:00Z"),
      organizationId = UUID.fromString("20000000-0000-0000-0000-000000000001"),
      resourceId = UUID.fromString("70000000-0000-0000-0000-000000000001"),
      monitorRuleId = UUID.fromString("90000000-0000-0000-0000-000000000001"),
      incidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001"),
      reason = reason
    )
}
