package ru.bitec.app.ops
package domain.metric

import java.time.{Duration, Instant}
import java.util.UUID

/** How finely a stretch of metric history is told: every observation, or one point per bucket. */
sealed abstract class MetricResolution(val code: String, val bucket: Option[Duration])
object MetricResolution {
  case object Raw extends MetricResolution("RAW", None)
  case object FiveMinutes extends MetricResolution("FIVE_MINUTES", Some(Duration.ofMinutes(5)))
  case object Hour extends MetricResolution("HOUR", Some(Duration.ofHours(1)))
  /** Read from hourly rollups; never stored. */
  case object Day extends MetricResolution("DAY", Some(Duration.ofDays(1)))

  /** The resolutions that are stored, in the order they are rolled up. */
  val Stored: List[MetricResolution] = List(FiveMinutes, Hour)

  /** The longest period one request may ask for. */
  val MaxPeriod: Duration = Duration.ofDays(400)

  /**
   * The finest resolution that keeps a chart readable: a few hundred points per metric at most.
   * Short periods show every observation; long ones read the rollups that outlive raw rows.
   */
  def forPeriod(from: Instant, to: Instant): MetricResolution = {
    val period = Duration.between(from, to)
    if (period.compareTo(Duration.ofHours(6)) <= 0) Raw
    else if (period.compareTo(Duration.ofDays(3)) <= 0) FiveMinutes
    else if (period.compareTo(Duration.ofDays(45)) <= 0) Hour
    else Day
  }
}

/** One point of a metric series: a single observation, or the summary of one bucket. */
final case class MetricSeriesPoint(
  resourceId: UUID,
  metricCode: MetricCode,
  bucketStart: Instant,
  average: BigDecimal,
  minimum: BigDecimal,
  maximum: BigDecimal,
  samples: Int
)
