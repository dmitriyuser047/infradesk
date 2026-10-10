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

  // Every collected metric reads as a phrase; the code is the last resort for a future one.
  private val metricLabels: Map[MetricCode, String] = Map(
    MetricCode.CpuUsagePercent -> "Использование CPU",
    MetricCode.MemoryUsagePercent -> "Использование памяти",
    MetricCode.DiskUsagePercent -> "Заполненность диска",
    MetricCode.DiskFreeBytes -> "Свободное место на диске",
    MetricCode.InodeUsagePercent -> "Использование inode",
    MetricCode.SwapUsagePercent -> "Использование swap",
    MetricCode.SwapUsedBytes -> "Занятый swap",
    MetricCode.LoadAverage1 -> "Средняя нагрузка за 1 мин",
    MetricCode.LoadAverage5 -> "Средняя нагрузка за 5 мин",
    MetricCode.LoadAverage15 -> "Средняя нагрузка за 15 мин",
    MetricCode.LoadPerCore -> "Нагрузка на ядро CPU",
    MetricCode.CpuIowaitPercent -> "Ожидание ввода-вывода CPU",
    MetricCode.DiskReadBytesPerSecond -> "Чтение с диска",
    MetricCode.DiskWriteBytesPerSecond -> "Запись на диск",
    MetricCode.DiskLatencyMilliseconds -> "Задержка дисковых операций",
    MetricCode.DiskBusyPercent -> "Загруженность диска",
    MetricCode.NetworkReceiveBytesPerSecond -> "Входящий трафик",
    MetricCode.NetworkTransmitBytesPerSecond -> "Исходящий трафик",
    MetricCode.NetworkErrorsPerSecond -> "Ошибки сетевых интерфейсов",
    MetricCode.NetworkDropsPerSecond -> "Потерянные пакеты",
    MetricCode.ContainerRestartCount -> "Перезапуски контейнера",
    MetricCode.ContainerHealthy -> "Healthcheck контейнера",
    MetricCode.TlsDaysRemaining -> "Дней до истечения TLS-сертификата",
    MetricCode.ServiceAvailable -> "Доступность веб-сервиса",
    MetricCode.ServiceResponseMilliseconds -> "Время ответа веб-сервиса",
    MetricCode.DiskTemperatureCelsius -> "Температура накопителя",
    MetricCode.DiskWearPercent -> "Износ NVMe",
    MetricCode.DiskMediaErrors -> "Ошибки носителя",
    MetricCode.DiskHealthy -> "Состояние SMART"
  )

  private def metricLabel(metric: MetricCode): String = metricLabels.getOrElse(metric, metric.code)

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

  private def formatValue(value: BigDecimal, metric: MetricCode): String = {
    def plain(number: BigDecimal) =
      number.setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString
    metric.unit match {
      // Byte counts read in binary multiples, as the interface shows them.
      case unit @ ("B" | "B/s") =>
        val scales = List(BigDecimal(1024).pow(3) -> "GiB", BigDecimal(1024).pow(2) -> "MiB", BigDecimal(1024) -> "KiB")
        scales.find { case (scale, _) => value.abs >= scale }
          .fold(plain(value) + " B" + unit.drop(1)) { case (scale, label) => plain(value / scale) + " " + label + unit.drop(1) }
      case "" => plain(value)
      case unit => plain(value) + unit
    }
  }

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
