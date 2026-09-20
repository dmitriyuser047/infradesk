package ru.bitec.app.ops
package domain.enviroment

import java.time.Instant
import java.util.UUID

sealed trait EnvironmentKind

object EnvironmentKind {
  case object Dev extends EnvironmentKind
  case object Test extends EnvironmentKind
  case object Stage extends EnvironmentKind
  case object Prod extends EnvironmentKind
  case object Custom extends EnvironmentKind
}

final case class Environment(
                              id: UUID,
                              organizationId: UUID,
                              projectId: UUID,
                              code: String,
                              name: String,
                              kind: EnvironmentKind,
                              isActive: Boolean,
                              createdAt: Instant,
                              updatedAt: Instant
                            )

