package ru.bitec.app.ops
package application.monitor

import application.port.{
  IdGenerator,
  IncidentRepository,
  MonitorRuleRepository,
  MonitorRuleStateRepository,
  ResourceRepository,
  TimeProvider
}
import application.audit.AuditRecorder
import application.auth.ActorContext
import application.history.{HistoryEntry, HistoryRecorder}
import application.notification.RecordNotificationDeliveries
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.history.HistoryEventType
import domain.incident.IncidentStatus
import domain.metric.MetricCode
import domain.monitor.{InvalidMonitorRule, MonitorOperator, MonitorRule, MonitorRuleStatus, MonitorRuleValidation}

import java.time.Instant
import java.util.UUID

/** A configured rule together with the status its last evaluation left behind, if any. */
final case class MonitorRuleView(rule: MonitorRule, status: Option[MonitorRuleStatus])

/** Everything a caller may set on a monitor rule, validated as one business object. */
final case class MonitorRuleCommand(
  metricCode: MetricCode,
  operator: MonitorOperator,
  threshold: BigDecimal,
  forSeconds: Long,
  noDataSeconds: Long,
  enabled: Boolean
) {
  def validated[Tx[_]: MonadThrow]: Tx[MonitorRuleCommand] =
    MonitorRuleValidation
      .validate(metricCode, threshold, forSeconds, noDataSeconds)
      .as(this)
      .liftTo[Tx]
}

final case class ListMonitorRules[Tx[_]: MonadThrow](
  resourceRepository: ResourceRepository[Tx],
  monitorRuleRepository: MonitorRuleRepository[Tx],
  monitorRuleStateRepository: MonitorRuleStateRepository[Tx]
) {
  def execute(
    organizationId: UUID,
    resourceId: UUID
  ): Tx[Option[List[MonitorRuleView]]] =
    resourceRepository.findById(organizationId, resourceId).flatMap {
      case None =>
        none[List[MonitorRuleView]].pure[Tx]
      case Some(_) =>
        for {
          rules <- monitorRuleRepository.findByResource(organizationId, resourceId)
          states <- monitorRuleStateRepository.findByResource(organizationId, resourceId)
          statusByRule = states.map(state => state.monitorRuleId -> state.status).toMap
        } yield Some(rules.map(rule => MonitorRuleView(rule, statusByRule.get(rule.id))))
    }
}

final case class CreateMonitorRule[Tx[_]: MonadThrow](
  resourceRepository: ResourceRepository[Tx],
  monitorRuleRepository: MonitorRuleRepository[Tx],
  idGenerator: IdGenerator[Tx],
  timeProvider: TimeProvider[Tx],
  auditRecorder: AuditRecorder[Tx]
) {
  def execute(
    actor: ActorContext,
    resourceId: UUID,
    command: MonitorRuleCommand
  ): Tx[Option[MonitorRule]] = {
    val organizationId = actor.organizationId
    command.validated[Tx].flatMap { valid =>
      resourceRepository.findById(organizationId, resourceId).flatMap {
        case None =>
          none[MonitorRule].pure[Tx]
        // The resource exists but monitoring does not apply to its type, so the rule would be
        // stored and never evaluated. The backend rejects it instead of trusting the client.
        case Some(resource) if !MonitoredResourceTypes.supports(resource.resourceTypeCode) =>
          InvalidMonitorRule(
            s"Monitoring is not supported for resource type '${resource.resourceTypeCode}'"
          ).raiseError[Tx, Option[MonitorRule]]
        case Some(_) =>
          for {
            id <- idGenerator.nextId
            now <- timeProvider.now
            rule = MonitorRule(
              id,
              organizationId,
              resourceId,
              valid.metricCode,
              valid.operator,
              valid.threshold,
              valid.forSeconds,
              valid.noDataSeconds,
              valid.enabled,
              now,
              now
            )
            _ <- monitorRuleRepository.save(rule)
            _ <- auditRecorder.record(actor, AuditAction.MonitorRuleCreated,
              AuditTargetType.MonitorRule, Some(rule.id))
          } yield Some(rule)
      }
    }
  }
}

/** Updates a rule and, in the same transaction, drops the evaluation state the change
  * invalidates: a disabled or reconfigured rule must not leave a FIRING state or an OPEN
  * incident behind, because the evaluator only reads enabled rules and would never close them.
  */
final case class UpdateMonitorRule[Tx[_]: MonadThrow](
  monitorRuleRepository: MonitorRuleRepository[Tx],
  monitorRuleStateRepository: MonitorRuleStateRepository[Tx],
  incidentRepository: IncidentRepository[Tx],
  notificationRecorder: RecordNotificationDeliveries[Tx],
  auditRecorder: AuditRecorder[Tx],
  historyRecorder: HistoryRecorder[Tx],
  timeProvider: TimeProvider[Tx]
) {
  def execute(
    actor: ActorContext,
    monitorRuleId: UUID,
    command: MonitorRuleCommand
  ): Tx[Option[MonitorRule]] = {
    val organizationId = actor.organizationId
    command.validated[Tx].flatMap { valid =>
      monitorRuleRepository.findById(organizationId, monitorRuleId).flatMap {
        case None =>
          none[MonitorRule].pure[Tx]
        case Some(existing) =>
          timeProvider.now.flatMap { now =>
            val updated = existing.copy(
              metricCode = valid.metricCode,
              operator = valid.operator,
              threshold = valid.threshold,
              forSeconds = valid.forSeconds,
              noDataSeconds = valid.noDataSeconds,
              enabled = valid.enabled,
              updatedAt = now
            )

            monitorRuleRepository.save(updated) *>
              resetEvaluationIfInvalidated(existing, updated, now) *>
              // Rule change, incident lifecycle, notification outbox and journal entry all
              // belong to the one transaction this use case runs in.
              auditRecorder.record(actor, AuditAction.MonitorRuleUpdated,
                AuditTargetType.MonitorRule, Some(updated.id)).as(Some(updated))
          }
      }
    }
  }

  private def resetEvaluationIfInvalidated(
    existing: MonitorRule,
    updated: MonitorRule,
    now: Instant
  ): Tx[Unit] =
    if (!MonitorRule.invalidatesEvaluation(existing, updated)) ().pure[Tx]
    else
      for {
        openIncident <- incidentRepository.findOpenByRule(updated.organizationId, updated.id)
        _ <- openIncident.traverse_(incident =>
          incidentRepository.save(incident.copy(
            status = IncidentStatus.Resolved,
            resolvedAt = Some(now),
            updatedAt = now
          ))
        )
        _ <- monitorRuleStateRepository.deleteByRuleId(updated.organizationId, updated.id)
        // Closing an incident here is the same business fact as closing one during an
        // evaluation, so it is reported through the same outbox, in this transaction.
        transitions = openIncident.toList.map(incident =>
          MonitorTransition.Resolved(updated.organizationId, updated.resourceId, updated.id,
            incident.id, incident.reason, now, notificationsSilenced = incident.notificationsSilenced))
        _ <- notificationRecorder.record(transitions)
        // Closing an incident is the same fact however it happened, so it reaches the timeline
        // through this transaction as well.
        _ <- historyRecorder.recordAll(transitions.map(transition =>
          HistoryEntry.system(transition.organizationId, HistoryEventType.IncidentResolved,
            transition.evaluatedAt)
            .copy(resourceId = Some(transition.resourceId), incidentId = Some(transition.incidentId))
        ))
      } yield ()
}
