package ru.bitec.app.ops
package infrastructure.http

import cats.effect.IO
import munit.FunSuite
import org.http4s.Request
import org.typelevel.ci.CIString

final class TerminalOriginSpec extends FunSuite {
  test("accepts exact same origin with normalized default port") {
    val request = Request[IO]().putHeaders(
      org.http4s.Header.Raw(CIString("Host"), "infra.example.test"),
      org.http4s.Header.Raw(CIString("Origin"), "https://infra.example.test:443"),
      org.http4s.Header.Raw(CIString("X-Forwarded-Proto"), "https")
    )
    assert(TerminalOrigin.sameOrigin(request))
  }

  test("rejects missing, malformed and cross-origin Origin values") {
    val host = org.http4s.Header.Raw(CIString("Host"), "infra.example.test")
    List(
      Request[IO]().putHeaders(host),
      Request[IO]().putHeaders(host, org.http4s.Header.Raw(CIString("Origin"), "null")),
      Request[IO]().putHeaders(host, org.http4s.Header.Raw(CIString("Origin"), "https://attacker.test")),
      Request[IO]().putHeaders(host, org.http4s.Header.Raw(CIString("Origin"), "https://infra.example.test/path"))
    ).foreach(request => assert(!TerminalOrigin.sameOrigin(request)))
  }

  test("does not trust a client supplied forwarded host") {
    val request = Request[IO]().putHeaders(
      org.http4s.Header.Raw(CIString("Host"), "infra.example.test"),
      org.http4s.Header.Raw(CIString("Origin"), "https://attacker.test"),
      org.http4s.Header.Raw(CIString("X-Forwarded-Host"), "attacker.test"),
      org.http4s.Header.Raw(CIString("X-Forwarded-Proto"), "https")
    )
    assert(!TerminalOrigin.sameOrigin(request))
  }
}
