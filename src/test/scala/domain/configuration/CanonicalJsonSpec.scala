package ru.bitec.app.ops
package domain.configuration

import io.circe.parser.parse
import munit.FunSuite

final class CanonicalJsonSpec extends FunSuite {
  private def json(value: String) = parse(value).toOption.get

  test("object order, whitespace and equivalent numbers have one canonical hash") {
    val first = json("""{"a":1,"b":2.0,"nested":{"x":true,"y":null}}""")
    val second = json(""" { "nested": { "y": null, "x": true }, "b": 2, "a": 1.0 } """)
    assertEquals(CanonicalJson.render(first), CanonicalJson.render(second))
    assertEquals(CanonicalJson.sha256(first), CanonicalJson.sha256(second))
    assertEquals(CanonicalJson.sha256(first).length, 64)
  }

  test("array order remains significant") {
    assertNotEquals(CanonicalJson.sha256(json("""{"a":[1,2]}""")),
      CanonicalJson.sha256(json("""{"a":[2,1]}""")))
  }

  test("a large exponent has a bounded canonical representation") {
    val rendered = CanonicalJson.render(json("""{"x":1e100000000}"""))
    assert(rendered.length < 100)
    assert(rendered.contains("E+100000000"))
  }
}
