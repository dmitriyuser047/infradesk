package ru.bitec.app.ops
package application.monitor

import domain.incident.IncidentReason

import java.time.Instant
import java.util.UUID

/** A committed incident change, reported to the caller for logging after the transaction. */
sealed trait MonitorTransition {
  def eventName: String
  def organizationId: UUID
  def resourceId: UUID
  def monitorRuleId: UUID
  def incidentId: UUID
  def reason: IncidentReason
  def evaluatedAt: Instant

  final def logMessage: String =
    s"$eventName organizationId=$organizationId resourceId=$resourceId monitorRuleId=$monitorRuleId " +
      s"incidentId=$incidentId reason=${reason.code} evaluatedAt=$evaluatedAt"
}

object MonitorTransition {
  final case class Opened(
    organizationId: UUID,
    resourceId: UUID,
    monitorRuleId: UUID,
    incidentId: UUID,
    reason: IncidentReason,
    evaluatedAt: Instant
  ) extends MonitorTransition {
    val eventName = "incident.opened"
  }

  final case class Resolved(
    organizationId: UUID,
    resourceId: UUID,
    monitorRuleId: UUID,
    incidentId: UUID,
    reason: IncidentReason,
    evaluatedAt: Instant
  ) extends MonitorTransition {
    val eventName = "incident.resolved"
  }
}
