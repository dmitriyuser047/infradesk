package ru.bitec.app.ops
package domain.metric

import java.time.Instant
import java.util.UUID

final case class MetricObservation(
                                    id: UUID,
                                    organizationId: UUID,
                                    resourceId: UUID,
                                    metricCode: MetricCode,
                                    value: BigDecimal,
                                    observedAt: Instant
                                  )
