package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  EnvironmentReference,
  IncidentCursor,
  IncidentListItem,
  IncidentListQuery,
  IncidentResourceReference,
  InfrastructureLocation,
  MonitorConditionView,
  ProjectReference,
  ResourceReference,
  SourceConnectionReference
}
import domain.incident.IncidentStatus

import cats.syntax.all._
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

import PostgresIncidentRepository.IncidentRow
import PostgresIncidentListQuery.{ContextColumns, groupSources}

/** Every incident list is one tenant-scoped statement.
  *
  * The page is chosen first, in a CTE that joins the resource, its environment and project, the
  * rule and the parent on the same organization; the source connections are then joined laterally
  * to the chosen rows only. A resource of another tenant can never name an incident, and a list
  * costs one read however many resources, environments or connections it mentions.
  */
final class PostgresIncidentListQuery extends IncidentListQuery[ConnectionIO] {

  override def list(organizationId: UUID, status: Option[IncidentStatus]): ConnectionIO[List[IncidentListItem]] =
    run(organizationId, statusFilter(status), None)

  override def listByConnection(
    organizationId: UUID,
    connectionId: UUID,
    status: Option[IncidentStatus],
    before: Option[IncidentCursor],
    limit: Int
  ): ConnectionIO[List[IncidentListItem]] =
    run(
      organizationId,
      fr"""
        and exists (
          select 1 from external_ref er
          where er.organization_id = i.organization_id
            and er.resource_id = i.resource_id
            and er.connection_id = $connectionId
        )
      """ ++ statusFilter(status) ++ cursorFilter(before),
      Some(limit)
    )

  override def listByResource(
    organizationId: UUID,
    resourceId: UUID,
    status: Option[IncidentStatus],
    before: Option[IncidentCursor],
    limit: Int
  ): ConnectionIO[List[IncidentListItem]] =
    run(organizationId, fr"and i.resource_id = $resourceId" ++ statusFilter(status) ++ cursorFilter(before), Some(limit))

  override def find(organizationId: UUID, incidentId: UUID): ConnectionIO[Option[IncidentListItem]] =
    run(organizationId, fr"and i.id = $incidentId", Some(1)).map(_.headOption)

  private def statusFilter(status: Option[IncidentStatus]): Fragment =
    status.fold(fr"")(value => fr"and i.status = ${value.code}")

  private def cursorFilter(before: Option[IncidentCursor]): Fragment =
    before.fold(fr"")(value => fr"and (i.opened_at, i.id) < (${value.openedAt}, ${value.id})")

  private def run(organizationId: UUID, filter: Fragment, limit: Option[Int]): ConnectionIO[List[IncidentListItem]] = {
    val pageLimit = limit.fold(fr"")(value => fr"limit $value")
    (fr"""
      with page as (
        select i.id, i.organization_id, i.monitor_rule_id, i.resource_id, i.status, i.reason,
          i.started_at, i.opened_at, i.resolved_at, i.created_at, i.updated_at,
          r.name as resource_name, rt.code as resource_type_code,
          e.id as environment_id, e.name as environment_name, e.kind as environment_kind,
          p.id as project_id, p.name as project_name,
          m.metric_code, m.operator, m.threshold, m.for_seconds, m.no_data_seconds,
          pr.id as parent_id, pr.name as parent_name, prt.code as parent_type_code
        from incident i
        join resource r on r.id = i.resource_id and r.organization_id = i.organization_id
        join resource_type rt on rt.id = r.resource_type_id
        join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
        join project p on p.id = e.project_id and p.organization_id = e.organization_id
        join monitor_rule m on m.id = i.monitor_rule_id and m.organization_id = i.organization_id
        left join resource pr on pr.id = r.parent_resource_id and pr.organization_id = r.organization_id
        left join resource_type prt on prt.id = pr.resource_type_id
        where i.organization_id = $organizationId
    """ ++ filter ++ fr"order by i.opened_at desc, i.id desc" ++ pageLimit ++ fr"""
      )
      select page.id, page.organization_id, page.monitor_rule_id, page.resource_id, page.status, page.reason,
        page.started_at, page.opened_at, page.resolved_at, page.created_at, page.updated_at,
        page.resource_name, page.resource_type_code,
        page.environment_id, page.environment_name, page.environment_kind,
        page.project_id, page.project_name,
        page.metric_code, page.operator, page.threshold, page.for_seconds, page.no_data_seconds,
        page.parent_id, page.parent_name, page.parent_type_code,
        src.id, src.name, src.connector_type, src.is_active
      from page
      left join lateral (
        select distinct c.id, c.name, c.connector_type, c.is_active
        from external_ref er
        join connection c on c.id = er.connection_id and c.organization_id = er.organization_id
        where er.organization_id = page.organization_id
          and er.resource_id = page.resource_id
      ) src on true
      order by page.opened_at desc, page.id desc, src.name, src.id
    """)
      .query[(IncidentRow, ContextColumns)]
      .to[List]
      .flatMap { rows =>
        groupSources(rows.map { case (row, columns) => (row.id, (row, columns), columns.source) })
          .traverse { case ((row, columns), sources) =>
            row.toDomain.map(incident => columns.toItem(incident, sources)).liftTo[ConnectionIO]
          }
      }
  }
}

object PostgresIncidentListQuery {

  private[postgres] final case class ContextColumns(
    resourceName: String,
    resourceTypeCode: String,
    environmentId: UUID,
    environmentName: String,
    environmentKind: String,
    projectId: UUID,
    projectName: String,
    metricCode: String,
    operator: String,
    threshold: BigDecimal,
    forSeconds: Long,
    noDataSeconds: Long,
    parentId: Option[UUID],
    parentName: Option[String],
    parentTypeCode: Option[String],
    sourceId: Option[UUID],
    sourceName: Option[String],
    sourceConnectorType: Option[String],
    sourceActive: Option[Boolean]
  ) {
    def source: Option[SourceConnectionReference] =
      (sourceId, sourceName, sourceConnectorType, sourceActive).mapN(SourceConnectionReference.apply)

    def toItem(incident: domain.incident.Incident, sources: List[SourceConnectionReference]): IncidentListItem =
      IncidentListItem(
        incident,
        IncidentResourceReference(incident.resourceId, resourceName, resourceTypeCode),
        InfrastructureLocation(
          ProjectReference(projectId, projectName),
          EnvironmentReference(environmentId, environmentName, environmentKind)
        ),
        MonitorConditionView(incident.monitorRuleId, metricCode, operator, threshold, forSeconds, noDataSeconds),
        (parentId, parentName, parentTypeCode).mapN(ResourceReference.apply),
        sources
      )
  }

  /** Folds the rows of a lateral join back into one entry per key, in the order the rows came.
    *
    * The statement orders by the key first, so every row of one key is adjacent; a key without any
    * joined row keeps an empty list.
    */
  private[postgres] def groupSources[K, A, S](rows: List[(K, A, Option[S])]): List[(A, List[S])] =
    rows.foldRight(List.empty[(K, A, List[S])]) {
      case ((key, _, source), (headKey, headValue, sources) :: tail) if headKey == key =>
        (headKey, headValue, source.toList ++ sources) :: tail
      case ((key, value, source), grouped) =>
        (key, value, source.toList) :: grouped
    }.map { case (_, value, sources) => (value, sources) }
}
