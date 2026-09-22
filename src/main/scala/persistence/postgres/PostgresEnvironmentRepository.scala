package ru.bitec.app.ops
package persistence.postgres

import application.port.EnvironmentRepository
import cats.syntax.all._
import domain.enviroment.{Environment, EnvironmentKind}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresEnvironmentRepository extends EnvironmentRepository[ConnectionIO] {

  private final case class EnvironmentRow(
    id: UUID,
    organizationId: UUID,
    projectId: UUID,
    code: String,
    name: String,
    kind: String,
    isActive: Boolean,
    createdAt: Instant,
    updatedAt: Instant
  ) {
    def toDomain: Either[IllegalArgumentException, Environment] =
      EnvironmentKind.fromCode(kind).map { environmentKind =>
        Environment(
          id,
          organizationId,
          projectId,
          code,
          name,
          environmentKind,
          isActive,
          createdAt,
          updatedAt
        )
      }
  }

  override def findActiveByProject(
    organizationId: UUID,
    projectId: UUID
  ): ConnectionIO[List[Environment]] =
    sql"""
      select id, organization_id, project_id, code, name, kind, is_active, created_at, updated_at
      from environment
      where organization_id = $organizationId
        and project_id = $projectId
        and is_active = true
      order by name asc, id asc
    """
      .query[EnvironmentRow]
      .to[List]
      .flatMap(
        _.traverse(_.toDomain.leftMap(error => error: Throwable)).liftTo[ConnectionIO]
      )
}
