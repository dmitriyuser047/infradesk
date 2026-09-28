package ru.bitec.app.ops
package infrastructure.http

import application.port.ReadinessCheck
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import infrastructure.database.DatabaseConfig
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

final class HealthRoutesSpec extends FunSuite {
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.health")

  test("health is public and does not query readiness") {
    var calls = 0
    val check = new ReadinessCheck[IO] { def check: IO[Unit] = IO { calls += 1 } }
    val response = new HealthRoutes(check, logger, build = HealthRoutes.Build("0.1.0", "abc123")).routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString("/health"))).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    val body = response.as[Json].unsafeRunSync().hcursor
    assertEquals(body.get[String]("status"), Right("UP"))
    // The baked build identity, so an installer can check the release it started.
    assertEquals((body.get[String]("version"), body.get[String]("gitSha")), (Right("0.1.0"), Right("abc123")))
    assertEquals(calls, 0)
  }

  test("ready reports successful database check") {
    var calls = 0
    val check = new ReadinessCheck[IO] { def check: IO[Unit] = IO { calls += 1 } }
    val response = new HealthRoutes(check, logger).routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString("/ready"))).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    assertEquals(response.as[Json].unsafeRunSync().hcursor.get[String]("status"), Right("READY"))
    assertEquals(calls, 1)
  }

  test("a database that never answers still gives readiness a controlled 503") {
    // A JDBC call blocked on a dead host cannot be interrupted, so the deadline has to apply to
    // waiting for the probe rather than to unwinding it. The pool may be configured to wait
    // minutes for ordinary work; readiness may not.
    val check = new ReadinessCheck[IO] {
      def check: IO[Unit] = IO.blocking(Thread.sleep(30000))
    }
    val routes = new HealthRoutes(check, logger, readinessTimeout = 100.milliseconds)
      .routes.orNotFound

    val started = System.nanoTime()
    val response = routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/ready")))
      .unsafeRunSync()
    val elapsed = (System.nanoTime() - started).nanos

    // The application answers, so the proxy never has to invent a gateway timeout.
    assertEquals(response.status, Status.ServiceUnavailable)
    assertEquals(response.as[Json].unsafeRunSync().hcursor.get[String]("status"), Right("NOT_READY"))
    assert(elapsed < 3.seconds, s"readiness took $elapsed")
    // Liveness is unaffected by a database that is not answering.
    assertEquals(
      routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/health"))).unsafeRunSync().status,
      Status.Ok
    )
  }

  test("the readiness budget is shorter than the pool it protects") {
    assert(HealthRoutes.DefaultReadinessTimeout < DatabaseConfig.DefaultConnectionTimeout)
    assertEquals(HealthRoutes.DefaultReadinessTimeout, 3.seconds)
  }

  test("ready hides database exception details") {
    val check = new ReadinessCheck[IO] {
      def check: IO[Unit] = IO.raiseError(new IllegalStateException("jdbc:secret-sensitive-detail"))
    }
    val response = new HealthRoutes(check, logger).routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString("/ready"))).unsafeRunSync()
    assertEquals(response.status, Status.ServiceUnavailable)
    val body = response.as[Json].unsafeRunSync()
    assertEquals(body.hcursor.get[String]("status"), Right("NOT_READY"))
    assert(!body.noSpaces.contains("secret-sensitive-detail"))
  }
}
