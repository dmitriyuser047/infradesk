package ru.bitec.app.ops
package domain.connection

import java.util.UUID

sealed trait ConnectionScope {
  def code: String
}

object ConnectionScope {

  val OrganizationCode = "ORGANIZATION"
  val ProjectCode = "PROJECT"
  val EnvironmentCode = "ENVIRONMENT"

  case object Organization extends ConnectionScope {
    override val code: String = OrganizationCode
  }

  final case class Project(projectId: UUID) extends ConnectionScope {
    override val code: String = ProjectCode
  }

  final case class Environment(projectId: UUID, environmentId: UUID) extends ConnectionScope {
    override val code: String = EnvironmentCode
  }
}
