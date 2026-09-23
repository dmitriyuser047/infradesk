package ru.bitec.app.ops
package infrastructure.http.mapper

import domain.enviroment.Environment
import domain.organization.Organization
import domain.project.Project
import application.navigation.EnvironmentContext
import infrastructure.http.dto.{EnvironmentContextResponse, EnvironmentResponse, OrganizationResponse, ProjectResponse}

object NavigationHttpMapper {
  def organizationResponse(organization: Organization): OrganizationResponse =
    OrganizationResponse(organization.id, organization.code, organization.name)

  def projectResponse(project: Project): ProjectResponse =
    ProjectResponse(
      project.id,
      project.organizationId,
      project.code,
      project.name,
      project.description
    )

  def environmentResponse(environment: Environment): EnvironmentResponse =
    EnvironmentResponse(
      environment.id,
      environment.organizationId,
      environment.projectId,
      environment.code,
      environment.name,
      environment.kind.code
    )

  def environmentContextResponse(context: EnvironmentContext): EnvironmentContextResponse =
    EnvironmentContextResponse(projectResponse(context.project), environmentResponse(context.environment))
}
