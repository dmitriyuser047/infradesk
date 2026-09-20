package ru.bitec.app.ops
package domain.resource

import java.time.Instant
import java.util.UUID

final case class ResourceRelation(
                             id: UUID,
                             organizationId: UUID,
                             fromResourceId: UUID,
                             relationType: String,
                             toResourceId: UUID,
                             createdAt: Instant
                           )
