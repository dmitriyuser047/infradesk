package ru.bitec.app.ops
package application.monitor

import application.port.{IdGenerator, IncidentRepository, MonitorEvaluationQuery, MonitorRuleStateRepository}
import cats.MonadThrow
import cats.syntax.all._
import domain.incident.{Incident, IncidentStatus}
import domain.metric.MetricObservation
import domain.monitor.{MonitorRule, MonitorRuleState, MonitorRuleStatus}
import domain.resource.Resource
import domain.resource.node.NodeDefinition

import java.time.{Duration, Instant}

final class EvaluateMonitorRules[Tx[_]: MonadThrow](
  evaluationQuery: MonitorEvaluationQuery[Tx],
  monitorRuleStateRepository: MonitorRuleStateRepository[Tx],
  incidentRepository: IncidentRepository[Tx],
  idGenerator: IdGenerator[Tx]
) extends MonitorRuleEvaluator[Tx] {

  override def execute(resources: List[Resource], evaluatedAt: Instant): Tx[List[MonitorTransition]] = {
    val resourcesByOrganization = resources
      .filter(_.resourceTypeCode == NodeDefinition.code)
      .groupBy(_.organizationId)
      .toList
      .sortBy(_._1.toString)

    for {
      inputs <- resourcesByOrganization.traverse { case (organizationId, organizationResources) =>
        evaluationQuery.findEnabledForResources(organizationId, organizationResources.map(_.id).distinct)
      }.map(_.flatten)
      plans <- inputs.flatMap(input => input.observation.map(input -> _))
        .traverse { case (input, observation) =>
          calculate(input, observation, evaluatedAt).liftTo[Tx]
        }
      decisions <- plans.traverse(materialize)
      states = decisions.map(_.state)
      incidents = decisions.flatMap(_.incident)
      _ <- if (states.isEmpty) ().pure[Tx] else monitorRuleStateRepository.saveAll(states)
      _ <- if (incidents.isEmpty) ().pure[Tx] else incidentRepository.saveAll(incidents)
    } yield decisions.flatMap(_.transition)
  }

  private def calculate(
    input: MonitorEvaluationInput,
    observation: MetricObservation,
    evaluatedAt: Instant
  ): Either[Throwable, EvaluationPlan] =
    nextState(input.rule, input.state, observation, evaluatedAt).flatMap { next =>
      incidentPlan(input, next, evaluatedAt).map(EvaluationPlan(next, _))
    }

  private def nextState(
    rule: MonitorRule,
    currentState: Option[MonitorRuleState],
    observation: MetricObservation,
    evaluatedAt: Instant
  ): Either[IllegalArgumentException, MonitorRuleState] =
    if (!rule.operator.matches(observation.value, rule.threshold)) {
      Right(MonitorRuleState(rule.organizationId, rule.id, MonitorRuleStatus.Ok, None, evaluatedAt))
    } else {
      currentState match {
        case None | Some(MonitorRuleState(_, _, MonitorRuleStatus.Ok, _, _)) =>
          Right(MonitorRuleState(
            rule.organizationId,
            rule.id,
            if (rule.forSeconds == 0) MonitorRuleStatus.Firing else MonitorRuleStatus.Pending,
            Some(observation.observedAt),
            evaluatedAt
          ))
        case Some(state @ MonitorRuleState(_, _, MonitorRuleStatus.Pending, Some(pendingSince), _)) =>
          val status =
            if (Duration.between(pendingSince, observation.observedAt).getSeconds >= rule.forSeconds)
              MonitorRuleStatus.Firing
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

  private def incidentPlan(
    input: MonitorEvaluationInput,
    nextState: MonitorRuleState,
    evaluatedAt: Instant
  ): Either[Throwable, Option[IncidentPlan]] =
    (input.state.map(_.status), nextState.status, input.openIncident) match {
      case (Some(MonitorRuleStatus.Firing), MonitorRuleStatus.Firing, Some(_)) => Right(None)
      case (Some(MonitorRuleStatus.Firing), MonitorRuleStatus.Firing, None) =>
        Left(new IllegalStateException(s"Monitor rule ${input.rule.id} is FIRING without an OPEN incident"))

      case (Some(MonitorRuleStatus.Firing), MonitorRuleStatus.Ok, Some(incident)) =>
        Right(Some(ResolveIncident(input.rule, incident, evaluatedAt)))
      case (Some(MonitorRuleStatus.Firing), MonitorRuleStatus.Ok, None) =>
        Left(new IllegalStateException(
          s"Monitor rule ${input.rule.id} recovered from FIRING without an OPEN incident"
        ))

      case (_, MonitorRuleStatus.Firing, Some(existing)) =>
        Left(new IllegalStateException(
          s"Monitor rule ${input.rule.id} transitioned to FIRING while incident ${existing.id} is already OPEN"
        ))
      case (_, MonitorRuleStatus.Firing, None) =>
        nextState.pendingSince match {
          case Some(startedAt) => Right(Some(OpenIncident(input.rule, startedAt, evaluatedAt)))
          case None => Left(new IllegalStateException(
            s"Monitor rule ${input.rule.id} transitioned to FIRING without pendingSince"
          ))
        }

      case (_, MonitorRuleStatus.Ok | MonitorRuleStatus.Pending, None) => Right(None)
      case (_, _, Some(incident)) => Left(new IllegalStateException(
        s"Monitor rule ${input.rule.id} is not FIRING while incident ${incident.id} is still OPEN"
      ))
    }

  private def materialize(plan: EvaluationPlan): Tx[EvaluationDecision] =
    plan.incident match {
      case None => EvaluationDecision(plan.state, None, None).pure[Tx]
      case Some(OpenIncident(rule, startedAt, evaluatedAt)) =>
        idGenerator.nextId.map { incidentId =>
          val incident = Incident(incidentId, rule.organizationId, rule.id, rule.resourceId,
            IncidentStatus.Open, startedAt, evaluatedAt, None, evaluatedAt, evaluatedAt)
          EvaluationDecision(plan.state, Some(incident), Some(
            MonitorTransition.Opened(rule.organizationId, rule.resourceId, rule.id, incidentId, evaluatedAt)
          ))
        }
      case Some(ResolveIncident(rule, incident, evaluatedAt)) =>
        EvaluationDecision(
          plan.state,
          Some(incident.copy(status = IncidentStatus.Resolved,
            resolvedAt = Some(evaluatedAt), updatedAt = evaluatedAt)),
          Some(MonitorTransition.Resolved(rule.organizationId, rule.resourceId,
            rule.id, incident.id, evaluatedAt))
        ).pure[Tx]
    }

  private final case class EvaluationPlan(state: MonitorRuleState, incident: Option[IncidentPlan])
  private final case class EvaluationDecision(
    state: MonitorRuleState,
    incident: Option[Incident],
    transition: Option[MonitorTransition]
  )
  private sealed trait IncidentPlan
  private final case class OpenIncident(rule: MonitorRule, startedAt: Instant, evaluatedAt: Instant)
    extends IncidentPlan
  private final case class ResolveIncident(rule: MonitorRule, incident: Incident, evaluatedAt: Instant)
    extends IncidentPlan
}
