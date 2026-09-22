package ru.bitec.app.ops
package infrastructure.http.mapper
import domain.metric.MetricObservation
import infrastructure.http.dto.MetricObservationResponse
object MetricObservationHttpMapper { def toResponse(value: MetricObservation): MetricObservationResponse = MetricObservationResponse(value.metricCode.code, value.value, value.observedAt) }
