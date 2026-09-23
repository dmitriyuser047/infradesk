package ru.bitec.app.ops
package application.workspace

import application.port.{EnvironmentRepository, IdGenerator, ProjectRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.enviroment.{Environment, EnvironmentKind}

import java.util.UUID

final class CreateEnvironment[Tx[_]: MonadThrow](
  projects: ProjectRepository[Tx],
  environments: EnvironmentRepository[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx]
) {
  def execute(organizationId: UUID, projectId: UUID, command: CreateEnvironmentCommand): Tx[Environment] =
    for {
      normalized <- WorkspaceValidation.codeAndName(command.code, command.name).liftTo[Tx]
      kind <- EnvironmentKind.fromCode(command.kind.trim)
        .leftMap(_ => WorkspaceManagementError("INVALID_REQUEST", "Invalid environment kind"))
        .liftTo[Tx]
      project <- projects.findActiveById(organizationId, projectId)
      _ <- project.liftTo[Tx](WorkspaceManagementError("PROJECT_NOT_FOUND", "Project was not found"))
      id <- ids.nextId
      now <- time.now
      environment = Environment(id, organizationId, projectId, normalized._1, normalized._2,
        kind, true, now, now)
      created <- environments.tryCreate(environment)
      _ <- if (created) MonadThrow[Tx].unit
        else MonadThrow[Tx].raiseError[Unit](WorkspaceManagementError(
          "ENVIRONMENT_CODE_ALREADY_EXISTS", "Environment code already exists"))
    } yield environment
}
