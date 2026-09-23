package ru.bitec.app.ops
package infrastructure.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import infrastructure.http.middleware.{HttpRequestLogging, RequestIdMiddleware}
import munit.FunSuite
import org.http4s.{Header, HttpRoutes, Method, Request, Status, Uri}
import org.http4s.dsl.io._
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

final class HttpRequestLoggingSpec extends FunSuite {
  test("logs the safe request context and redacts query, body and auth headers") {
    val logger = new RecordingLogger
      val routes = HttpRoutes.of[IO] { case POST -> Root / "probe" => Ok("done") }.orNotFound
      val app = RequestIdMiddleware(HttpRequestLogging(routes, logger))
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/probe?token=query-secret"))
        .putHeaders(Header.Raw(CIString("Authorization"), "Bearer header-secret"),
          Header.Raw(CIString("Cookie"), "session=cookie-secret"),
          Header.Raw(RequestIdMiddleware.HeaderName, "trace-42"))
        .withEntity("body-secret")
      val response = app.run(request).unsafeRunSync()
      assertEquals(response.status, Status.Ok)
      val event = logger.infos.find(_.startsWith("http.request.completed"))
        .getOrElse(fail("Missing HTTP request log"))
      assert(event.contains("requestId=trace-42 method=POST path=/probe status=200"))
      for (secret <- List("query-secret", "header-secret", "cookie-secret", "body-secret"))
        assert(!event.contains(secret))
  }

  private final class RecordingLogger extends Logger[IO] {
    var infos: List[String] = Nil
    override def info(message: => String): IO[Unit] = IO { infos = infos :+ message }
    override def error(message: => String): IO[Unit] = IO.unit
    override def warn(message: => String): IO[Unit] = IO.unit
    override def debug(message: => String): IO[Unit] = IO.unit
    override def trace(message: => String): IO[Unit] = IO.unit
    override def error(t: Throwable)(message: => String): IO[Unit] = IO.unit
    override def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
    override def info(t: Throwable)(message: => String): IO[Unit] = info(message)
    override def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
    override def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit
  }
}
