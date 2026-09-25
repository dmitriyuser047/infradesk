package ru.bitec.app.ops
package persistence.postgres

import application.port.{IncidentListItem, IncidentListQuery, IncidentResourceReference}
import domain.incident.IncidentStatus

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

import PostgresIncidentRepository.IncidentRow

/** One tenant-scoped statement: the incident joins its resource on the same organization, so a
  * resource of another tenant can never name an incident, and the list costs one read however many
  * resources it mentions.
  */
final class PostgresIncidentListQuery extends IncidentListQuery[ConnectionIO] {

  override def list(organizationId: UUID, status: Option[IncidentStatus]): ConnectionIO[List[IncidentListItem]] = {
    val statusFilter = status.fold(fr"")(value => fr"and i.status = ${value.code}")
    (fr"""
      select i.id, i.organization_id, i.monitor_rule_id, i.resource_id, i.status, i.reason,
        i.started_at, i.opened_at, i.resolved_at, i.created_at, i.updated_at,
        r.name, rt.code
      from incident i
      join resource r on r.id = i.resource_id and r.organization_id = i.organization_id
      join resource_type rt on rt.id = r.resource_type_id
      where i.organization_id = $organizationId
    """ ++ statusFilter ++ fr"order by i.opened_at desc, i.id desc")
      .query[(IncidentRow, String, String)]
      .to[List]
      .flatMap(_.traverse { case (row, name, typeCode) =>
        row.toDomain
          .map(incident => IncidentListItem(incident, IncidentResourceReference(incident.resourceId, name, typeCode)))
          .liftTo[ConnectionIO]
      })
  }
}
