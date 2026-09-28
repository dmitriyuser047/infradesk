package ru.bitec.app.ops
package persistence.postgres

import application.port.{OrganizationProvisioning, OrganizationRepository}
import domain.organization.Organization
import cats.syntax.functor._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresOrganizationRepository extends OrganizationRepository[ConnectionIO]
  with OrganizationProvisioning[ConnectionIO] {

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

  override def createIfMissing(id: UUID, code: String, name: String, at: Instant): ConnectionIO[Unit] =
    sql"""
      insert into organization (id, code, name, is_active, created_at, updated_at)
      values ($id, $code, $name, true, $at, $at)
      on conflict do nothing
    """.update.run.void
}
