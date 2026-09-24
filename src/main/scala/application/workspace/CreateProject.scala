package ru.bitec.app.ops
package application.workspace

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{IdGenerator, OrganizationRepository, ProjectRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.project.Project

import java.util.UUID

final class CreateProject[Tx[_]: MonadThrow](
  organizations: OrganizationRepository[Tx],
  projects: ProjectRepository[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx],
  audit: AuditRecorder[Tx]
) {
  def execute(actor: ActorContext, command: CreateProjectCommand): Tx[Project] = {
    val organizationId = actor.organizationId
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
      // The journal entry belongs to this transaction: no project without its audit trail.
      _ <- audit.record(actor, AuditAction.ProjectCreated, AuditTargetType.Project, Some(project.id))
    } yield project
  }
}
