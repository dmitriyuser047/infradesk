package ru.bitec.app.ops
package persistence.postgres

import application.port.ResourceTypeRepository
import domain.resource.ResourceType

import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresResourceTypeRepository extends ResourceTypeRepository[ConnectionIO] {

  private final case class ResourceTypeRow(
                                            id: UUID,
                                            code: String,
                                            name: String,
                                            schemaVersion: Int,
                                            capabilities: List[String],
                                            isActive: Boolean,
                                            createdAt: Instant,
                                            updatedAt: Instant
                                          ) {
    def toDomain: ResourceType =
      ResourceType(
        id = id,
        code = code,
        name = name,
        schemaVersion = schemaVersion,
        capabilities = capabilities.toSet,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt
      )
  }

  override def findById(id: UUID): ConnectionIO[Option[ResourceType]] =
    sql"""
      select
        id,
        code,
        name,
        schema_version,
        capabilities,
        is_active,
        created_at,
        updated_at
      from resource_type
      where id = $id
    """
      .query[ResourceTypeRow]
      .option
      .map(_.map(_.toDomain))

  override def findByCode(code: String): ConnectionIO[Option[ResourceType]] =
    sql"""
      select
        id,
        code,
        name,
        schema_version,
        capabilities,
        is_active,
        created_at,
        updated_at
      from resource_type
      where lower(code) = lower($code)
    """
      .query[ResourceTypeRow]
      .option
      .map(_.map(_.toDomain))
}