package ru.bitec.app.ops
package domain.resource

import java.time.Instant
import java.util.UUID

final case class ResourceType(
                               id: UUID,
                               code: String,
                               name: String,
                               schemaVersion: Int,
                               capabilities: Set[String],
                               isActive: Boolean,
                               createdAt: Instant,
                               updatedAt: Instant
                             )