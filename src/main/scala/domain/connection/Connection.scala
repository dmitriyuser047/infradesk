package ru.bitec.app.ops
package domain.connection

import java.time.Instant
import java.util.UUID

final case class Connection(
                             id: UUID,
                             organizationId: UUID,
                             scope: ConnectionScope,
                             connectorType: String,
                             code: String,
                             name: String,
                             config: ConnectionConfig,
                             secretRef: Option[String],
                             isActive: Boolean,
                             createdAt: Instant,
                             updatedAt: Instant
                           )
