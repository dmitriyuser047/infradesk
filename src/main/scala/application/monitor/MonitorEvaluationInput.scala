package ru.bitec.app.ops
package application.monitor

import domain.incident.Incident
import domain.metric.MetricObservation
import domain.monitor.{MonitorRule, MonitorRuleState}

final case class MonitorEvaluationInput(
  rule: MonitorRule,
  observation: Option[MetricObservation],
  state: Option[MonitorRuleState],
  openIncident: Option[Incident]
)
