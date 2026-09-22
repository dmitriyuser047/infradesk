package ru.bitec.app.ops
package application.auth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

final class SessionTokens {
  private val random = new SecureRandom()

  def generate(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  def hash(rawToken: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(rawToken.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
}
