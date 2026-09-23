package ru.bitec.app.ops
package infrastructure.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import infrastructure.http.middleware.RequestIdMiddleware
import munit.FunSuite
import org.http4s.{Header, HttpRoutes, Method, Request, Status, Uri}
import org.http4s.dsl.io._

import java.util.UUID

final class RequestIdMiddlewareSpec extends FunSuite {
  private val app = RequestIdMiddleware(HttpRoutes.of[IO] {
    case request @ GET -> Root / "probe" =>
      Ok(request.headers.headers.find(_.name == RequestIdMiddleware.HeaderName).map(_.value).getOrElse("missing"))
  }.orNotFound)

  test("generates and echoes a request id when missing") {
    val response = app.run(Request[IO](Method.GET, Uri.unsafeFromString("/probe"))).unsafeRunSync()
    val requestId = response.headers.headers.find(_.name == RequestIdMiddleware.HeaderName)
      .map(_.value).getOrElse(fail("Missing X-Request-ID"))
    assertEquals(response.status, Status.Ok)
    assertEquals(UUID.fromString(requestId).toString, requestId)
    assertEquals(response.as[String].unsafeRunSync(), requestId)
  }

  test("preserves a valid client request id") {
    val value = "client-123.trace_4"
    val request = Request[IO](Method.GET, Uri.unsafeFromString("/probe"))
      .putHeaders(Header.Raw(RequestIdMiddleware.HeaderName, value))
    val response = app.run(request).unsafeRunSync()
    assertEquals(response.headers.headers.find(_.name == RequestIdMiddleware.HeaderName).map(_.value), Some(value))
    assertEquals(response.as[String].unsafeRunSync(), value)
  }

  test("replaces an invalid or oversized request id") {
    for (value <- List("bad id", "x" * 129)) {
      val request = Request[IO](Method.GET, Uri.unsafeFromString("/probe"))
        .putHeaders(Header.Raw(RequestIdMiddleware.HeaderName, value))
      val response = app.run(request).unsafeRunSync()
      val returned = response.headers.headers.find(_.name == RequestIdMiddleware.HeaderName)
        .map(_.value).getOrElse(fail("Missing X-Request-ID"))
      assertNotEquals(returned, value)
      assertEquals(UUID.fromString(returned).toString, returned)
    }
  }
}
