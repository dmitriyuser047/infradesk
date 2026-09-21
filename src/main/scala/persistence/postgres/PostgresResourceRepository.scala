package ru.bitec.app.ops
package persistence.postgres

import application.port.ResourceRepository
import domain.resource.{Resource, ResourceData}

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

final class PostgresResourceRepository extends ResourceRepository[ConnectionIO] {

  private final case class ResourceRow(
                                        id: UUID,
                                        organizationId: UUID,
                                        environmentId: UUID,
                                        resourceTypeId: UUID,
                                        parentResourceId: Option[UUID],
                                        code: String,
                                        name: String,
                                        isActive: Boolean,
                                        createdAt: java.time.Instant,
                                        updatedAt: java.time.Instant,
                                        resourceTypeCode: String,
                                        specJson: String,
                                        statusJson: String
                                      ) {
    def toDomain(codec: ResourceDataJsonCodec): Either[IllegalArgumentException, Resource] =
      codec.decode(resourceTypeCode, specJson, statusJson).map { data =>
        Resource(
          id = id,
          organizationId = organizationId,
          environmentId = environmentId,
          resourceTypeId = resourceTypeId,
          parentResourceId = parentResourceId,
          code = code,
          name = name,
          isActive = isActive,
          createdAt = createdAt,
          updatedAt = updatedAt,
          resourceTypeCode = resourceTypeCode,
          data = data
        )
      }
  }

  private val resourceDataJsonCodec = new ResourceDataJsonCodec

  override def findById(organizationId: UUID, id: UUID): ConnectionIO[Option[Resource]] =
    sql"""
      select
        r.id,
        r.organization_id,
        r.environment_id,
        r.resource_type_id,
        r.parent_resource_id,
        r.code,
        r.name,
        r.is_active,
        r.created_at,
        r.updated_at,
        rt.code,
        r.spec::text,
        r.status::text
      from resource r
      join resource_type rt on rt.id = r.resource_type_id
      where r.organization_id = $organizationId
        and r.id = $id
    """
      .query[ResourceRow]
      .option
      .flatMap {
        case Some(row) =>
          row.toDomain(resourceDataJsonCodec).map(resource => Option(resource)).liftTo[ConnectionIO]
        case None =>
          none[Resource].pure[ConnectionIO]
      }


  override def save(resource: Resource): ConnectionIO[Unit] =
    resourceDataJsonCodec
      .encode(resource.resourceTypeCode, resource.data)
      .liftTo[ConnectionIO]
      .flatMap { encodedData =>
        sql"""
      insert into resource (
        id,
        organization_id,
        environment_id,
        resource_type_id,
        parent_resource_id,
        code,
        name,
        spec,
        status,
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
        cast(${encodedData.specJson} as jsonb),
        cast(${encodedData.statusJson} as jsonb),
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
        spec = excluded.spec,
        status = excluded.status,
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
