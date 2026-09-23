package ru.bitec.app.ops
package infrastructure.http.dto

import java.util.UUID

final case class OrganizationResponse(
  id: UUID,
  code: String,
  name: String
)

final case class ProjectResponse(
  id: UUID,
  organizationId: UUID,
  code: String,
  name: String,
  description: Option[String]
)

final case class EnvironmentResponse(
  id: UUID,
  organizationId: UUID,
  projectId: UUID,
  code: String,
  name: String,
  kind: String
)

final case class EnvironmentContextResponse(
  project: ProjectResponse,
  environment: EnvironmentResponse
)
