package ru.bitec.app.ops
package application.metric

import domain.maintenance.{MaintenanceWindow, MaintenanceWindowState}
import domain.metric.MetricResolution
import munit.FunSuite

import java.time.{Duration, Instant}
import java.util.UUID

final class MetricRetentionSpec extends FunSuite {
  private val t = Instant.parse("2026-10-10T12:07:30Z")

  test("buckets are aligned to the epoch whatever the time of the request") {
    assertEquals(MetricRetention.bucketFloor(t, Duration.ofMinutes(5)), Instant.parse("2026-10-10T12:05:00Z"))
    assertEquals(MetricRetention.bucketFloor(t, Duration.ofHours(1)), Instant.parse("2026-10-10T12:00:00Z"))
    assertEquals(MetricRetention.bucketFloor(Instant.parse("2026-10-10T12:00:00Z"), Duration.ofHours(1)),
      Instant.parse("2026-10-10T12:00:00Z"))
  }

  test("a rollup step covers closed buckets only, never more than one span, and nothing when caught up") {
    // 12:07:30 less the two-minute lateness is 12:05:30; the open 12:05 bucket is not rolled up.
    assertEquals(MetricRetention.nextUntil(Instant.parse("2026-10-10T11:00:00Z"), t, MetricResolution.FiveMinutes),
      Some(Instant.parse("2026-10-10T12:05:00Z")))
    assertEquals(MetricRetention.nextUntil(Instant.parse("2026-10-10T12:05:00Z"), t, MetricResolution.FiveMinutes), None)
    // A week behind: six hours at a time.
    assertEquals(MetricRetention.nextUntil(Instant.parse("2026-10-03T12:00:00Z"), t, MetricResolution.FiveMinutes),
      Some(Instant.parse("2026-10-03T18:00:00Z")))
    assertEquals(MetricRetention.nextUntil(Instant.parse("2026-10-10T11:00:00Z"), t, MetricResolution.Hour),
      Some(Instant.parse("2026-10-10T12:00:00Z")))
    assertEquals(MetricRetention.nextUntil(Instant.parse("2026-10-10T12:00:00Z"), t, MetricResolution.Hour), None)
  }

  test("the period decides the resolution, a few hundred points per metric at most") {
    def at(hours: Long) = MetricResolution.forPeriod(t.minus(Duration.ofHours(hours)), t)
    assertEquals(at(1), MetricResolution.Raw)
    assertEquals(at(6), MetricResolution.Raw)
    assertEquals(at(7), MetricResolution.FiveMinutes)
    assertEquals(at(72), MetricResolution.FiveMinutes)
    assertEquals(at(73), MetricResolution.Hour)
    assertEquals(at(45 * 24), MetricResolution.Hour)
    assertEquals(at(45 * 24 + 1), MetricResolution.Day)
  }

  test("retention must keep raw rows at least a day and coarser history at least as long as finer") {
    intercept[IllegalArgumentException](MetricRetentionSettings(rawRetention = Duration.ofHours(12)))
    intercept[IllegalArgumentException](MetricRetentionSettings(rawRetention = Duration.ofDays(40)))
    intercept[IllegalArgumentException](MetricRetentionSettings(fiveMinuteRetention = Duration.ofDays(500)))
    MetricRetentionSettings()
  }

  test("a maintenance window is scheduled, active, finished, or cancelled before it started") {
    val window = MaintenanceWindow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
      t, t.plusSeconds(3600), "kernel upgrade", UUID.randomUUID(), t.minusSeconds(60), None, None)
    assertEquals(window.state(t.minusSeconds(1)), MaintenanceWindowState.Scheduled)
    assertEquals(window.state(t), MaintenanceWindowState.Active)
    assertEquals(window.state(t.plusSeconds(3600)), MaintenanceWindowState.Finished)
    val cut = window.copy(cancelledAt = Some(t.plusSeconds(600)), cancelledBy = Some(UUID.randomUUID()))
    assertEquals(cut.effectiveEnd, t.plusSeconds(600))
    assertEquals(cut.state(t.plusSeconds(601)), MaintenanceWindowState.Finished)
    assertEquals(window.copy(cancelledAt = Some(t.minusSeconds(30))).state(t.plusSeconds(10)), MaintenanceWindowState.Cancelled)
  }
}
