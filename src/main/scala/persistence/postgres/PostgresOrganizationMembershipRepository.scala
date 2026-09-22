package ru.bitec.app.ops
package persistence.postgres

import application.port.{MyOrganization, OrganizationMembershipRepository}
import cats.syntax.all._
import domain.auth.{OrganizationMembership, OrganizationRole}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

final class PostgresOrganizationMembershipRepository extends OrganizationMembershipRepository[ConnectionIO] {
  override def hasActiveMembership(userId: UUID, organizationId: UUID): ConnectionIO[Boolean] =
    sql"""select exists (
              select 1 from organization_membership m
              join organization o on o.id = m.organization_id
              where m.user_id = $userId and m.organization_id = $organizationId
                and m.is_active = true and o.is_active = true
            )""".query[Boolean].unique

  override def listActiveOrganizations(userId: UUID): ConnectionIO[List[MyOrganization]] =
    sql"""select o.id, o.code, o.name, m.role
            from organization_membership m
            join organization o on o.id = m.organization_id
            where m.user_id = $userId and m.is_active = true and o.is_active = true
            order by o.name asc, o.id asc"""
      .query[(UUID, String, String, String)]
      .to[List]
      .flatMap(_.traverse { case (id, code, name, roleCode) =>
        OrganizationRole.fromCode(roleCode)
          .leftMap(error => error: Throwable)
          .map(MyOrganization(id, code, name, _))
          .liftTo[ConnectionIO]
      })

  override def createIfMissing(membership: OrganizationMembership): ConnectionIO[Unit] =
    sql"""insert into organization_membership
            (user_id, organization_id, role, is_active, created_at, updated_at)
            values (${membership.userId}, ${membership.organizationId}, ${membership.role.code},
                    ${membership.isActive}, ${membership.createdAt}, ${membership.updatedAt})
            on conflict (user_id, organization_id) do nothing""".update.run.void
}
