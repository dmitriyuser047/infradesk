package ru.bitec.app.ops
package domain.notification

import domain.metric.MetricCode
import domain.monitor.MonitorOperator

/** The human-facing snapshot of an incident event, taken when the event is recorded.
  *
  * A rendered Telegram message, an email and a webhook's enrichment all come from this one value,
  * so no two channels tell a different story, and none of them makes the dispatcher read a
  * resource, a rule or an organization on the way to sending: the context that describes the event
  * is built once, in the same projection that decided the incident, and travels with the delivery.
  *
  * It is a snapshot on purpose. The text a person reads must not change between the first send and
  * a retry because a resource was renamed in between: what was true when the incident changed is
  * what every attempt reports. Only fields that really exist live here — a monitor rule has no
  * name of its own, so the problem is described from its metric, operator and threshold rather than
  * from an invented title.
  *
  * `occurredAt` and `reason` are kept on the event, not here; this is the descriptive context a
  * reader needs beyond the identifiers.
  */
final case class NotificationContext(
  serverName: String,
  resourceTypeName: String,
  environmentName: Option[String],
  projectName: Option[String],
  metricCode: MetricCode,
  operator: MonitorOperator,
  threshold: BigDecimal,
  currentValue: Option[BigDecimal],
  /** Present only for a resolved event: how long the incident was open, in seconds. */
  durationSeconds: Option[Long]
)
