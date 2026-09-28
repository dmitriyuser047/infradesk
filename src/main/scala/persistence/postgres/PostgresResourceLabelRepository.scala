package ru.bitec.app.ops
package persistence.postgres

import application.port.{ResourceLabelRepository, ResourceLabelSet, ResourceLabelWrite}
import cats.syntax.all._
import domain.configuration.ResourceLabel
import org.typelevel.doobie.{ConnectionIO, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Label sets change as a whole by compare-and-set on a per-resource version row, so two editors never
  * silently overwrite each other.
  */
final class PostgresResourceLabelRepository extends ResourceLabelRepository[ConnectionIO] {

  override def find(organizationId: UUID, resourceId: UUID): ConnectionIO[Option[ResourceLabelSet]] =
    sql"""select coalesce(s.version, 0) from resource r
          left join resource_label_state s on s.resource_id = r.id and s.organization_id = r.organization_id
          where r.organization_id = $organizationId and r.id = $resourceId"""
      .query[Int].option.flatMap(_.traverse { version =>
        sql"""select key, value from resource_label
              where organization_id = $organizationId and resource_id = $resourceId order by key"""
          .query[ResourceLabel].to[List].map(ResourceLabelSet(resourceId, version, _))
      })

  override def replace(organizationId: UUID, resourceId: UUID, expectedVersion: Int, labels: List[ResourceLabel],
                       at: Instant): ConnectionIO[ResourceLabelWrite] =
    // The resource row is locked first, so the version check and the replacement are one step. NO KEY
    // UPDATE: it excludes other label writers and adoptions (which share-lock the row), but not the
    // foreign-key checks of inserts that reference the resource.
    sql"""select coalesce(s.version, 0) from resource r
          left join resource_label_state s on s.resource_id = r.id and s.organization_id = r.organization_id
          where r.organization_id = $organizationId and r.id = $resourceId for no key update of r"""
      .query[Int].option.flatMap {
        case None => (ResourceLabelWrite.ResourceMissing: ResourceLabelWrite).pure[ConnectionIO]
        case Some(current) if current != expectedVersion => (ResourceLabelWrite.Stale: ResourceLabelWrite).pure[ConnectionIO]
        case Some(current) =>
          val next = current + 1
          for {
            _ <- sql"""insert into resource_label_state (organization_id, resource_id, version, updated_at)
                       values ($organizationId, $resourceId, $next, $at)
                       on conflict (resource_id) do update set version = excluded.version, updated_at = excluded.updated_at"""
              .update.run
            _ <- sql"delete from resource_label where organization_id = $organizationId and resource_id = $resourceId".update.run
            _ <- Update[(UUID, UUID, String, String, Instant, Instant)]("""insert into resource_label
                   (organization_id, resource_id, key, value, created_at, updated_at) values (?, ?, ?, ?, ?, ?)""")
              .updateMany(labels.map(label => (organizationId, resourceId, label.key, label.value, at, at)))
            // Matches may have changed: every enabled rule of the organization looks again soon.
            _ <- sql"""update configuration_assignment_rule set next_reconcile_at = $at
                       where organization_id = $organizationId and enabled and not archived
                         and (next_reconcile_at is null or next_reconcile_at > $at)""".update.run
          } yield ResourceLabelWrite.Written(next): ResourceLabelWrite
      }
}
