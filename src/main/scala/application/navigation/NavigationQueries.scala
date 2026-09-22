package ru.bitec.app.ops
package application.navigation

import application.port.{EnvironmentRepository, OrganizationRepository, ProjectRepository}
import cats.Monad
import cats.syntax.all._
import domain.enviroment.Environment
import domain.organization.Organization
import domain.project.Project

import java.util.UUID

final case class GetOrganization[Tx[_]](
  organizationRepository: OrganizationRepository[Tx]
) {
  def execute(organizationId: UUID): Tx[Option[Organization]] =
    organizationRepository.findActiveById(organizationId)
}

final case class ListProjects[Tx[_]: Monad](
  organizationRepository: OrganizationRepository[Tx],
  projectRepository: ProjectRepository[Tx]
) {
  def execute(organizationId: UUID): Tx[Option[List[Project]]] =
    organizationRepository.findActiveById(organizationId).flatMap {
      case Some(_) => projectRepository.findActiveByOrganization(organizationId).map(Some(_))
      case None => none[List[Project]].pure[Tx]
    }
}

final case class ListEnvironments[Tx[_]: Monad](
  organizationRepository: OrganizationRepository[Tx],
  projectRepository: ProjectRepository[Tx],
  environmentRepository: EnvironmentRepository[Tx]
) {
  def execute(
    organizationId: UUID,
    projectId: UUID
  ): Tx[Option[List[Environment]]] =
    organizationRepository.findActiveById(organizationId).flatMap {
      case None => none[List[Environment]].pure[Tx]
      case Some(_) =>
        projectRepository.findActiveById(organizationId, projectId).flatMap {
          case Some(_) => environmentRepository.findActiveByProject(organizationId, projectId).map(Some(_))
          case None => none[List[Environment]].pure[Tx]
        }
    }
}
