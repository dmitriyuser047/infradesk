package ru.bitec.app.ops
package persistence.postgres

import application.port._
import cats.syntax.all._
import domain.connection.ConnectionScope
import domain.history.{HistoryEventCursor, HistoryEventSource, HistoryEventType}
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

import PostgresHistoryEventQuery.HistoryRow

/** One statement per page: the timeline joins what it displays instead of loading it per row. */
final class PostgresHistoryEventQuery extends HistoryEventQuery[ConnectionIO] {

  override def listByOrganization(
    organizationId: UUID,
    before: Option[HistoryEventCursor],
    limit: Int
  ): ConnectionIO[List[HistoryEventView]] =
    run(fr"where h.organization_id = $organizationId", before, limit)

  override def listByResource(
    organizationId: UUID,
    resourceId: UUID,
    before: Option[HistoryEventCursor],
    limit: Int
  ): ConnectionIO[List[HistoryEventView]] =
    run(fr"where h.organization_id = $organizationId and h.resource_id = $resourceId", before, limit)

  override def listByScope(
    organizationId: UUID,
    scope: ConnectionScope,
    limit: Int
  ): ConnectionIO[List[HistoryEventView]] =
    run(fr"where h.organization_id = $organizationId" ++ inScope(scope), None, limit)

  /** An entry with a resource belongs where that resource lives; one without it (a failed
    * synchronization) belongs where its connection is attached. Both joins are already part of
    * the projection, so the scope adds a predicate and never another statement.
    */
  private def inScope(scope: ConnectionScope): Fragment = scope match {
    case ConnectionScope.Organization => fr""
    case ConnectionScope.Project(projectId) =>
      fr"""and case when h.resource_id is not null
        then exists (select 1 from environment e
          where e.id = r.environment_id and e.organization_id = h.organization_id
            and e.project_id = $projectId)
        else c.project_id = $projectId end"""
    case ConnectionScope.Environment(_, environmentId) =>
      fr"""and case when h.resource_id is not null
        then r.environment_id = $environmentId
        else c.environment_id = $environmentId end"""
  }

  private def run(
    scope: Fragment,
    before: Option[HistoryEventCursor],
    limit: Int
  ): ConnectionIO[List[HistoryEventView]] = {
    val cursor = before.fold(fr"")(value =>
      fr"and (h.occurred_at, h.id) < (${value.occurredAt}, ${value.id})"
    )

    (select ++ scope ++ cursor ++ fr"order by h.occurred_at desc, h.id desc limit $limit")
      .query[HistoryRow]
      .to[List]
      .flatMap(_.traverse(_.toView.liftTo[ConnectionIO]))
  }

  private val select =
    fr"""
      select h.id, h.event_type, h.source, h.occurred_at,
        r.id, r.name, rt.code, r.environment_id,
        c.id, c.name,
        u.id, u.display_name,
        i.id, i.status, i.reason, i.monitor_rule_id,
        oe.id, oe.operation, oe.status, oe.error_code, oe.error_message,
        s.id, s.status, s.error_code
      from history_event h
      left join resource r on r.id = h.resource_id and r.organization_id = h.organization_id
      left join resource_type rt on rt.id = r.resource_type_id
      left join connection c on c.id = h.connection_id and c.organization_id = h.organization_id
      left join user_account u on u.id = h.actor_user_id
      left join incident i on i.id = h.incident_id and i.organization_id = h.organization_id
      left join operation_execution oe
        on oe.id = h.operation_execution_id and oe.organization_id = h.organization_id
      left join sync_session s on s.id = h.sync_session_id and s.organization_id = h.organization_id
    """
}

object PostgresHistoryEventQuery {

  private[postgres] final case class HistoryRow(
    id: UUID,
    eventType: String,
    source: String,
    occurredAt: Instant,
    resourceId: Option[UUID],
    resourceName: Option[String],
    resourceTypeCode: Option[String],
    resourceEnvironmentId: Option[UUID],
    connectionId: Option[UUID],
    connectionName: Option[String],
    actorId: Option[UUID],
    actorDisplayName: Option[String],
    incidentId: Option[UUID],
    incidentStatus: Option[String],
    incidentReason: Option[String],
    incidentMonitorRuleId: Option[UUID],
    operationId: Option[UUID],
    operationCode: Option[String],
    operationStatus: Option[String],
    operationErrorCode: Option[String],
    operationErrorMessage: Option[String],
    syncSessionId: Option[UUID],
    syncStatus: Option[String],
    syncErrorCode: Option[String]
  ) {
    def toView: Either[IllegalArgumentException, HistoryEventView] =
      for {
        typedEventType <- HistoryEventType.fromCode(eventType)
        typedSource <- HistoryEventSource.fromCode(source)
      } yield HistoryEventView(
        id,
        typedEventType,
        typedSource,
        occurredAt,
        (resourceId, resourceName, resourceTypeCode, resourceEnvironmentId).tupled.map {
          case (id, name, code, environmentId) => HistoryResourceView(id, name, code, environmentId)
        },
        (connectionId, connectionName).tupled.map {
          case (id, name) => HistoryConnectionView(id, name)
        },
        (actorId, actorDisplayName).tupled.map {
          case (id, name) => HistoryActorView(id, name)
        },
        (incidentId, incidentStatus, incidentReason, incidentMonitorRuleId).tupled.map {
          case (id, status, reason, ruleId) => HistoryIncidentView(id, status, reason, ruleId)
        },
        (operationId, operationCode, operationStatus).tupled.map {
          case (id, code, status) =>
            HistoryOperationView(id, code, status, operationErrorCode, operationErrorMessage)
        },
        (syncSessionId, syncStatus).tupled.map {
          case (id, status) => HistorySyncView(id, status, syncErrorCode)
        }
      )
  }
}
