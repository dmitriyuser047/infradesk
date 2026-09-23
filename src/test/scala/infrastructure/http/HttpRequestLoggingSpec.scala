package ru.bitec.app.ops
package infrastructure.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import infrastructure.http.middleware.{HttpRequestLogging, RequestIdMiddleware}
import munit.FunSuite
import org.http4s.{Header, HttpRoutes, Method, Request, Status, Uri}
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory
import org.typelevel.ci.CIString
import org.typelevel.log4cats.slf4j.Slf4jLogger

final class HttpRequestLoggingSpec extends FunSuite {
  private val name = "infrastructure.http.test"
  private val logger = Slf4jLogger.getLoggerFromName[IO](name)

  test("logs the safe request context and redacts query, body and auth headers") {
    val underlying = LoggerFactory.getLogger(name).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    underlying.addAppender(appender)
    try {
      val routes = HttpRoutes.of[IO] { case POST -> Root / "probe" => Ok("done") }.orNotFound
      val app = RequestIdMiddleware(HttpRequestLogging(routes, logger))
      val request = Request[IO](Method.POST, Uri.unsafeFromString("/probe?token=query-secret"))
        .putHeaders(Header.Raw(CIString("Authorization"), "Bearer header-secret"),
          Header.Raw(CIString("Cookie"), "session=cookie-secret"),
          Header.Raw(RequestIdMiddleware.HeaderName, "trace-42"))
        .withEntity("body-secret")
      val response = app.run(request).unsafeRunSync()
      assertEquals(response.status, Status.Ok)
      val events = appender.list.toArray.map(_.asInstanceOf[ILoggingEvent])
      val event = events.find(_.getFormattedMessage.startsWith("http.request.completed"))
        .getOrElse(fail("Missing HTTP request log"))
      assertEquals(event.getLevel, Level.INFO)
      assert(event.getFormattedMessage.contains("requestId=trace-42 method=POST path=/probe status=200"))
      for (secret <- List("query-secret", "header-secret", "cookie-secret", "body-secret"))
        assert(!event.getFormattedMessage.contains(secret))
    } finally {
      underlying.detachAppender(appender)
      appender.stop()
    }
  }
}
