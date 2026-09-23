package ru.bitec.app.ops
package application.workspace

import application.port.{IdGenerator, OrganizationRepository, ProjectRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.project.Project

import java.util.UUID

final class CreateProject[Tx[_]: MonadThrow](
  organizations: OrganizationRepository[Tx],
  projects: ProjectRepository[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx]
) {
  def execute(organizationId: UUID, command: CreateProjectCommand): Tx[Project] =
    for {
      normalized <- WorkspaceValidation.codeAndName(command.code, command.name).liftTo[Tx]
      organization <- organizations.findActiveById(organizationId)
      _ <- organization.liftTo[Tx](WorkspaceManagementError(
        "ORGANIZATION_NOT_FOUND", "Organization was not found"))
      id <- ids.nextId
      now <- time.now
      project = Project(id, organizationId, normalized._1, normalized._2,
        command.description.map(_.trim).filter(_.nonEmpty), true, now, now)
      created <- projects.tryCreate(project)
      _ <- if (created) MonadThrow[Tx].unit
        else MonadThrow[Tx].raiseError[Unit](WorkspaceManagementError(
          "PROJECT_CODE_ALREADY_EXISTS", "Project code already exists"))
    } yield project
}
