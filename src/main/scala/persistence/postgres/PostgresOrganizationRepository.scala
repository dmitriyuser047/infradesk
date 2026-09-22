package ru.bitec.app.ops
package persistence.postgres

import application.port.OrganizationRepository
import domain.organization.Organization
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresOrganizationRepository extends OrganizationRepository[ConnectionIO] {

  private final case class OrganizationRow(
    id: UUID,
    code: String,
    name: String,
    isActive: Boolean,
    createdAt: Instant,
    updatedAt: Instant
  ) {
    def toDomain: Organization =
      Organization(id, code, name, isActive, createdAt, updatedAt)
  }

  override def findActiveById(id: UUID): ConnectionIO[Option[Organization]] =
    sql"""
      select id, code, name, is_active, created_at, updated_at
      from organization
      where id = $id
        and is_active = true
    """
      .query[OrganizationRow]
      .option
      .map(_.map(_.toDomain))
}
