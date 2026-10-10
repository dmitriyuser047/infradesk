package ru.bitec.app.ops
package application.monitor

import domain.incident.Incident
import domain.metric.MetricObservation
import domain.monitor.{MonitorRule, MonitorRuleState}

/** One monitor rule as the evaluation reads it, together with everything the same projection can
  * cheaply carry alongside: the latest observation, the current state, the open incident, and the
  * descriptive names a human notification needs.
  *
  * The names come from the resource join the query already makes, so they cost no extra round
  * trip. Monitoring targets nodes, so `serverName` is the monitored resource's own name; the
  * environment and project are present only when the resource is linked to them.
  */
final case class MonitorEvaluationInput(
  rule: MonitorRule,
  observation: Option[MetricObservation],
  state: Option[MonitorRuleState],
  openIncident: Option[Incident],
  serverName: String = "",
  resourceTypeName: String = "",
  environmentName: Option[String] = None,
  projectName: Option[String] = None,
  /** A maintenance window covers the resource now: an incident opened now is silenced for good. */
  inMaintenance: Boolean = false
)
