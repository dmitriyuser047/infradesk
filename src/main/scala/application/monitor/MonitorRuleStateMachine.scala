package ru.bitec.app.ops
package application.monitor

import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.metric.MetricObservation
import domain.monitor.{MonitorRule, MonitorRuleState, MonitorRuleStatus}

import java.time.{Duration, Instant}

/** What one monitor rule evaluation wants to change, before any identifier or write exists. */
final case class EvaluationPlan(
  state: MonitorRuleState,
  resolvedIncident: Option[Incident],
  openedIncident: Option[OpenIncidentPlan]
)

final case class OpenIncidentPlan(reason: IncidentReason, startedAt: Instant)

/** The pure part of monitoring: observation freshness, rule state transitions and the incident
  * changes they imply. No clock, no identifiers, no persistence.
  */
object MonitorRuleStateMachine {

  /** `None` means the rule cannot be judged yet and nothing is written for it. */
  def calculate(
    input: MonitorEvaluationInput,
    evaluatedAt: Instant
  ): Either[Throwable, Option[EvaluationPlan]] =
    freshness(input.rule, input.observation, evaluatedAt) match {
      case Undetermined =>
        Right(None)

      case NoFreshObservation =>
        val state = MonitorRuleState(
          input.rule.organizationId, input.rule.id, MonitorRuleStatus.NoData, None, evaluatedAt
        )
        incidentChanges(input, state, noDataStartedAt(input, evaluatedAt), evaluatedAt)
          .map { case (resolved, opened) => Some(EvaluationPlan(state, resolved, opened)) }

      case Fresh(observation) =>
        for {
          state <- nextThresholdState(input.rule, input.state, observation, evaluatedAt)
          startedAt = state.pendingSince.getOrElse(observation.observedAt)
          changes <- incidentChanges(input, state, startedAt, evaluatedAt)
        } yield Some(EvaluationPlan(state, changes._1, changes._2))
    }

  /** Observations age out after `noDataSeconds`; `0` turns no-data detection off for a rule. */
  private def freshness(
    rule: MonitorRule,
    observation: Option[MetricObservation],
    evaluatedAt: Instant
  ): Freshness =
    observation match {
      case Some(value) if rule.noDataSeconds == 0 || elapsed(value.observedAt, evaluatedAt) < rule.noDataSeconds =>
        Fresh(value)
      case Some(_) =>
        NoFreshObservation
      case None if rule.noDataSeconds == 0 =>
        Undetermined
      case None if elapsed(rule.createdAt, evaluatedAt) >= rule.noDataSeconds =>
        // A rule that has never seen an observation only reports NO_DATA once its own timeout
        // has passed, so creating a rule never opens an incident in the same moment.
        NoFreshObservation
      case None =>
        Undetermined
    }

  private def nextThresholdState(
    rule: MonitorRule,
    currentState: Option[MonitorRuleState],
    observation: MetricObservation,
    evaluatedAt: Instant
  ): Either[IllegalArgumentException, MonitorRuleState] =
    if (!rule.operator.matches(observation.value, rule.threshold)) {
      Right(MonitorRuleState(rule.organizationId, rule.id, MonitorRuleStatus.Ok, None, evaluatedAt))
    } else {
      currentState.map(_.status) match {
        // A violation seen after a gap in the data starts its own duration window: the previous
        // pendingSince covered a period we can no longer vouch for.
        case None | Some(MonitorRuleStatus.Ok) | Some(MonitorRuleStatus.NoData) =>
          Right(MonitorRuleState(
            rule.organizationId,
            rule.id,
            if (rule.forSeconds == 0) MonitorRuleStatus.Firing else MonitorRuleStatus.Pending,
            Some(observation.observedAt),
            evaluatedAt
          ))

        case Some(MonitorRuleStatus.Pending) =>
          currentState.flatMap(_.pendingSince) match {
            case Some(pendingSince) =>
              val status =
                if (elapsed(pendingSince, observation.observedAt) >= rule.forSeconds) MonitorRuleStatus.Firing
                else MonitorRuleStatus.Pending
              Right(currentState.get.copy(status = status, updatedAt = evaluatedAt))
            case None => Left(invalidPendingSince(rule, MonitorRuleStatus.Pending))
          }

        case Some(MonitorRuleStatus.Firing) =>
          currentState.flatMap(_.pendingSince) match {
            case Some(_) => Right(currentState.get.copy(updatedAt = evaluatedAt))
            case None => Left(invalidPendingSince(rule, MonitorRuleStatus.Firing))
          }
      }
    }

  /** At most one incident is OPEN per rule, so a change of reason resolves before it opens. */
  private def incidentChanges(
    input: MonitorEvaluationInput,
    nextState: MonitorRuleState,
    startedAt: Instant,
    evaluatedAt: Instant
  ): Either[Throwable, (Option[Incident], Option[OpenIncidentPlan])] = {
    val expected = incidentReasonFor(input.state.map(_.status))
    val target = incidentReasonFor(Some(nextState.status))

    validate(input, expected).map { _ =>
      (expected, target) match {
        case (current, next) if current == next =>
          (None, None)
        case (Some(_), None) =>
          (input.openIncident.map(resolve(_, evaluatedAt)), None)
        case (None, Some(reason)) =>
          (None, Some(OpenIncidentPlan(reason, startedAt)))
        case (Some(_), Some(reason)) =>
          (input.openIncident.map(resolve(_, evaluatedAt)), Some(OpenIncidentPlan(reason, startedAt)))
      }
    }
  }

  private def validate(
    input: MonitorEvaluationInput,
    expected: Option[IncidentReason]
  ): Either[Throwable, Unit] =
    (expected, input.openIncident) match {
      case (Some(reason), None) =>
        Left(new IllegalStateException(
          s"Monitor rule ${input.rule.id} requires an OPEN ${reason.code} incident but has none"
        ))
      case (None, Some(incident)) =>
        Left(new IllegalStateException(
          s"Monitor rule ${input.rule.id} is healthy while incident ${incident.id} is still OPEN"
        ))
      case (Some(reason), Some(incident)) if incident.reason != reason =>
        Left(new IllegalStateException(
          s"Monitor rule ${input.rule.id} expects an OPEN ${reason.code} incident but incident " +
            s"${incident.id} is ${incident.reason.code}"
        ))
      case _ => Right(())
    }

  private def incidentReasonFor(status: Option[MonitorRuleStatus]): Option[IncidentReason] =
    status match {
      case Some(MonitorRuleStatus.Firing) => Some(IncidentReason.ThresholdViolation)
      case Some(MonitorRuleStatus.NoData) => Some(IncidentReason.NoData)
      case _ => None
    }

  /** A no-data incident starts when data was last seen, or when the rule started waiting. */
  private def noDataStartedAt(input: MonitorEvaluationInput, evaluatedAt: Instant): Instant =
    input.observation.map(_.observedAt).getOrElse(input.rule.createdAt) match {
      case startedAt if startedAt.isAfter(evaluatedAt) => evaluatedAt
      case startedAt => startedAt
    }

  private def resolve(incident: Incident, evaluatedAt: Instant): Incident =
    incident.copy(
      status = IncidentStatus.Resolved,
      resolvedAt = Some(evaluatedAt),
      updatedAt = evaluatedAt
    )

  private def invalidPendingSince(rule: MonitorRule, status: MonitorRuleStatus): IllegalArgumentException =
    new IllegalArgumentException(
      s"Monitor rule state ${rule.id} has invalid pendingSince for status ${status.code}"
    )

  private def elapsed(from: Instant, to: Instant): Long = Duration.between(from, to).getSeconds

  private sealed trait Freshness
  private final case class Fresh(observation: MetricObservation) extends Freshness
  private case object NoFreshObservation extends Freshness
  private case object Undetermined extends Freshness
}
