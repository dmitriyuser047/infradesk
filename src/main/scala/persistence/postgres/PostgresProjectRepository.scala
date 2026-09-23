package ru.bitec.app.ops
package persistence.postgres

import application.port.ProjectRepository
import domain.project.Project
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresProjectRepository extends ProjectRepository[ConnectionIO] {

  override def tryCreate(project: Project): ConnectionIO[Boolean] =
    sql"""insert into project (id, organization_id, code, name, description, is_active, created_at, updated_at)
           values (${project.id}, ${project.organizationId}, ${project.code}, ${project.name},
                   ${project.description}, ${project.isActive}, ${project.createdAt}, ${project.updatedAt})
           on conflict do nothing"""
      .update.run.map(_ == 1)

  private final case class ProjectRow(
    id: UUID,
    organizationId: UUID,
    code: String,
    name: String,
    description: Option[String],
    isActive: Boolean,
    createdAt: Instant,
    updatedAt: Instant
  ) {
    def toDomain: Project =
      Project(id, organizationId, code, name, description, isActive, createdAt, updatedAt)
  }

  override def findActiveByOrganization(organizationId: UUID): ConnectionIO[List[Project]] =
    sql"""
      select id, organization_id, code, name, description, is_active, created_at, updated_at
      from project
      where organization_id = $organizationId
        and is_active = true
      order by name asc, id asc
    """
      .query[ProjectRow]
      .to[List]
      .map(_.map(_.toDomain))

  override def findActiveById(
    organizationId: UUID,
    projectId: UUID
  ): ConnectionIO[Option[Project]] =
    sql"""
      select id, organization_id, code, name, description, is_active, created_at, updated_at
      from project
      where organization_id = $organizationId
        and id = $projectId
        and is_active = true
    """
      .query[ProjectRow]
      .option
      .map(_.map(_.toDomain))
}
