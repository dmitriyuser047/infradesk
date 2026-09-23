package ru.bitec.app.ops
package application.monitor

import application.port.{IdGenerator, MonitorRuleRepository, MonitorRuleStateRepository, ResourceRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.metric.MetricCode
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleStatus, MonitorRuleValidation}

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
  timeProvider: TimeProvider[Tx]
) {
  def execute(
    organizationId: UUID,
    resourceId: UUID,
    command: MonitorRuleCommand
  ): Tx[Option[MonitorRule]] =
    command.validated[Tx].flatMap { valid =>
      resourceRepository.findById(organizationId, resourceId).flatMap {
        case None =>
          none[MonitorRule].pure[Tx]
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
          } yield Some(rule)
      }
    }
}

final case class UpdateMonitorRule[Tx[_]: MonadThrow](
  monitorRuleRepository: MonitorRuleRepository[Tx],
  timeProvider: TimeProvider[Tx]
) {
  def execute(
    organizationId: UUID,
    monitorRuleId: UUID,
    command: MonitorRuleCommand
  ): Tx[Option[MonitorRule]] =
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
            monitorRuleRepository.save(updated).as(Some(updated))
          }
      }
    }
}
