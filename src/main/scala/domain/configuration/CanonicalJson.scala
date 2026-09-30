package ru.bitec.app.ops
package domain.configuration

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** One deterministic representation for observed, stored and freshly fetched Xray JSON. */
object CanonicalJson {
  def render(value: Json): String = value.fold(
    "null",
    boolean => if (boolean) "true" else "false",
    number => number.toBigDecimal.map(_.bigDecimal.stripTrailingZeros.toPlainString).getOrElse(number.toString),
    string => Json.fromString(string).noSpaces,
    array => array.iterator.map(render).mkString("[", ",", "]"),
    obj => obj.keys.toList.sorted.map(key => s"${Json.fromString(key).noSpaces}:${render(obj(key).get)}")
      .mkString("{", ",", "}")
  )

  def sha256(value: Json): String = sha256(render(value))

  def sha256(canonical: String): String =
    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))
      .iterator.map(byte => f"${byte & 0xff}%02x").mkString
}
