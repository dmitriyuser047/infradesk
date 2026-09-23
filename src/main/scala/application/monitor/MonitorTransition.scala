package ru.bitec.app.ops
package application.monitor

import java.time.Instant
import java.util.UUID

sealed trait MonitorTransition {
  def eventName: String
  def organizationId: UUID
  def resourceId: UUID
  def monitorRuleId: UUID
  def incidentId: UUID
  def evaluatedAt: Instant

  final def logMessage: String =
    s"$eventName organizationId=$organizationId resourceId=$resourceId monitorRuleId=$monitorRuleId incidentId=$incidentId evaluatedAt=$evaluatedAt"
}

object MonitorTransition {
  final case class Opened(
    organizationId: UUID,
    resourceId: UUID,
    monitorRuleId: UUID,
    incidentId: UUID,
    evaluatedAt: Instant
  ) extends MonitorTransition {
    val eventName = "incident.opened"
  }

  final case class Resolved(
    organizationId: UUID,
    resourceId: UUID,
    monitorRuleId: UUID,
    incidentId: UUID,
    evaluatedAt: Instant
  ) extends MonitorTransition {
    val eventName = "incident.resolved"
  }
}
