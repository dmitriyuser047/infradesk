package ru.bitec.app.ops
package persistence.postgres

import application.port.IncidentRepository
import domain.incident.{Incident, IncidentStatus}

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.Query0
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

import PostgresIncidentRepository.IncidentRow

final class PostgresIncidentRepository extends IncidentRepository[ConnectionIO] {
  private val selectColumns = fr"select id, organization_id, monitor_rule_id, resource_id, status, started_at, opened_at, resolved_at, created_at, updated_at from incident"
  private def rows(query: Query0[IncidentRow]): ConnectionIO[List[Incident]] = query.to[List].flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  override def findOpenByRule(
                               organizationId: UUID,
                               monitorRuleId: UUID
                             ): ConnectionIO[Option[Incident]] =
    sql"""
      select
        id,
        organization_id,
        monitor_rule_id,
        resource_id,
        status,
        started_at,
        opened_at,
        resolved_at,
        created_at,
        updated_at
      from incident
      where organization_id = $organizationId
        and monitor_rule_id = $monitorRuleId
        and status = ${IncidentStatus.Open.code}
    """
      .query[IncidentRow]
      .option
      .flatMap {
        case Some(row) => row.toDomain.map(value => Option(value)).liftTo[ConnectionIO]
        case None => none[Incident].pure[ConnectionIO]
      }

  override def findById(organizationId: UUID, incidentId: UUID): ConnectionIO[Option[Incident]] =
    (selectColumns ++ fr"where organization_id = $organizationId and id = $incidentId").query[IncidentRow].option.flatMap {
      case Some(row) => row.toDomain.map(value => Option(value)).liftTo[ConnectionIO]; case None => none[Incident].pure[ConnectionIO]
    }

  override def findByOrganization(organizationId: UUID, status: Option[IncidentStatus]): ConnectionIO[List[Incident]] = {
    val where = status.fold(fr"where organization_id = $organizationId")(value => fr"where organization_id = $organizationId and status = ${value.code}")
    rows((selectColumns ++ where ++ fr"order by opened_at desc, id desc").query[IncidentRow])
  }

  override def save(incident: Incident): ConnectionIO[Unit] =
    sql"""
      insert into incident (
        id,
        organization_id,
        monitor_rule_id,
        resource_id,
        status,
        started_at,
        opened_at,
        resolved_at,
        created_at,
        updated_at
      )
      values (
        ${incident.id},
        ${incident.organizationId},
        ${incident.monitorRuleId},
        ${incident.resourceId},
        ${incident.status.code},
        ${incident.startedAt},
        ${incident.openedAt},
        ${incident.resolvedAt},
        ${incident.createdAt},
        ${incident.updatedAt}
      )
      on conflict (id)
      do update set
        status = excluded.status,
        resolved_at = excluded.resolved_at,
        updated_at = excluded.updated_at
      where incident.organization_id = excluded.organization_id
    """
      .update
      .run
      .flatMap {
        case 1 => ().pure[ConnectionIO]
        case rows =>
          new IllegalStateException(
            s"Expected to save 1 incident row, affected: ${rows}"
          ).raiseError[ConnectionIO, Unit]
      }
}

object PostgresIncidentRepository {

  private[postgres] final case class IncidentRow(
                                                   id: UUID,
                                                   organizationId: UUID,
                                                   monitorRuleId: UUID,
                                                   resourceId: UUID,
                                                   status: String,
                                                   startedAt: Instant,
                                                   openedAt: Instant,
                                                   resolvedAt: Option[Instant],
                                                   createdAt: Instant,
                                                   updatedAt: Instant
                                                 ) {
    def toDomain: Either[IllegalArgumentException, Incident] =
      IncidentStatus.fromCode(status).map { typedStatus =>
        Incident(
          id,
          organizationId,
          monitorRuleId,
          resourceId,
          typedStatus,
          startedAt,
          openedAt,
          resolvedAt,
          createdAt,
          updatedAt
        )
      }
  }
}
