package ru.bitec.app.ops
package domain.employee

import java.time.Instant
import java.util.UUID

final case class Employee (
                            id: UUID,
                            organizationId: UUID,
                            email: String,
                            displayName: String,
                            position: Option[String],
                            isActive: Boolean,
                            createdAt: Instant,
                            updatedAt: Instant
                          )
