package ru.bitec.app.ops
package domain.monitor

import java.time.Instant
import java.util.UUID

final case class MonitorRuleState(
                                   organizationId: UUID,
                                   monitorRuleId: UUID,
                                   status: MonitorRuleStatus,
                                   pendingSince: Option[Instant],
                                   updatedAt: Instant
                                 )
