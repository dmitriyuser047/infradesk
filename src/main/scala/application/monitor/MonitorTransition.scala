package ru.bitec.app.ops
package application.monitor

import domain.incident.IncidentReason
import domain.notification.NotificationContext

import java.time.Instant
import java.util.UUID

/** A committed incident change, reported to the caller for logging after the transaction.
  *
  * `context` is the human-facing snapshot built from the same evaluation projection. It is
  * optional so the identity of a transition — what happened, to which incident — never depends on
  * whether a description could be built.
  */
sealed trait MonitorTransition {
  def eventName: String
  def organizationId: UUID
  def resourceId: UUID
  def monitorRuleId: UUID
  def incidentId: UUID
  def reason: IncidentReason
  def evaluatedAt: Instant
  def context: Option[NotificationContext]
  /** The incident opened during maintenance: the change is recorded, nobody is notified. */
  def notificationsSilenced: Boolean

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
    evaluatedAt: Instant,
    context: Option[NotificationContext] = None,
    notificationsSilenced: Boolean = false
  ) extends MonitorTransition {
    val eventName = "incident.opened"
  }

  final case class Resolved(
    organizationId: UUID,
    resourceId: UUID,
    monitorRuleId: UUID,
    incidentId: UUID,
    reason: IncidentReason,
    evaluatedAt: Instant,
    context: Option[NotificationContext] = None,
    notificationsSilenced: Boolean = false
  ) extends MonitorTransition {
    val eventName = "incident.resolved"
  }
}
