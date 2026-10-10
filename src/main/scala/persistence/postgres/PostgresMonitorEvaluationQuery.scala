package ru.bitec.app.ops
package persistence.postgres

import application.monitor.MonitorEvaluationInput
import application.port.MonitorEvaluationQuery
import cats.data.NonEmptyList
import cats.syntax.all._
import domain.incident.{Incident, IncidentReason, IncidentStatus}
import domain.metric.{MetricCode, MetricObservation}
import domain.monitor.{MonitorOperator, MonitorRule, MonitorRuleState, MonitorRuleStatus}
import org.typelevel.doobie.{ConnectionIO, Fragments}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresMonitorEvaluationQuery extends MonitorEvaluationQuery[ConnectionIO] {
  private final case class EvaluationRow(
    ruleId: UUID,
    ruleOrganizationId: UUID,
    ruleResourceId: UUID,
    ruleMetricCode: String,
    ruleOperator: String,
    ruleThreshold: BigDecimal,
    ruleForSeconds: Long,
    ruleNoDataSeconds: Long,
    ruleEnabled: Boolean,
    ruleCreatedAt: Instant,
    ruleUpdatedAt: Instant,
    observationId: Option[UUID],
    observationOrganizationId: Option[UUID],
    observationResourceId: Option[UUID],
    observationMetricCode: Option[String],
    observationValue: Option[BigDecimal],
    observationObservedAt: Option[Instant],
    stateOrganizationId: Option[UUID],
    stateRuleId: Option[UUID],
    stateStatus: Option[String],
    statePendingSince: Option[Instant],
    stateUpdatedAt: Option[Instant],
    incidentId: Option[UUID],
    incidentOrganizationId: Option[UUID],
    incidentRuleId: Option[UUID],
    incidentResourceId: Option[UUID],
    incidentStatus: Option[String],
    incidentReason: Option[String],
    incidentStartedAt: Option[Instant],
    incidentOpenedAt: Option[Instant],
    incidentResolvedAt: Option[Instant],
    incidentCreatedAt: Option[Instant],
    incidentUpdatedAt: Option[Instant],
    incidentNotificationsSilenced: Option[Boolean],
    incidentAcknowledgedAt: Option[Instant],
    incidentAcknowledgedBy: Option[UUID],
    resourceName: String,
    resourceTypeName: String,
    environmentName: Option[String],
    projectName: Option[String],
    inMaintenance: Boolean
  ) {
    def toDomain: Either[IllegalArgumentException, MonitorEvaluationInput] =
      for {
        metricCode <- MetricCode.fromCode(ruleMetricCode)
        operator <- MonitorOperator.fromCode(ruleOperator)
        observation <- optionalObservation
        state <- optionalState
        incident <- optionalIncident
      } yield MonitorEvaluationInput(
        MonitorRule(ruleId, ruleOrganizationId, ruleResourceId, metricCode, operator,
          ruleThreshold, ruleForSeconds, ruleNoDataSeconds, ruleEnabled, ruleCreatedAt, ruleUpdatedAt),
        observation,
        state,
        incident,
        // Monitoring targets nodes, so the monitored resource is itself the server.
        serverName = resourceName,
        resourceTypeName = resourceTypeName,
        environmentName = environmentName,
        projectName = projectName,
        inMaintenance = inMaintenance
      )

    private def optionalObservation: Either[IllegalArgumentException, Option[MetricObservation]] =
      observationId match {
        case None => Right(None)
        case Some(id) => for {
          organizationId <- required(observationOrganizationId, "observation.organizationId")
          resourceId <- required(observationResourceId, "observation.resourceId")
          metricCodeRaw <- required(observationMetricCode, "observation.metricCode")
          metricCode <- MetricCode.fromCode(metricCodeRaw)
          value <- required(observationValue, "observation.value")
          observedAt <- required(observationObservedAt, "observation.observedAt")
        } yield Some(MetricObservation(id, organizationId, resourceId, metricCode, value, observedAt))
      }

    private def optionalState: Either[IllegalArgumentException, Option[MonitorRuleState]] =
      stateRuleId match {
        case None => Right(None)
        case Some(monitorRuleId) => for {
          organizationId <- required(stateOrganizationId, "state.organizationId")
          statusRaw <- required(stateStatus, "state.status")
          status <- MonitorRuleStatus.fromCode(statusRaw)
          updatedAt <- required(stateUpdatedAt, "state.updatedAt")
        } yield Some(MonitorRuleState(organizationId, monitorRuleId, status, statePendingSince, updatedAt))
      }

    private def optionalIncident: Either[IllegalArgumentException, Option[Incident]] =
      incidentId match {
        case None => Right(None)
        case Some(id) => for {
          organizationId <- required(incidentOrganizationId, "incident.organizationId")
          monitorRuleId <- required(incidentRuleId, "incident.monitorRuleId")
          resourceId <- required(incidentResourceId, "incident.resourceId")
          statusRaw <- required(incidentStatus, "incident.status")
          status <- IncidentStatus.fromCode(statusRaw)
          reasonRaw <- required(incidentReason, "incident.reason")
          reason <- IncidentReason.fromCode(reasonRaw)
          startedAt <- required(incidentStartedAt, "incident.startedAt")
          openedAt <- required(incidentOpenedAt, "incident.openedAt")
          createdAt <- required(incidentCreatedAt, "incident.createdAt")
          updatedAt <- required(incidentUpdatedAt, "incident.updatedAt")
        } yield Some(Incident(id, organizationId, monitorRuleId, resourceId, status, reason,
          startedAt, openedAt, incidentResolvedAt, createdAt, updatedAt,
          incidentNotificationsSilenced.getOrElse(false), incidentAcknowledgedAt, incidentAcknowledgedBy))
      }

    private def required[A](value: Option[A], field: String): Either[IllegalArgumentException, A] =
      value.toRight(new IllegalArgumentException(s"Monitor evaluation row is missing $field"))
  }

  override def findEnabledForConnection(
    organizationId: UUID,
    connectionId: UUID,
    resourceTypeCodes: List[String],
    at: Instant
  ): ConnectionIO[List[MonitorEvaluationInput]] =
    NonEmptyList.fromList(resourceTypeCodes.distinct) match {
      case None => List.empty[MonitorEvaluationInput].pure[ConnectionIO]
      case Some(codes) =>
        val query =
          fr"""
            select
              mr.id, mr.organization_id, mr.resource_id, mr.metric_code, mr.operator,
              mr.threshold, mr.for_seconds, mr.no_data_seconds, mr.enabled, mr.created_at, mr.updated_at,
              mo.id, mo.organization_id, mo.resource_id, mo.metric_code, mo.value, mo.observed_at,
              mrs.organization_id, mrs.monitor_rule_id, mrs.status, mrs.pending_since, mrs.updated_at,
              i.id, i.organization_id, i.monitor_rule_id, i.resource_id, i.status, i.reason,
              i.started_at, i.opened_at, i.resolved_at, i.created_at, i.updated_at,
              i.notifications_silenced, i.acknowledged_at, i.acknowledged_by,
              r.name, rt.name, e.name, p.name,
              -- A window on the resource itself or on the resource directly above it.
              exists (
                select 1 from maintenance_window w
                where w.organization_id = mr.organization_id
                  and w.resource_id in (r.id, r.parent_resource_id)
                  and w.starts_at <= $at and w.ends_at > $at
                  and (w.cancelled_at is null or w.cancelled_at > $at)
              )
            from monitor_rule mr
            join resource r
              on r.id = mr.resource_id
             and r.organization_id = mr.organization_id
            join resource_type rt
              on rt.id = r.resource_type_id
            left join environment e
              on e.id = r.environment_id
             and e.organization_id = r.organization_id
            left join project p
              on p.id = e.project_id
             and p.organization_id = e.organization_id
            left join lateral (
              select observation.id, observation.organization_id, observation.resource_id,
                     observation.metric_code, observation.value, observation.observed_at
              from metric_observation observation
              where observation.organization_id = mr.organization_id
                and observation.resource_id = mr.resource_id
                and observation.metric_code = mr.metric_code
              order by observation.observed_at desc, observation.id desc
              limit 1
            ) mo on true
            left join monitor_rule_state mrs
              on mrs.organization_id = mr.organization_id
             and mrs.monitor_rule_id = mr.id
            left join incident i
              on i.organization_id = mr.organization_id
             and i.monitor_rule_id = mr.id
             and i.status = 'OPEN'
            where mr.organization_id = $organizationId
              and mr.enabled = true
              and r.is_active = true
              and exists (
                select 1
                from external_ref er
                where er.organization_id = mr.organization_id
                  and er.resource_id = mr.resource_id
                  and er.connection_id = $connectionId
              )
              and
          """ ++ Fragments.in(fr"rt.code", codes) ++
            // Locking the selected monitor_rule rows serializes an evaluation against a
            // concurrent rule change: either this evaluation finishes and the update cleans up
            // after it, or the update commits first and its rule is no longer selected here.
            // Only the driving table is locked; the outer joins and the lateral stay unlocked.
            fr"order by mr.id for update of mr"

        query.query[EvaluationRow].to[List]
          .flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
    }
}
