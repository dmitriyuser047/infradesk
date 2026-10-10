package ru.bitec.app.ops
package domain.metric

/** Latest device readings supplement the resource-wide observations used by monitoring.
  * Missing readings are absent, never substituted with zero.
  */
final case class TelemetryDevice(kind: String, name: String, metrics: Map[String, BigDecimal])
final case class ResourceTelemetry(
  metrics: Map[String, BigDecimal] = Map.empty,
  devices: List[TelemetryDevice] = Nil
)
