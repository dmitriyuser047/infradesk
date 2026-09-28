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
  readinessTimeout: FiniteDuration = HealthRoutes.DefaultReadinessTimeout,
  build: HealthRoutes.Build = HealthRoutes.Build("unknown", "unknown")
) {
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // The build identity baked into the artifact, so an operator (or an installer) can check that
    // what runs is the release it installed. It names a public release, never configuration.
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> Json.fromString("UP"), "version" -> Json.fromString(build.version),
        "gitSha" -> Json.fromString(build.gitSha)))

    case GET -> Root / "ready" =>
      // The probe runs on its own fiber and the deadline applies to waiting for it, not to
      // unwinding it: a JDBC call already blocked on a dead host cannot be interrupted, and
      // waiting for it would hand the answer back to the proxy's timeout instead of ours.
      readiness.check.attempt.start.flatMap { probe =>
        probe.joinWithNever.timeout(readinessTimeout).attempt.flatMap {
          case Right(Right(_)) => Ok(Json.obj("status" -> Json.fromString("READY")))
          case Right(Left(error)) => notReady(error)
          case Left(error) => probe.cancel.start *> notReady(error)
        }
      }
  }

  /** JDBC exception messages can carry connection URLs. Readiness exposes and logs the failure
    * class only; operators inspect PostgreSQL itself without that reaching a client.
    */
  private def notReady(error: Throwable): IO[org.http4s.Response[IO]] =
    logger.error(s"readiness.failed errorType=${error.getClass.getSimpleName}")
      .handleErrorWith(_ => IO.unit) *>
      ServiceUnavailable(Json.obj("status" -> Json.fromString("NOT_READY")))
}

object HealthRoutes {

  final case class Build(version: String, gitSha: String)

  /** One bounded attempt. It is deliberately shorter than the pool's connection timeout, which a
    * deployment may raise to minutes for ordinary work without making readiness wait that long.
    */
  val DefaultReadinessTimeout: FiniteDuration = 3.seconds
}
