package ru.bitec.app.ops
package infrastructure.http.middleware

import cats.data.Kleisli
import cats.effect.{Clock, IO}
import cats.syntax.all._
import org.http4s.{HttpApp, Request}
import org.typelevel.log4cats.Logger

object HttpRequestLogging {
  def apply(app: HttpApp[IO], logger: Logger[IO]): HttpApp[IO] = Kleisli { request: Request[IO] =>
    val requestId = request.headers.headers.find(_.name == RequestIdMiddleware.HeaderName)
      .map(_.value).getOrElse("unknown")
    val context = s"requestId=$requestId method=${request.method.name} path=${request.uri.path.renderString}"

    Clock[IO].monotonic.flatMap { started =>
      app.run(request).attempt.flatMap {
        case Right(response) =>
          Clock[IO].monotonic.flatMap { finished =>
            val event = s"http.request.completed $context status=${response.status.code} durationMs=${(finished - started).toMillis}"
            val log =
              if (response.status.code >= 500) logger.error(event)
              else if (response.status.code >= 400) logger.warn(event)
              else logger.info(event)
            log.handleErrorWith(_ => IO.unit).as(response)
          }
        case Left(error) =>
          Clock[IO].monotonic.flatMap { finished =>
            logger.error(s"http.request.failed $context status=500 durationMs=${(finished - started).toMillis} errorType=${error.getClass.getSimpleName}").handleErrorWith(_ => IO.unit) *>
              IO.raiseError(error)
          }
      }
    }
  }
}
