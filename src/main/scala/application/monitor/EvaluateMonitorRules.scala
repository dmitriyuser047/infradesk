package ru.bitec.app.ops
package application.monitor

import application.port.{MetricObservationRepository, MonitorRuleRepository, MonitorRuleStateRepository}
import domain.metric.MetricObservation
import domain.monitor.{MonitorRule, MonitorRuleState, MonitorRuleStatus}
import domain.resource.Resource
import domain.resource.node.NodeDefinition

import cats.MonadThrow
import cats.syntax.all._

import java.time.{Duration, Instant}

final class EvaluateMonitorRules[Tx[_]: MonadThrow](
                                                     monitorRuleRepository: MonitorRuleRepository[Tx],
                                                     monitorRuleStateRepository: MonitorRuleStateRepository[Tx],
                                                     metricObservationRepository: MetricObservationRepository[Tx]
                                                   ) extends MonitorRuleEvaluator[Tx] {

  override def execute(resources: List[Resource], evaluatedAt: Instant): Tx[Unit] =
    resources
      .filter(resource => resource.resourceTypeCode == NodeDefinition.code)
      .traverse_(evaluateResource(_, evaluatedAt))

  private def evaluateResource(resource: Resource, evaluatedAt: Instant): Tx[Unit] =
    monitorRuleRepository
      .findEnabledByResource(resource.organizationId, resource.id)
      .flatMap(_.traverse_(evaluateRule(resource, _, evaluatedAt)))

  private def evaluateRule(
                            resource: Resource,
                            rule: MonitorRule,
                            evaluatedAt: Instant
                          ): Tx[Unit] =
    metricObservationRepository
      .findLatest(resource.organizationId, resource.id, rule.metricCode)
      .flatMap {
        case Some(observation) =>
          monitorRuleStateRepository
            .findByRuleId(rule.organizationId, rule.id)
            .flatMap { currentState =>
              nextState(rule, currentState, observation, evaluatedAt)
                .liftTo[Tx]
                .flatMap(monitorRuleStateRepository.save)
            }

        case None =>
          ().pure[Tx]
      }

  private def nextState(
                         rule: MonitorRule,
                         currentState: Option[MonitorRuleState],
                         observation: MetricObservation,
                         evaluatedAt: Instant
                       ): Either[IllegalArgumentException, MonitorRuleState] =
    if (!rule.operator.matches(observation.value, rule.threshold)) {
      Right(
        MonitorRuleState(
          rule.organizationId,
          rule.id,
          MonitorRuleStatus.Ok,
          None,
          evaluatedAt
        )
      )
    } else {
      currentState match {
        case None | Some(MonitorRuleState(_, _, MonitorRuleStatus.Ok, _, _)) =>
          initialViolationState(rule, observation, evaluatedAt)

        case Some(state @ MonitorRuleState(_, _, MonitorRuleStatus.Pending, Some(pendingSince), _)) =>
          val elapsedSeconds = Duration.between(pendingSince, observation.observedAt).getSeconds
          val status =
            if (elapsedSeconds >= rule.forSeconds) MonitorRuleStatus.Firing
            else MonitorRuleStatus.Pending

          Right(state.copy(status = status, updatedAt = evaluatedAt))

        case Some(state @ MonitorRuleState(_, _, MonitorRuleStatus.Firing, Some(_), _)) =>
          Right(state.copy(updatedAt = evaluatedAt))

        case Some(state) =>
          Left(new IllegalArgumentException(
            s"Monitor rule state ${state.monitorRuleId} has invalid pendingSince for status ${state.status.code}"
          ))
      }
    }

  private def initialViolationState(
                                    rule: MonitorRule,
                                    observation: MetricObservation,
                                    evaluatedAt: Instant
                                  ): Either[IllegalArgumentException, MonitorRuleState] = {
    val status =
      if (rule.forSeconds == 0) MonitorRuleStatus.Firing
      else MonitorRuleStatus.Pending

    Right(
      MonitorRuleState(
        rule.organizationId,
        rule.id,
        status,
        Some(observation.observedAt),
        evaluatedAt
      )
    )
  }
}
