package ru.bitec.app.ops
package persistence.postgres

import application.port.{IncidentAcknowledgement, IncidentAcknowledgementRepository, MaintenanceWindowCancellation,
  MaintenanceWindowRepository, MaintenanceWindowView}
import cats.syntax.all._
import domain.maintenance.MaintenanceWindow
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Every statement is scoped by organization; a window id of another tenant reads as absent. */
final class PostgresMaintenanceWindowRepository
  extends MaintenanceWindowRepository[ConnectionIO] with IncidentAcknowledgementRepository[ConnectionIO] {

  private type WindowRow = (UUID, UUID, UUID, Instant, Instant, String, UUID, Instant, Option[Instant], Option[UUID])
  private type ViewRow = (WindowRow, (String, String, String, Option[String]))

  private def window(row: WindowRow): MaintenanceWindow = {
    val (id, org, resource, starts, ends, reason, by, created, cancelledAt, cancelledBy) = row
    MaintenanceWindow(id, org, resource, starts, ends, reason, by, created, cancelledAt, cancelledBy)
  }

  private val viewSelect: Fragment =
    fr"""select w.id, w.organization_id, w.resource_id, w.starts_at, w.ends_at, w.reason, w.created_by,
        w.created_at, w.cancelled_at, w.cancelled_by, r.name, rt.code, cu.display_name, xu.display_name
      from maintenance_window w
      join resource r on r.id = w.resource_id and r.organization_id = w.organization_id
      join resource_type rt on rt.id = r.resource_type_id
      join user_account cu on cu.id = w.created_by
      left join user_account xu on xu.id = w.cancelled_by"""

  private def view(row: ViewRow): MaintenanceWindowView = {
    val (windowRow, (resourceName, typeCode, createdBy, cancelledBy)) = row
    MaintenanceWindowView(window(windowRow), resourceName, typeCode, createdBy, cancelledBy)
  }

  override def insert(value: MaintenanceWindow): ConnectionIO[Unit] =
    sql"""insert into maintenance_window
        (id, organization_id, resource_id, starts_at, ends_at, reason, created_by, created_at)
      values (${value.id}, ${value.organizationId}, ${value.resourceId}, ${value.startsAt}, ${value.endsAt},
        ${value.reason}, ${value.createdBy}, ${value.createdAt})""".update.run.void

  override def cancel(org: UUID, id: UUID, by: UUID, now: Instant): ConnectionIO[MaintenanceWindowCancellation] =
    sql"""select id, organization_id, resource_id, starts_at, ends_at, reason, created_by, created_at,
        cancelled_at, cancelled_by
      from maintenance_window where organization_id = $org and id = $id for update"""
      .query[WindowRow].option.map(_.map(window)).flatMap {
        case None => (MaintenanceWindowCancellation.NotFound: MaintenanceWindowCancellation).pure[ConnectionIO]
        case Some(found) if found.cancelledAt.nonEmpty =>
          (MaintenanceWindowCancellation.Unchanged(found): MaintenanceWindowCancellation).pure[ConnectionIO]
        case Some(found) if !found.endsAt.isAfter(now) =>
          (MaintenanceWindowCancellation.Finished: MaintenanceWindowCancellation).pure[ConnectionIO]
        case Some(found) =>
          sql"""update maintenance_window set cancelled_at = $now, cancelled_by = $by
            where organization_id = $org and id = $id""".update.run
            .as(MaintenanceWindowCancellation.Cancelled(found.copy(cancelledAt = Some(now), cancelledBy = Some(by))))
      }

  override def list(org: UUID, now: Instant, limit: Int): ConnectionIO[List[MaintenanceWindowView]] =
    (viewSelect ++ fr"""where w.organization_id = $org
      order by (least(w.ends_at, coalesce(w.cancelled_at, w.ends_at)) > $now) desc,
        case when least(w.ends_at, coalesce(w.cancelled_at, w.ends_at)) > $now then w.starts_at end asc,
        least(w.ends_at, coalesce(w.cancelled_at, w.ends_at)) desc, w.id
      limit $limit""").query[ViewRow].to[List].map(_.map(view))

  override def find(org: UUID, id: UUID): ConnectionIO[Option[MaintenanceWindowView]] =
    (viewSelect ++ fr"where w.organization_id = $org and w.id = $id").query[ViewRow].option.map(_.map(view))

  /** The first acknowledgement stands; the incident row lock orders it against a resolution. */
  override def acknowledge(org: UUID, incidentId: UUID, by: UUID, at: Instant): ConnectionIO[IncidentAcknowledgement] =
    sql"""update incident set acknowledged_at = $at, acknowledged_by = $by, updated_at = greatest(updated_at, $at)
      where organization_id = $org and id = $incidentId and status = 'OPEN' and acknowledged_at is null
      returning id""".query[UUID].option.flatMap {
      case Some(_) => (IncidentAcknowledgement.Acknowledged: IncidentAcknowledgement).pure[ConnectionIO]
      case None =>
        sql"select status, acknowledged_at is not null from incident where organization_id = $org and id = $incidentId"
          .query[(String, Boolean)].option.map {
            case None => IncidentAcknowledgement.NotFound
            case Some((status, _)) if status != "OPEN" => IncidentAcknowledgement.NotOpen
            case Some(_) => IncidentAcknowledgement.AlreadyAcknowledged
          }
    }
}
