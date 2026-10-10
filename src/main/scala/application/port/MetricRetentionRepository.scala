package ru.bitec.app.ops
package application.port

import domain.metric.{MetricResolution, MetricSeriesPoint}

import java.time.Instant
import java.util.UUID

/** The upkeep of metric history. Every method runs in the caller's short transaction. */
trait MetricRetentionRepository[Tx[_]] {
  /** True when this transaction now holds the upkeep lock; another instance then skips its turn. */
  def tryLock: Tx[Boolean]

  /** How far a stored resolution has been rolled up, locked until the transaction ends. */
  def watermark(resolution: MetricResolution): Tx[Option[Instant]]

  /**
   * Summarises the raw rows of `[from, until)` into buckets of one resolution and moves its
   * watermark from `from` to `until`. Both ends are bucket-aligned, so every bucket is complete
   * and recomputing it gives the same row. False when the watermark was no longer `from`.
   */
  def rollUp(resolution: MetricResolution, from: Instant, until: Instant): Tx[Boolean]

  /** The lowest watermark: raw rows below it are summarised by every stored resolution. */
  def rolledUpUntil: Tx[Option[Instant]]

  /** Deletes up to `limit` raw observations older than `before`, oldest first. */
  def purgeObservations(before: Instant, limit: Int): Tx[Int]

  /** Deletes up to `limit` rollups of one resolution whose bucket started before `before`. */
  def purgeRollups(resolution: MetricResolution, before: Instant, limit: Int): Tx[Int]
}

/** Metric history of one resource as chart points at a chosen resolution. */
trait MetricSeriesQuery[Tx[_]] {
  /** Points of the given metrics in `[from, to)`, oldest first; buckets not yet rolled up are read from raw rows. */
  def series(organizationId: UUID, resourceId: UUID, resolution: MetricResolution,
    from: Instant, to: Instant, metricCodes: List[String]): Tx[List[MetricSeriesPoint]]
}
