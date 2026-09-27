package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  ConnectionInfrastructureCounts,
  ConnectionResourceCounts,
  EnvironmentReference,
  InfrastructureContextQuery,
  InfrastructureLocation,
  LocatedResource,
  ProjectReference,
  ResourceContextView,
  ResourceReference,
  ResourceSources,
  ResourceTypeCount,
  SourceConnectionReference
}
import domain.incident.IncidentStatus
import domain.resource.Resource

import cats.syntax.all._
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

import PostgresIncidentListQuery.groupSources

/** Places connections and resources in the infrastructure graph through `external_ref`.
  *
  * No statement here runs per row: lists are one statement, and the composite reads are a fixed
  * handful meant to run inside one read-only snapshot. Every statement names the organization,
  * and every join repeats it, so an identifier of another tenant matches nothing.
  */
final class PostgresInfrastructureContextQuery(
  resourceDataJsonCodec: ResourceDataJsonCodec
) extends InfrastructureContextQuery[ConnectionIO] {

  private val open = IncidentStatus.Open.code

  override def connectionExists(organizationId: UUID, connectionId: UUID): ConnectionIO[Boolean] =
    sql"select exists (select 1 from connection where organization_id = $organizationId and id = $connectionId)"
      .query[Boolean]
      .unique

  override def resourceExists(organizationId: UUID, resourceId: UUID): ConnectionIO[Boolean] =
    sql"select exists (select 1 from resource where organization_id = $organizationId and id = $resourceId)"
      .query[Boolean]
      .unique

  override def connectionResources(
    organizationId: UUID,
    connectionId: UUID,
    limit: Int
  ): ConnectionIO[List[LocatedResource]] =
    (resourceColumns ++ fr", e.name, e.kind" ++ fr"""
      from resource r
      join resource_type rt on rt.id = r.resource_type_id
      join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
      where r.organization_id = $organizationId
        and r.is_active = true
    """ ++ discoveredBy(organizationId, fr"r.id", connectionId) ++ fr"""
      order by e.name, e.id, r.parent_resource_id nulls first, r.name, r.id
      limit $limit
    """)
      .query[(ResourceRow, String, String)]
      .to[List]
      .flatMap(_.traverse { case (row, environmentName, environmentKind) =>
        row.toDomain(resourceDataJsonCodec)
          .map(resource => LocatedResource(resource, EnvironmentReference(row.environmentId, environmentName, environmentKind)))
          .liftTo[ConnectionIO]
      })

  override def connectionCounts(organizationId: UUID, connectionId: UUID): ConnectionIO[ConnectionResourceCounts] =
    for {
      counts <- (fr"""
        select
          count(*) filter (where r.is_active),
          count(*) filter (where not r.is_active),
          (
            select count(*) from incident i
            where i.organization_id = $organizationId
              and i.status = $open
      """ ++ discoveredBy(organizationId, fr"i.resource_id", connectionId) ++ fr"""
          )
        from resource r
        where r.organization_id = $organizationId
      """ ++ discoveredBy(organizationId, fr"r.id", connectionId))
        .query[(Long, Long, Long)]
        .unique
      byType <- (fr"""
        select rt.code, count(*)
        from resource r
        join resource_type rt on rt.id = r.resource_type_id
        where r.organization_id = $organizationId
          and r.is_active = true
      """ ++ discoveredBy(organizationId, fr"r.id", connectionId) ++ fr"group by rt.code order by rt.code")
        .query[(String, Long)]
        .to[List]
    } yield ConnectionResourceCounts(
      counts._1,
      counts._2,
      counts._3,
      byType.map { case (code, count) => ResourceTypeCount(code, count) }
    )

  /** One grouped statement for the whole list. `count(distinct ...)` absorbs the fan-out of a
    * resource known through several external references and of a resource with several open
    * incidents.
    */
  override def organizationConnectionCounts(organizationId: UUID): ConnectionIO[List[ConnectionInfrastructureCounts]] =
    sql"""
      select c.id,
        count(distinct r.id) filter (where r.is_active),
        count(distinct i.id)
      from connection c
      left join external_ref er on er.connection_id = c.id and er.organization_id = c.organization_id
      left join resource r on r.id = er.resource_id and r.organization_id = er.organization_id
      left join incident i
        on i.resource_id = r.id and i.organization_id = r.organization_id and i.status = $open
      where c.organization_id = $organizationId
      group by c.id
      order by c.id
    """
      .query[(UUID, Long, Long)]
      .to[List]
      .map(_.map { case (id, resources, incidents) => ConnectionInfrastructureCounts(id, resources, incidents) })

  /** Three statements whatever the size of the neighbourhood: the resource's place, its sources
    * and a bounded preview of its children.
    */
  override def resourceContext(
    organizationId: UUID,
    resourceId: UUID,
    childLimit: Int
  ): ConnectionIO[Option[ResourceContextView]] =
    sql"""
      select e.id, e.name, e.kind, p.id, p.name, pr.id, pr.name, prt.code,
        (
          select count(*) from resource child
          where child.organization_id = r.organization_id
            and child.parent_resource_id = r.id
            and child.is_active = true
        ),
        (
          select count(*) from incident i
          where i.organization_id = r.organization_id
            and i.resource_id = r.id
            and i.status = $open
        )
      from resource r
      join environment e on e.id = r.environment_id and e.organization_id = r.organization_id
      join project p on p.id = e.project_id and p.organization_id = e.organization_id
      left join resource pr on pr.id = r.parent_resource_id and pr.organization_id = r.organization_id
      left join resource_type prt on prt.id = pr.resource_type_id
      where r.organization_id = $organizationId
        and r.id = $resourceId
    """
      .query[(UUID, String, String, UUID, String, Option[UUID], Option[String], Option[String], Long, Long)]
      .option
      .flatMap {
        case None => none[ResourceContextView].pure[ConnectionIO]
        case Some((environmentId, environmentName, environmentKind, projectId, projectName,
          parentId, parentName, parentTypeCode, childCount, openIncidents)) =>
          for {
            sources <- sourcesOf(organizationId, resourceId)
            children <- childrenOf(organizationId, resourceId, childLimit)
          } yield Some(ResourceContextView(
            InfrastructureLocation(
              ProjectReference(projectId, projectName),
              EnvironmentReference(environmentId, environmentName, environmentKind)
            ),
            (parentId, parentName, parentTypeCode).mapN(ResourceReference.apply),
            sources,
            children,
            childCount,
            openIncidents
          ))
      }

  override def environmentResourceSources(
    organizationId: UUID,
    environmentId: UUID
  ): ConnectionIO[List[ResourceSources]] =
    sql"""
      select distinct r.id, c.id, c.name, c.connector_type, c.is_active
      from resource r
      join external_ref er on er.resource_id = r.id and er.organization_id = r.organization_id
      join connection c on c.id = er.connection_id and c.organization_id = er.organization_id
      where r.organization_id = $organizationId
        and r.environment_id = $environmentId
        and r.is_active = true
      order by r.id, c.name, c.id
    """
      .query[(UUID, UUID, String, String, Boolean)]
      .to[List]
      .map { rows =>
        groupSources(rows.map { case (resourceId, id, name, connectorType, active) =>
          (resourceId, resourceId, Option(SourceConnectionReference(id, name, connectorType, active)))
        }).map { case (resourceId, sources) => ResourceSources(resourceId, sources) }
      }

  private def sourcesOf(organizationId: UUID, resourceId: UUID): ConnectionIO[List[SourceConnectionReference]] =
    sql"""
      select distinct c.id, c.name, c.connector_type, c.is_active
      from external_ref er
      join connection c on c.id = er.connection_id and c.organization_id = er.organization_id
      where er.organization_id = $organizationId
        and er.resource_id = $resourceId
      order by c.name, c.id
    """
      .query[(UUID, String, String, Boolean)]
      .to[List]
      .map(_.map { case (id, name, connectorType, active) => SourceConnectionReference(id, name, connectorType, active) })

  private def childrenOf(organizationId: UUID, resourceId: UUID, limit: Int): ConnectionIO[List[Resource]] =
    (resourceColumns ++ fr"""
      from resource r
      join resource_type rt on rt.id = r.resource_type_id
      where r.organization_id = $organizationId
        and r.parent_resource_id = $resourceId
        and r.is_active = true
      order by r.name, r.id
      limit $limit
    """)
      .query[ResourceRow]
      .to[List]
      .flatMap(_.traverse(_.toDomain(resourceDataJsonCodec).liftTo[ConnectionIO]))

  /** The resource was discovered by the connection: a semi-join, so a resource known through
    * several references of one connection still counts once.
    */
  private def discoveredBy(organizationId: UUID, resourceId: Fragment, connectionId: UUID): Fragment =
    fr"and exists (select 1 from external_ref er where er.organization_id = $organizationId and er.resource_id =" ++
      resourceId ++ fr"and er.connection_id = $connectionId)"

  /** The columns `ResourceRow` reads, in its order. */
  private val resourceColumns: Fragment =
    fr"""
      select r.id, r.organization_id, r.environment_id, r.resource_type_id, r.parent_resource_id,
        r.code, r.name, r.is_active, r.created_at, r.updated_at, rt.code, r.spec::text, r.status::text
    """
}
