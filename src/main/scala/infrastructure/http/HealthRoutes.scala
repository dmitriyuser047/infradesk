package ru.bitec.app.ops
package infrastructure.http

import application.port.ReadinessCheck
import cats.effect.IO
import cats.syntax.all._
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

final class HealthRoutes(readiness: ReadinessCheck[IO], logger: Logger[IO]) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> Json.fromString("UP")))

    case GET -> Root / "ready" =>
      readiness.check.attempt.flatMap {
        case Right(_) => Ok(Json.obj("status" -> Json.fromString("READY")))
        case Left(error) =>
          logger.error(error)(s"readiness.failed errorType=${error.getClass.getSimpleName}").handleErrorWith(_ => IO.unit) *>
            ServiceUnavailable(Json.obj("status" -> Json.fromString("NOT_READY")))
      }
  }
}
