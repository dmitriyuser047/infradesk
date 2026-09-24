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

import scala.concurrent.duration._

/** Liveness and readiness, kept apart on purpose.
  *
  * `/health` says the process is alive and runs no query at all. `/ready` says this instance can
  * serve database-backed requests, and answers within its own deadline rather than the one the
  * connection pool happens to be configured with: an operator asking whether a node is ready
  * must get a controlled answer from the application, not a gateway timeout from the proxy.
  */
final class HealthRoutes(
  readiness: ReadinessCheck[IO],
  logger: Logger[IO],
  readinessTimeout: FiniteDuration = HealthRoutes.DefaultReadinessTimeout
) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> Json.fromString("UP")))

    case GET -> Root / "ready" =>
      readiness.check.timeout(readinessTimeout).attempt.flatMap {
        case Right(_) => Ok(Json.obj("status" -> Json.fromString("READY")))
        case Left(error) =>
          // JDBC exception messages can contain connection URLs. Readiness exposes and logs only
          // the failure class; operators can inspect PostgreSQL separately without leaking it.
          logger.error(s"readiness.failed errorType=${error.getClass.getSimpleName}")
            .handleErrorWith(_ => IO.unit) *>
            ServiceUnavailable(Json.obj("status" -> Json.fromString("NOT_READY")))
      }
  }
}

object HealthRoutes {

  /** One bounded attempt. It is deliberately shorter than the pool's connection timeout, which a
    * deployment may raise to minutes for ordinary work without making readiness wait that long.
    */
  val DefaultReadinessTimeout: FiniteDuration = 3.seconds
}
