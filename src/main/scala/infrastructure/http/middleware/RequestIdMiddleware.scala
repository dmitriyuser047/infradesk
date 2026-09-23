package ru.bitec.app.ops
package infrastructure.http.middleware

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.{Header, HttpApp, Request}
import org.typelevel.ci.CIString

import java.util.UUID

object RequestIdMiddleware {
  val HeaderName: CIString = CIString("X-Request-ID")
  private val ValidId = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}".r

  def apply(app: HttpApp[IO]): HttpApp[IO] = Kleisli { request: Request[IO] =>
    val supplied = request.headers.headers.find(_.name == HeaderName).map(_.value)
      .filter(value => ValidId.pattern.matcher(value).matches())
    IO(supplied.getOrElse(UUID.randomUUID().toString)).flatMap { requestId =>
      val header = Header.Raw(HeaderName, requestId)
      app.run(request.putHeaders(header)).map(_.putHeaders(header))
    }
  }
}
