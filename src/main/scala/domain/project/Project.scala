package ru.bitec.app.ops
package domain.project

import java.time.Instant
import java.util.UUID

final case class Project(
                          id: UUID,
                          organizationId: UUID,
                          code: String,
                          name: String,
                          description: Option[String],
                          isActive: Boolean,
                          createdAt: Instant,
                          updatedAt: Instant
                        )
