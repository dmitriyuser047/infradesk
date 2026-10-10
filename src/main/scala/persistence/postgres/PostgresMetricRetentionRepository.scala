package ru.bitec.app.ops
package persistence.postgres

import application.port.{MetricRetentionRepository, MetricSeriesQuery}
import cats.syntax.all._
import domain.metric.{MetricCode, MetricResolution, MetricSeriesPoint}
import org.typelevel.doobie.{ConnectionIO, Fragment}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Rollup, retention and series reads: each a fixed number of set-based statements. */
final class PostgresMetricRetentionRepository extends MetricRetentionRepository[ConnectionIO] with MetricSeriesQuery[ConnectionIO] {
  import PostgresMetricRetentionRepository._

  // One instance at a time does the upkeep; the work is idempotent, the lock only avoids waste.
  override def tryLock: ConnectionIO[Boolean] =
    sql"select pg_try_advisory_xact_lock(hashtextextended('infradesk.metric-retention', 0))".query[Boolean].unique

  override def watermark(resolution: MetricResolution): ConnectionIO[Option[Instant]] =
    sql"""select rolled_up_until from metric_rollup_watermark
      where resolution = ${resolution.code} for update""".query[Instant].option

  override def rollUp(resolution: MetricResolution, from: Instant, until: Instant): ConnectionIO[Boolean] =
    sql"""update metric_rollup_watermark set rolled_up_until = $until
      where resolution = ${resolution.code} and rolled_up_until = $from""".update.run.flatMap {
      case 1 => (fr"""insert into metric_rollup
          (organization_id, resource_id, metric_code, resolution, bucket_start, sample_count, min_value, max_value, avg_value)
        select organization_id, resource_id, metric_code, ${resolution.code},""" ++ bucketOf(resolution, fr"observed_at") ++ fr""",
          count(*), min(value), max(value), avg(value)
        from metric_observation
        where observed_at >= $from and observed_at < $until
        group by organization_id, resource_id, metric_code, 5
        on conflict (organization_id, resource_id, metric_code, resolution, bucket_start) do update set
          sample_count = excluded.sample_count, min_value = excluded.min_value,
          max_value = excluded.max_value, avg_value = excluded.avg_value""").update.run.as(true)
      case _ => false.pure[ConnectionIO]
    }

  override def rolledUpUntil: ConnectionIO[Option[Instant]] =
    sql"""select min(rolled_up_until) from metric_rollup_watermark
      where resolution in (${MetricResolution.FiveMinutes.code}, ${MetricResolution.Hour.code})"""
      .query[Option[Instant]].unique

  override def purgeObservations(before: Instant, limit: Int): ConnectionIO[Int] =
    sql"""delete from metric_observation where id in (
        select id from metric_observation where observed_at < $before order by observed_at limit $limit
      )""".update.run

  override def purgeRollups(resolution: MetricResolution, before: Instant, limit: Int): ConnectionIO[Int] =
    sql"""delete from metric_rollup where ctid = any(array(
        select ctid from metric_rollup
        where resolution = ${resolution.code} and bucket_start < $before limit $limit
      ))""".update.run

  /**
   * Rolled-up buckets below the watermark and buckets computed from raw rows above it, merged
   * into the requested resolution. A day is summarised from hours, weighting each by its samples.
   */
  override def series(organizationId: UUID, resourceId: UUID, resolution: MetricResolution,
    from: Instant, to: Instant, metricCodes: List[String]): ConnectionIO[List[MetricSeriesPoint]] = {
    val codes = metricCodes.toArray
    val query = resolution match {
      case MetricResolution.Raw =>
        sql"""select metric_code, observed_at, value, value, value, 1 from metric_observation
          where organization_id = $organizationId and resource_id = $resourceId
            and observed_at >= $from and observed_at < $to and metric_code = any($codes)
          order by observed_at, metric_code, id"""
      case _ =>
        val stored = if (resolution == MetricResolution.Day) MetricResolution.Hour else resolution
        fr"""with mark as (
            select coalesce((select rolled_up_until from metric_rollup_watermark where resolution = ${stored.code}),
              timestamptz '-infinity') as until
          ), combined as (
            select metric_code, bucket_start, avg_value, min_value, max_value, sample_count
            from metric_rollup, mark
            where organization_id = $organizationId and resource_id = $resourceId and resolution = ${stored.code}
              and bucket_start >= """ ++ bucketOf(stored, fr"$from") ++ fr""" and bucket_start < $to
              and bucket_start < mark.until and metric_code = any($codes)
            union all
            select metric_code, """ ++ bucketOf(stored, fr"observed_at") ++ fr""", avg(value), min(value), max(value), count(*)
            from metric_observation, mark
            where organization_id = $organizationId and resource_id = $resourceId
              and observed_at >= greatest(""" ++ bucketOf(stored, fr"$from") ++ fr""", mark.until) and observed_at < $to
              and metric_code = any($codes)
            group by 1, 2
          )
          select metric_code, """ ++ bucketOf(resolution, fr"bucket_start") ++ fr""" as bucket,
            sum(avg_value * sample_count) / sum(sample_count), min(min_value), max(max_value), sum(sample_count)::int
          from combined group by 1, 2 order by 2, 1"""
    }
    query.query[(String, Instant, BigDecimal, BigDecimal, BigDecimal, Int)].to[List].flatMap(_.traverse {
      case (code, bucket, average, minimum, maximum, samples) =>
        MetricCode.fromCode(code).map(MetricSeriesPoint(resourceId, _, bucket, average, minimum, maximum, samples))
          .liftTo[ConnectionIO]
    })
  }
}

object PostgresMetricRetentionRepository {
  /** Buckets are aligned to a fixed UTC origin, so they never depend on the session time zone. */
  private def bucketOf(resolution: MetricResolution, value: Fragment): Fragment = {
    val width = resolution match {
      case MetricResolution.FiveMinutes => "5 minutes"
      case MetricResolution.Hour => "1 hour"
      case MetricResolution.Day => "1 day"
      case MetricResolution.Raw => throw new IllegalArgumentException("Raw observations have no buckets")
    }
    fr"date_bin(cast($width as interval)," ++ value ++ fr", timestamptz '2000-01-01 00:00:00+00')"
  }
}
