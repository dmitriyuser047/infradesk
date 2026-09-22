package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
final case class MetricObservationResponse(metricCode: String, value: BigDecimal, observedAt: Instant)
