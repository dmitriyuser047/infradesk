package ru.bitec.app.ops
package domain.connection

import java.time.Instant
import java.util.UUID

final case class ConnectionSchedule(
                                     organizationId: UUID,
                                     connectionId: UUID,
                                     enabled: Boolean,
                                     intervalSeconds: Long,
                                     nextRunAt: Instant
                                   )
