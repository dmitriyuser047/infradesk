package ru.bitec.app.ops
package application.monitor

import application.port.{IdGenerator, MonitorRuleRepository, ResourceRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.metric.MetricCode
import domain.monitor.{MonitorOperator, MonitorRule}

import java.util.UUID

final case class ListMonitorRules[Tx[_]: MonadThrow](
  resourceRepository: ResourceRepository[Tx],
  monitorRuleRepository: MonitorRuleRepository[Tx]
) {
  def execute(
    organizationId: UUID,
    resourceId: UUID
  ): Tx[Option[List[MonitorRule]]] =
    resourceRepository.findById(organizationId, resourceId).flatMap {
      case Some(_) =>
        monitorRuleRepository.findByResource(organizationId, resourceId).map(Some(_))
      case None =>
        none[List[MonitorRule]].pure[Tx]
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
    metricCode: MetricCode,
    operator: MonitorOperator,
    threshold: BigDecimal,
    forSeconds: Long,
    enabled: Boolean
  ): Tx[Option[MonitorRule]] =
    if (forSeconds < 0) {
      new IllegalArgumentException("forSeconds must not be negative")
        .raiseError[Tx, Option[MonitorRule]]
    } else {
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
              metricCode,
              operator,
              threshold,
              forSeconds,
              enabled,
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
    metricCode: MetricCode,
    operator: MonitorOperator,
    threshold: BigDecimal,
    forSeconds: Long,
    enabled: Boolean
  ): Tx[Option[MonitorRule]] =
    if (forSeconds < 0) {
      new IllegalArgumentException("forSeconds must not be negative")
        .raiseError[Tx, Option[MonitorRule]]
    } else {
      monitorRuleRepository.findById(organizationId, monitorRuleId).flatMap {
        case None =>
          none[MonitorRule].pure[Tx]
        case Some(existing) =>
          timeProvider.now.flatMap { now =>
            val updated = existing.copy(
              metricCode = metricCode,
              operator = operator,
              threshold = threshold,
              forSeconds = forSeconds,
              enabled = enabled,
              updatedAt = now
            )
            monitorRuleRepository.save(updated).as(Some(updated))
          }
      }
    }
}
