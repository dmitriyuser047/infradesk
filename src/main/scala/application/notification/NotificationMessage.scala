package ru.bitec.app.ops
package application.notification

import domain.incident.IncidentReason
import domain.metric.MetricCode
import domain.monitor.MonitorOperator
import domain.notification.{NotificationContext, NotificationEventType}

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

/** What a person reads when a notification arrives.
  *
  * Built from the event alone, by a pure function: a channel that wants a prettier message does
  * not get to make the worker load a resource, a rule or an organization on the way to sending.
  * What the event carries — its identifiers and its descriptive [[NotificationContext]] snapshot —
  * is what the message says, and it reads the same on every retry.
  *
  * Webhooks keep their structured payload; this is for the transports that reach a human. A rule
  * has no name in the model, so the problem is described from its metric, operator and threshold
  * rather than from an invented title. There is no organization time zone either, so times are
  * shown in UTC and marked as such: a correct UTC time beats a plausible-looking wrong local one.
  */
final case class NotificationMessage(subject: String, text: String)

object NotificationMessage {

  val empty: NotificationMessage = NotificationMessage("", "")

  /** The message a human-facing transport sends. Renders from the descriptive snapshot when the
    * event carries one, and otherwise falls back to the identifier-only form — which is what the
    * synthetic test-channel event, having no incident, deliberately produces.
    */
  def of(event: NotificationEvent): NotificationMessage =
    event.context match {
      case Some(context) => render(event.eventType, event.reason, event.occurredAt, context)
      case None => legacy(event)
    }

  private def render(
    eventType: NotificationEventType,
    reason: IncidentReason,
    at: Instant,
    context: NotificationContext
  ): NotificationMessage = {
    val opened = eventType == NotificationEventType.IncidentOpened
    val statusWord = if (opened) "открыт" else "закрыт"
    val metric = metricLabel(context.metricCode)
    val value = context.currentValue.map(v => s"Текущее значение: ${formatValue(v, context.metricCode)}")

    val subject = s"[InfraDesk] Инцидент $statusWord: $metric — ${context.serverName}"

    val lines = List(
      Some(s"InfraDesk — инцидент $statusWord"),
      Some(""),
      Some(s"Сервер: ${context.serverName}"),
      Option(context.resourceTypeName).filter(_.nonEmpty).map(t => s"Тип: $t"),
      context.environmentName.map(e => s"Окружение: $e"),
      context.projectName.map(p => s"Проект: $p"),
      Some(""),
      Some(problemLine(opened, reason, context)),
      value,
      // The threshold matters only for a breach; a data gap was not measured against it.
      Option.when(reason == IncidentReason.ThresholdViolation)(conditionLine(context)),
      context.durationSeconds.map(d => s"Длительность: ${formatDuration(d)}"),
      Some(""),
      Some(s"${if (opened) "Время" else "Закрыт"}: ${formatTime(at)}"),
      Some(s"Статус: ${if (opened) "OPEN" else "RESOLVED"}")
    ).flatten

    NotificationMessage(subject, lines.mkString("\n"))
  }

  /** A short problem sentence derived only from what the model has. */
  private def problemLine(opened: Boolean, reason: IncidentReason, context: NotificationContext): String = {
    val metric = metricLabel(context.metricCode)
    (opened, reason) match {
      case (true, IncidentReason.ThresholdViolation) => s"Проблема: $metric ${direction(context.operator)}"
      case (true, IncidentReason.NoData) => "Проблема: нет данных от ресурса"
      case (false, IncidentReason.ThresholdViolation) => s"$metric вернулось в норму"
      case (false, IncidentReason.NoData) => "Данные от ресурса восстановлены"
    }
  }

  private def conditionLine(context: NotificationContext): String =
    s"Порог: ${operatorLabel(context.operator)} ${formatValue(context.threshold, context.metricCode)}"

  private def metricLabel(metric: MetricCode): String = metric match {
    case MetricCode.CpuUsagePercent => "Использование CPU"
    case MetricCode.MemoryUsagePercent => "Использование памяти"
  }

  /** Human phrasing of which side of the threshold the value fell on. */
  private def direction(operator: MonitorOperator): String = operator match {
    case MonitorOperator.GreaterThan | MonitorOperator.GreaterThanOrEqual => "выше допустимого"
    case MonitorOperator.LessThan | MonitorOperator.LessThanOrEqual => "ниже допустимого"
  }

  private def operatorLabel(operator: MonitorOperator): String = operator match {
    case MonitorOperator.GreaterThan => ">"
    case MonitorOperator.GreaterThanOrEqual => ">="
    case MonitorOperator.LessThan => "<"
    case MonitorOperator.LessThanOrEqual => "<="
  }

  private def formatValue(value: BigDecimal, metric: MetricCode): String =
    value.bigDecimal.stripTrailingZeros.toPlainString + (if (metric.isPercentage) "%" else "")

  private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC)

  private def formatTime(value: Instant): String = timeFormatter.format(value)

  private def formatDuration(seconds: Long): String = {
    val safe = seconds.max(0)
    s"${safe / 60} мин ${safe % 60} сек"
  }

  // -- Legacy identifier-only message, kept for events without a context snapshot. -------------

  private def legacy(event: NotificationEvent): NotificationMessage = {
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
