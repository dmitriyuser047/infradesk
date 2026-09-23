package ru.bitec.app.ops
package persistence.postgres

import application.navigation.EnvironmentContext
import application.port.NavigationQueryRepository
import cats.syntax.all._
import domain.enviroment.{Environment, EnvironmentKind}
import domain.project.Project
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresNavigationQueryRepository extends NavigationQueryRepository[ConnectionIO] {
  private final case class EnvironmentContextRow(
    projectId: UUID,
    projectOrganizationId: UUID,
    projectCode: String,
    projectName: String,
    projectDescription: Option[String],
    projectActive: Boolean,
    projectCreatedAt: Instant,
    projectUpdatedAt: Instant,
    environmentId: UUID,
    environmentOrganizationId: UUID,
    environmentProjectId: UUID,
    environmentCode: String,
    environmentName: String,
    environmentKind: String,
    environmentActive: Boolean,
    environmentCreatedAt: Instant,
    environmentUpdatedAt: Instant
  ) {
    def toDomain: Either[IllegalArgumentException, EnvironmentContext] =
      EnvironmentKind.fromCode(environmentKind).map { kind =>
        EnvironmentContext(
          Project(projectId, projectOrganizationId, projectCode, projectName, projectDescription,
            projectActive, projectCreatedAt, projectUpdatedAt),
          Environment(environmentId, environmentOrganizationId, environmentProjectId,
            environmentCode, environmentName, kind, environmentActive,
            environmentCreatedAt, environmentUpdatedAt)
        )
      }
  }

  override def findEnvironmentContext(
    organizationId: UUID,
    environmentId: UUID
  ): ConnectionIO[Option[EnvironmentContext]] =
    sql"""
      select
        p.id, p.organization_id, p.code, p.name, p.description,
        p.is_active, p.created_at, p.updated_at,
        e.id, e.organization_id, e.project_id, e.code, e.name, e.kind,
        e.is_active, e.created_at, e.updated_at
      from environment e
      join project p
        on p.id = e.project_id
       and p.organization_id = e.organization_id
      join organization o
        on o.id = e.organization_id
      where e.organization_id = $organizationId
        and e.id = $environmentId
        and e.is_active = true
        and p.is_active = true
        and o.is_active = true
    """.query[EnvironmentContextRow].option.flatMap {
      case Some(row) => row.toDomain.map(value => Option(value)).liftTo[ConnectionIO]
      case None => none[EnvironmentContext].pure[ConnectionIO]
    }
}
