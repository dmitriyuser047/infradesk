package ru.bitec.app.ops
package application.port

import domain.project.Project

import java.util.UUID

trait ProjectRepository[F[_]] {
  def findActiveByOrganization(organizationId: UUID): F[List[Project]]

  def findActiveById(organizationId: UUID, projectId: UUID): F[Option[Project]]
}
