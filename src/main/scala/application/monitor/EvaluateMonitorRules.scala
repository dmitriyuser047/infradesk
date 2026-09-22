package ru.bitec.app.ops
package application.monitor

import application.port.{IdGenerator, IncidentRepository, MetricObservationRepository, MonitorRuleRepository, MonitorRuleStateRepository}
import domain.incident.{Incident, IncidentStatus}
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
                                                     metricObservationRepository: MetricObservationRepository[Tx],
                                                     incidentRepository: IncidentRepository[Tx],
                                                     idGenerator: IdGenerator[Tx]
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
      .findLatestAtOrAfter(
        resource.organizationId,
        resource.id,
        rule.metricCode,
        resource.updatedAt
      )
      .flatMap {
        case Some(observation) =>
          monitorRuleStateRepository
            .findByRuleId(rule.organizationId, rule.id)
            .flatMap { currentState =>
              nextState(rule, currentState, observation, evaluatedAt)
                .liftTo[Tx]
                .flatMap { next =>
                  processIncidentTransition(rule, currentState, next, evaluatedAt) *>
                    monitorRuleStateRepository.save(next)
                }
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

  private def processIncidentTransition(
                                        rule: MonitorRule,
                                        currentState: Option[MonitorRuleState],
                                        nextState: MonitorRuleState,
                                        evaluatedAt: Instant
                                      ): Tx[Unit] =
    (currentState.map(_.status), nextState.status) match {
      case (Some(MonitorRuleStatus.Firing), MonitorRuleStatus.Firing) =>
        requireOpenIncident(rule)

      case (Some(MonitorRuleStatus.Firing), MonitorRuleStatus.Ok) =>
        resolveOpenIncident(rule, evaluatedAt)

      case (_, MonitorRuleStatus.Firing) =>
        createOpenIncident(rule, nextState, evaluatedAt)

      case (_, MonitorRuleStatus.Ok) =>
        requireNoOpenIncident(rule)

      case (_, MonitorRuleStatus.Pending) =>
        requireNoOpenIncident(rule)
    }

  private def createOpenIncident(
                                  rule: MonitorRule,
                                  nextState: MonitorRuleState,
                                  evaluatedAt: Instant
                                ): Tx[Unit] =
    incidentRepository
      .findOpenByRule(rule.organizationId, rule.id)
      .flatMap {
        case Some(existing) =>
          new IllegalStateException(
            s"Monitor rule ${rule.id} transitioned to FIRING while incident ${existing.id} is already OPEN"
          ).raiseError[Tx, Unit]

        case None =>
          nextState.pendingSince match {
            case Some(startedAt) =>
              idGenerator.nextId.flatMap { incidentId =>
                incidentRepository.save(
                  Incident(
                    incidentId,
                    rule.organizationId,
                    rule.id,
                    rule.resourceId,
                    IncidentStatus.Open,
                    startedAt,
                    evaluatedAt,
                    None,
                    evaluatedAt,
                    evaluatedAt
                  )
                )
              }

            case None =>
              new IllegalStateException(
                s"Monitor rule ${rule.id} transitioned to FIRING without pendingSince"
              ).raiseError[Tx, Unit]
          }
      }

  private def requireOpenIncident(rule: MonitorRule): Tx[Unit] =
    incidentRepository
      .findOpenByRule(rule.organizationId, rule.id)
      .flatMap {
        case Some(_) => ().pure[Tx]
        case None =>
          new IllegalStateException(
            s"Monitor rule ${rule.id} is FIRING without an OPEN incident"
          ).raiseError[Tx, Unit]
      }

  private def requireNoOpenIncident(rule: MonitorRule): Tx[Unit] =
    incidentRepository
      .findOpenByRule(rule.organizationId, rule.id)
      .flatMap {
        case None => ().pure[Tx]
        case Some(incident) =>
          new IllegalStateException(
            s"Monitor rule ${rule.id} is not FIRING while incident ${incident.id} is still OPEN"
          ).raiseError[Tx, Unit]
      }

  private def resolveOpenIncident(rule: MonitorRule, evaluatedAt: Instant): Tx[Unit] =
    incidentRepository
      .findOpenByRule(rule.organizationId, rule.id)
      .flatMap {
        case Some(incident) =>
          incidentRepository.save(
            incident.copy(
              status = IncidentStatus.Resolved,
              resolvedAt = Some(evaluatedAt),
              updatedAt = evaluatedAt
            )
          )

        case None =>
          new IllegalStateException(
            s"Monitor rule ${rule.id} recovered from FIRING without an OPEN incident"
          ).raiseError[Tx, Unit]
      }
}
