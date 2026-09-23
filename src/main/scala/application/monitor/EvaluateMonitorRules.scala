package ru.bitec.app.ops
package application.monitor

import application.port.{IdGenerator, IncidentRepository, MonitorEvaluationQuery, MonitorRuleStateRepository}
import cats.MonadThrow
import cats.syntax.all._
import domain.incident.{Incident, IncidentStatus}
import domain.monitor.MonitorRuleState

import java.time.Instant
import java.util.UUID

/** Evaluates every enabled monitor rule of a connection in one projection read, decides the new
  * rule states and incident changes in memory, and writes them back in batches.
  */
final class EvaluateMonitorRules[Tx[_]: MonadThrow](
  evaluationQuery: MonitorEvaluationQuery[Tx],
  monitorRuleStateRepository: MonitorRuleStateRepository[Tx],
  incidentRepository: IncidentRepository[Tx],
  idGenerator: IdGenerator[Tx]
) extends MonitorRuleEvaluator[Tx] {

  override def execute(
    organizationId: UUID,
    connectionId: UUID,
    evaluatedAt: Instant
  ): Tx[List[MonitorTransition]] =
    for {
      inputs <- evaluationQuery.findEnabledForConnection(
        organizationId, connectionId, MonitoredResourceTypes.codes
      )
      plans <- inputs.traverse(input =>
        MonitorRuleStateMachine.calculate(input, evaluatedAt).liftTo[Tx].map(_.map(input -> _))
      ).map(_.flatten)
      decisions <- plans.traverse { case (input, plan) => materialize(input, plan, evaluatedAt) }
      states = decisions.map(_.state)
      resolvedIncidents = decisions.flatMap(_.resolvedIncident)
      openedIncidents = decisions.flatMap(_.openedIncident)
      _ <- saveStates(states)
      // A rule may only have one OPEN incident, so resolutions are written before the incident
      // that replaces them.
      _ <- saveIncidents(resolvedIncidents)
      _ <- saveIncidents(openedIncidents)
    } yield decisions.flatMap(_.transitions)

  private def materialize(
    input: MonitorEvaluationInput,
    plan: EvaluationPlan,
    evaluatedAt: Instant
  ): Tx[EvaluationDecision] = {
    val rule = input.rule
    val resolved = plan.resolvedIncident
    val resolvedTransition = resolved.map(incident => MonitorTransition.Resolved(
      rule.organizationId, rule.resourceId, rule.id, incident.id, incident.reason, evaluatedAt
    ))

    plan.openedIncident match {
      case None =>
        EvaluationDecision(plan.state, resolved, None, resolvedTransition.toList).pure[Tx]

      case Some(opened) =>
        idGenerator.nextId.map { incidentId =>
          val incident = Incident(
            incidentId, rule.organizationId, rule.id, rule.resourceId, IncidentStatus.Open,
            opened.reason, opened.startedAt, evaluatedAt, None, evaluatedAt, evaluatedAt
          )
          EvaluationDecision(
            plan.state,
            resolved,
            Some(incident),
            resolvedTransition.toList :+ MonitorTransition.Opened(
              rule.organizationId, rule.resourceId, rule.id, incidentId, opened.reason, evaluatedAt
            )
          )
        }
    }
  }

  private def saveStates(states: List[MonitorRuleState]): Tx[Unit] =
    if (states.isEmpty) ().pure[Tx] else monitorRuleStateRepository.saveAll(states)

  private def saveIncidents(incidents: List[Incident]): Tx[Unit] =
    if (incidents.isEmpty) ().pure[Tx] else incidentRepository.saveAll(incidents)

  private final case class EvaluationDecision(
    state: MonitorRuleState,
    resolvedIncident: Option[Incident],
    openedIncident: Option[Incident],
    transitions: List[MonitorTransition]
  )
}
