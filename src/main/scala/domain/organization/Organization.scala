package ru.bitec.app.ops
package domain.organization

import java.time.Instant
import java.util.UUID

final case class Organization(
                               id: UUID,
                               code: String,
                               name: String,
                               isActive: Boolean,
                               createdAt: Instant,
                               updatedAt: Instant
                             )
