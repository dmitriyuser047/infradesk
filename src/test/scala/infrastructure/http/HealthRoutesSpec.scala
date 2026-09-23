package ru.bitec.app.ops
package infrastructure.http

import application.port.ReadinessCheck
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import org.typelevel.log4cats.slf4j.Slf4jLogger

final class HealthRoutesSpec extends FunSuite {
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.health")

  test("health is public and does not query readiness") {
    var calls = 0
    val check = new ReadinessCheck[IO] { def check: IO[Unit] = IO { calls += 1 } }
    val response = new HealthRoutes(check, logger).routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString("/health"))).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    assertEquals(response.as[Json].unsafeRunSync().hcursor.get[String]("status"), Right("UP"))
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
