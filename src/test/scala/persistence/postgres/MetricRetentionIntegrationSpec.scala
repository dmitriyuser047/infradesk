package ru.bitec.app.ops
package persistence.postgres

import application.metric.{MetricRetention, MetricRetentionSettings}
import application.port.MetricRetentionRepository
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.metric.{MetricCode, MetricResolution}
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.log4cats.noop.NoOpLogger

import java.time.{Duration, Instant}
import java.util.UUID

/** The watermark is global, so every test owns a database of its own. */
final class MetricRetentionIntegrationSpec extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(120, "seconds")
  private val T0 = Instant.parse("2026-10-01T00:00:00Z")
  private val Cpu = MetricCode.CpuUsagePercent.code
  private val Memory = MetricCode.MemoryUsagePercent.code

  test("a rollup step summarises complete buckets once and moves the watermark by compare-and-set") {
    withWorld { w =>
      for {
        _ <- w.watermarks(T0)
        _ <- w.observe(Cpu, T0.plusSeconds(60), 10)
        _ <- w.observe(Cpu, T0.plusSeconds(180), 20)
        _ <- w.observe(Cpu, T0.plusSeconds(420), 30)
        _ <- w.observe(Cpu, T0.plusSeconds(3900), 40)
        _ <- w.observe(Cpu, T0.plusSeconds(7300), 99) // beyond the step: stays raw
        moved <- w.run(w.repo.rollUp(MetricResolution.FiveMinutes, T0, T0.plusSeconds(7200)))
        again <- w.run(w.repo.rollUp(MetricResolution.FiveMinutes, T0, T0.plusSeconds(7200)))
        hours <- w.run(w.repo.rollUp(MetricResolution.Hour, T0, T0.plusSeconds(7200)))
        rows <- w.rollups
      } yield {
        assert(moved && !again && hours)
        assertEquals(rows, List(
          ("FIVE_MINUTES", T0, 2, BigDecimal(10), BigDecimal(20), BigDecimal(15)),
          ("FIVE_MINUTES", T0.plusSeconds(300), 1, BigDecimal(30), BigDecimal(30), BigDecimal(30)),
          ("FIVE_MINUTES", T0.plusSeconds(3900), 1, BigDecimal(40), BigDecimal(40), BigDecimal(40)),
          ("HOUR", T0, 3, BigDecimal(10), BigDecimal(30), BigDecimal(20)),
          ("HOUR", T0.plusSeconds(3600), 1, BigDecimal(40), BigDecimal(40), BigDecimal(40))
        ))
      }
    }
  }

  test("a cycle catches up step by step, then deletes what outlived each retention and nothing else") {
    withWorld { w =>
      val now = T0.plus(Duration.ofDays(5)).plusSeconds(37 * 60)
      val settings = MetricRetentionSettings(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3))
      val retention = new MetricRetention[ConnectionIO](w.repo, w.runner, settings, NoOpLogger[IO])
      for {
        _ <- w.watermarks(T0)
        _ <- w.observe(Cpu, T0.plusSeconds(60), 50) // four days old: summarised, then deleted raw
        _ <- w.observe(Cpu, now.minus(Duration.ofHours(30)), 60) // older than a day
        _ <- w.observe(Cpu, now.minus(Duration.ofHours(2)), 70) // recent: stays raw
        _ <- retention.runOnce(now)
        marks <- w.run(sql"select resolution, rolled_up_until from metric_rollup_watermark order by 1".query[(String, Instant)].to[List])
        raw <- w.run(sql"select value from metric_observation order by observed_at".query[BigDecimal].to[List])
        rows <- w.rollups
      } yield {
        assertEquals(marks, List("FIVE_MINUTES" -> Instant.parse("2026-10-06T00:35:00Z"), "HOUR" -> Instant.parse("2026-10-06T00:00:00Z")))
        assertEquals(raw, List(BigDecimal(70)))
        // The four-day-old point is past both the five-minute (2 days) and hourly (3 days) retention.
        assert(!rows.exists(_._2 == T0), clues(rows))
        assertEquals(rows.filter(_._1 == "HOUR").map(_._6), List(BigDecimal(60), BigDecimal(70)))
      }
    }
  }

  test("raw rows that no rollup has summarised yet are kept, whatever their age") {
    withWorld { w =>
      val now = T0.plus(Duration.ofDays(10))
      // A rollup that never makes progress, for example because another instance holds the lock.
      val stuck = new MetricRetentionRepository[ConnectionIO] {
        def tryLock = w.repo.tryLock
        def watermark(resolution: MetricResolution) = w.repo.watermark(resolution)
        def rollUp(resolution: MetricResolution, from: Instant, until: Instant) = false.pure[ConnectionIO]
        def rolledUpUntil = w.repo.rolledUpUntil
        def purgeObservations(before: Instant, limit: Int) = w.repo.purgeObservations(before, limit)
        def purgeRollups(resolution: MetricResolution, before: Instant, limit: Int) = w.repo.purgeRollups(resolution, before, limit)
      }
      for {
        _ <- w.watermarks(T0)
        _ <- w.observe(Cpu, T0.plusSeconds(10), 1)
        _ <- new MetricRetention[ConnectionIO](stuck, w.runner, MetricRetentionSettings(), NoOpLogger[IO]).runOnce(now)
        raw <- w.run(sql"select count(*) from metric_observation".query[Long].unique)
      } yield assertEquals(raw, 1L)
    }
  }

  test("a series reads rollups below the watermark and raw rows above it, for this resource and known metrics only") {
    withWorld { w =>
      for {
        _ <- w.watermarks(T0.plusSeconds(600))
        _ <- w.observe(Cpu, T0.plusSeconds(60), 10)
        _ <- w.observe(Cpu, T0.plusSeconds(120), 30)
        _ <- w.run(w.repo.rollUp(MetricResolution.FiveMinutes, T0, T0.plusSeconds(600)).void).attempt // watermark is ahead: no-op
        _ <- w.rollup("FIVE_MINUTES", Cpu, T0, 2, 10, 30, 20)
        _ <- w.observe(Cpu, T0.plusSeconds(660), 50) // above the watermark: read raw
        _ <- w.observe(Memory, T0.plusSeconds(660), 5)
        _ <- w.observe("SOMETHING_NEWER", T0.plusSeconds(660), 7)
        _ <- w.observe(Cpu, T0.plusSeconds(700), 60, w.otherResource)
        points <- w.run(w.repo.series(w.org, w.resource, MetricResolution.FiveMinutes, T0, T0.plusSeconds(3600),
          MetricCode.All.map(_.code)))
        raw <- w.run(w.repo.series(w.org, w.resource, MetricResolution.Raw, T0, T0.plusSeconds(3600), List(Cpu)))
        foreign <- w.run(w.repo.series(UUID.randomUUID(), w.resource, MetricResolution.Raw, T0, T0.plusSeconds(3600), List(Cpu)))
      } yield {
        assertEquals(points.map(p => (p.metricCode.code, p.bucketStart, p.average, p.samples)), List(
          (Cpu, T0, BigDecimal(20), 2),
          (Cpu, T0.plusSeconds(600), BigDecimal(50), 1),
          (Memory, T0.plusSeconds(600), BigDecimal(5), 1)
        ))
        assertEquals(raw.map(_.average), List(BigDecimal(10), BigDecimal(30), BigDecimal(50)))
        assertEquals(foreign, Nil)
      }
    }
  }

  test("a day is summarised from its hours, each weighted by its samples") {
    withWorld { w =>
      for {
        _ <- w.watermarks(T0.plus(Duration.ofDays(2)))
        _ <- w.rollup("HOUR", Cpu, T0, 1, 10, 10, 10)
        _ <- w.rollup("HOUR", Cpu, T0.plusSeconds(3600), 3, 20, 40, 30)
        _ <- w.rollup("HOUR", Cpu, T0.plus(Duration.ofDays(1)), 2, 5, 5, 5)
        points <- w.run(w.repo.series(w.org, w.resource, MetricResolution.Day, T0, T0.plus(Duration.ofDays(2)), List(Cpu)))
      } yield assertEquals(points.map(p => (p.bucketStart, p.average, p.minimum, p.maximum, p.samples)), List(
        (T0, BigDecimal(25), BigDecimal(10), BigDecimal(40), 4),
        (T0.plus(Duration.ofDays(1)), BigDecimal(5), BigDecimal(5), BigDecimal(5), 2)
      ))
    }
  }

  private def withWorld(body: World => IO[Unit]): Unit = {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"), "PostgreSQL integration tests disabled")
    PostgresTestDatabase.isolatedTransactor(PostgresTestDatabase.config).use { xa =>
      val world = new World(xa)
      world.setUp *> body(world)
    }.unsafeRunSync()
  }

  private final class World(xa: Transactor[IO]) {
    val runner = new DoobieTransactionRunner(xa)
    val repo = new PostgresMetricRetentionRepository
    val org: UUID = UUID.randomUUID()
    val resource: UUID = UUID.randomUUID()
    val otherResource: UUID = UUID.randomUUID()
    private val environment = UUID.randomUUID()

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def setUp: IO[Unit] = run(for {
      _ <- sql"insert into organization (id, code, name) values ($org, ${org.toString}, 'Metrics')".update.run
      project = UUID.randomUUID()
      _ <- sql"insert into project (id, organization_id, code, name) values ($project, $org, 'p', 'P')".update.run
      _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($environment, $org, $project, 'e', 'E', 'PROD')".update.run
      _ <- List(resource, otherResource).traverse_(id =>
        sql"""insert into resource (id, organization_id, environment_id, resource_type_id, code, name)
          values ($id, $org, $environment, (select id from resource_type where code = 'NODE'), ${id.toString}, 'node')""".update.run)
    } yield ())

    def watermarks(at: Instant): IO[Unit] =
      run(sql"update metric_rollup_watermark set rolled_up_until = $at".update.run.void)

    def observe(code: String, at: Instant, value: BigDecimal, on: UUID = resource): IO[Unit] =
      run(sql"""insert into metric_observation (id, organization_id, resource_id, metric_code, value, observed_at)
        values (${UUID.randomUUID()}, $org, $on, $code, $value, $at)""".update.run.void)

    def rollup(resolution: String, code: String, at: Instant, count: Int, min: BigDecimal, max: BigDecimal, avg: BigDecimal): IO[Unit] =
      run(sql"""insert into metric_rollup (organization_id, resource_id, metric_code, resolution, bucket_start,
          sample_count, min_value, max_value, avg_value)
        values ($org, $resource, $code, $resolution, $at, $count, $min, $max, $avg)""".update.run.void)

    def rollups: IO[List[(String, Instant, Int, BigDecimal, BigDecimal, BigDecimal)]] =
      run(sql"""select resolution, bucket_start, sample_count, min_value, max_value, round(avg_value, 6)
        from metric_rollup where resource_id = $resource order by resolution, bucket_start"""
        .query[(String, Instant, Int, BigDecimal, BigDecimal, BigDecimal)].to[List])
  }
}
