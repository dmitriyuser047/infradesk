package ru.bitec.app.ops
package application.notification

import domain.incident.IncidentReason
import domain.metric.MetricCode
import domain.monitor.MonitorOperator
import domain.notification.{NotificationContext, NotificationEventType}
import munit.FunSuite

import java.time.Instant
import java.util.UUID

/** What a notification says when it reaches a person. */
final class NotificationMessageSpec extends FunSuite {

  test("an event without a context falls back to the identifier-only message") {
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

  // A. Incident opened, threshold ---------------------------------------------------------------

  test("an opened threshold event reads by name, not by identifier") {
    val message = NotificationMessage.of(event(NotificationEventType.IncidentOpened,
      IncidentReason.ThresholdViolation, Some(context())))

    // What happened, where, the rule, the values.
    assert(message.text.contains("инцидент открыт"), message.text)
    assert(message.text.contains("Сервер: production-01"), message.text)
    assert(message.text.contains("Тип: Node"), message.text)
    assert(message.text.contains("Окружение: production"), message.text)
    assert(message.text.contains("Проект: Payments"), message.text)
    assert(message.text.contains("Использование CPU выше допустимого"), message.text)
    assert(message.text.contains("Текущее значение: 92%"), message.text)
    assert(message.text.contains("Порог: > 80%"), message.text)
    assert(message.text.contains("Статус: OPEN"), message.text)
    // The subject names the metric and the server, so an inbox is scannable.
    assertEquals(message.subject, "[InfraDesk] Инцидент открыт: Использование CPU — production-01")

    // No raw identifiers leak into the body a person reads.
    List(EventId, IncidentId, ResourceId, RuleId).foreach(id =>
      assert(!message.text.contains(id.toString), s"$id leaked into ${message.text}"))
    assert(!message.text.toLowerCase.contains("null"), message.text)
  }

  // B. Incident resolved ------------------------------------------------------------------------

  test("a resolved event reads as a recovery and shows how long it lasted") {
    val message = NotificationMessage.of(event(NotificationEventType.IncidentResolved,
      IncidentReason.ThresholdViolation,
      Some(context(currentValue = Some(BigDecimal(43)), durationSeconds = Some(504)))))

    assert(message.text.contains("инцидент закрыт"), message.text)
    assert(message.text.contains("Использование CPU вернулось в норму"), message.text)
    assert(message.text.contains("Текущее значение: 43%"), message.text)
    assert(message.text.contains("Длительность: 8 мин 24 сек"), message.text)
    assert(message.text.contains("Статус: RESOLVED"), message.text)
    assertEquals(message.subject, "[InfraDesk] Инцидент закрыт: Использование CPU — production-01")
  }

  test("a no-data event describes the gap rather than a threshold breach") {
    val opened = NotificationMessage.of(event(NotificationEventType.IncidentOpened,
      IncidentReason.NoData, Some(context(currentValue = None))))
    val resolved = NotificationMessage.of(event(NotificationEventType.IncidentResolved,
      IncidentReason.NoData, Some(context(currentValue = None, durationSeconds = Some(65)))))

    assert(opened.text.contains("нет данных от ресурса"), opened.text)
    assert(resolved.text.contains("Данные от ресурса восстановлены"), resolved.text)
    assert(resolved.text.contains("Длительность: 1 мин 5 сек"), resolved.text)
  }

  // C. Missing optional context ----------------------------------------------------------------

  test("a message with no environment, project or measurement neither crashes nor prints null") {
    val message = NotificationMessage.of(event(NotificationEventType.IncidentOpened,
      IncidentReason.ThresholdViolation,
      Some(context(resourceTypeName = "", environmentName = None, projectName = None,
        currentValue = None))))

    assert(message.text.contains("Сервер: production-01"), message.text)
    // Nothing optional is rendered as an empty or null line.
    assert(!message.text.contains("Окружение:"), message.text)
    assert(!message.text.contains("Проект:"), message.text)
    assert(!message.text.contains("Тип:"), message.text)
    assert(!message.text.contains("Текущее значение:"), message.text)
    assert(!message.text.toLowerCase.contains("null"), message.text)
    // Still shows the threshold it was compared against.
    assert(message.text.contains("Порог: > 80%"), message.text)
  }

  test("dangerous characters in a name are kept verbatim in the rendered text") {
    val message = NotificationMessage.of(event(NotificationEventType.IncidentOpened,
      IncidentReason.ThresholdViolation, Some(context(serverName = "prod <01> & backend_test"))))

    assert(message.text.contains("Сервер: prod <01> & backend_test"), message.text)
    assert(message.subject.contains("prod <01> & backend_test"), message.subject)
  }

  private def context(
    serverName: String = "production-01",
    resourceTypeName: String = "Node",
    environmentName: Option[String] = Some("production"),
    projectName: Option[String] = Some("Payments"),
    operator: MonitorOperator = MonitorOperator.GreaterThan,
    threshold: BigDecimal = BigDecimal(80),
    currentValue: Option[BigDecimal] = Some(BigDecimal(92)),
    durationSeconds: Option[Long] = None
  ): NotificationContext =
    NotificationContext(serverName, resourceTypeName, environmentName, projectName,
      MetricCode.CpuUsagePercent, operator, threshold, currentValue, durationSeconds)

  private def event(
    eventType: NotificationEventType,
    reason: IncidentReason,
    context: Option[NotificationContext] = None
  ): NotificationEvent =
    NotificationEvent(
      eventId = EventId,
      eventType = eventType,
      occurredAt = Instant.parse("2026-09-25T10:00:00Z"),
      organizationId = UUID.fromString("20000000-0000-0000-0000-000000000001"),
      resourceId = ResourceId,
      monitorRuleId = RuleId,
      incidentId = IncidentId,
      reason = reason,
      context = context
    )

  private val EventId = UUID.fromString("c0000000-0000-0000-0000-000000000001")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val IncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001")
}
