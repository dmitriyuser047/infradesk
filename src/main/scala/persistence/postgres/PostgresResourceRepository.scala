package ru.bitec.app.ops
package persistence.postgres

import application.port.ResourceRepository
import domain.resource.Resource

import cats.effect.IO
import cats.implicits.{catsSyntaxApplicativeErrorId, catsSyntaxApplicativeId}
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

final class PostgresResourceRepository extends ResourceRepository[ConnectionIO] {

  override def findById(organizationId: UUID, id: UUID): ConnectionIO[Option[Resource]] =
    sql"""
      select
        id,
        organization_id,
        environment_id,
        resource_type_id,
        parent_resource_id,
        code,
        name,
        is_active,
        created_at,
        updated_at
      from resource
      where organization_id = $organizationId
        and id = $id
    """
      .query[Resource]
      .option


  override def save(resource: Resource): ConnectionIO[Unit] =
    sql"""
      insert into resource (
        id,
        organization_id,
        environment_id,
        resource_type_id,
        parent_resource_id,
        code,
        name,
        is_active,
        created_at,
        updated_at
      )
      values (
        ${resource.id},
        ${resource.organizationId},
        ${resource.environmentId},
        ${resource.resourceTypeId},
        ${resource.parentResourceId},
        ${resource.code},
        ${resource.name},
        ${resource.isActive},
        ${resource.createdAt},
        ${resource.updatedAt}
      )
      on conflict (id)
      do update set
        environment_id = excluded.environment_id,
        resource_type_id = excluded.resource_type_id,
        parent_resource_id = excluded.parent_resource_id,
        code = excluded.code,
        name = excluded.name,
        is_active = excluded.is_active,
        updated_at = excluded.updated_at
      where resource.organization_id = excluded.organization_id
    """
      .update
      .run
      .flatMap {
        case 1 => ().pure[ConnectionIO]
        case rows =>
          new IllegalStateException(
            s"Expected to save 1 resource row, affected: $rows"
          ).raiseError[ConnectionIO, Unit]
      }

  override def deactivateIfExclusiveToConnection(
                                                   organizationId: UUID,
                                                   id: UUID,
                                                   connectionId: UUID,
                                                   now: java.time.Instant
                                                 ): ConnectionIO[Unit] =
    sql"""
      update resource r
      set is_active = false, updated_at = $now
      where r.organization_id = $organizationId
        and r.id = $id
        and r.is_active
        and not exists (
          select 1 from external_ref er
          where er.organization_id = r.organization_id
            and er.resource_id = r.id
            and er.connection_id <> $connectionId
        )
    """.update.run.map(_ => ())

}
