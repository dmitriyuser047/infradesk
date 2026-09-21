package ru.bitec.app.ops
package domain.resource

import java.time.Instant
import java.util.UUID

final case class Resource(
                           id: UUID,
                           organizationId: UUID,
                           environmentId: UUID,
                           resourceTypeId: UUID,
                           parentResourceId: Option[UUID],
                           code: String,
                           name: String,
                           isActive: Boolean,
                           createdAt: Instant,
                           updatedAt: Instant,
                           resourceTypeCode: String = "",
                           data: ResourceData = ResourceData.empty
                         )
