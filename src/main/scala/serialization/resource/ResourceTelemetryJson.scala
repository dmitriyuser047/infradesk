package ru.bitec.app.ops
package serialization.resource

import domain.metric.{MetricCode, ResourceTelemetry, TelemetryDevice}
import io.circe.{Decoder, Encoder}

object ResourceTelemetryJson {
  private val values: Decoder[Map[String, BigDecimal]] = Decoder.decodeMap[String, BigDecimal].emap { readings =>
    Either.cond(readings.forall { case (code, value) =>
      MetricCode.fromCode(code).exists(metric => value >= 0 && (!metric.isPercentage || value <= 100))
    }, readings, "Invalid telemetry reading")
  }
  implicit val deviceEncoder: Encoder[TelemetryDevice] =
    Encoder.forProduct3("kind", "name", "metrics")(v => (v.kind, v.name, v.metrics))
  implicit val deviceDecoder: Decoder[TelemetryDevice] = Decoder.instance { c =>
    for {
      kind <- c.get[String]("kind")
      name <- c.get[String]("name")
      metrics <- c.downField("metrics").as(values)
    } yield TelemetryDevice(kind, name, metrics)
  }
  implicit val telemetryEncoder: Encoder[ResourceTelemetry] =
    Encoder.forProduct2("metrics", "devices")(v => (v.metrics, v.devices))
  implicit val telemetryDecoder: Decoder[ResourceTelemetry] = Decoder.instance { c =>
    for {
      metrics <- c.downField("metrics").as(values)
      devices <- c.get[List[TelemetryDevice]]("devices")
    } yield ResourceTelemetry(metrics, devices)
  }
}
