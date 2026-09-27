package ru.bitec.app.ops
package application.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

/** Turns a login identifier or a client source into the fixed-width, non-reversible key a throttle
  * row is stored under.
  *
  * The throttle table must not become a plaintext journal of who tried to log in and from where,
  * so the email and the source address are never stored raw: each is HMAC-SHA256'd under a
  * server-side subkey. The subkey is derived from the deployment's master secret, so the digests
  * cannot be recomputed from a stolen backup alone, and two deployments never share a key space.
  */
final class LoginThrottleHasher(subkey: Array[Byte]) {
  private val algorithm = "HmacSHA256"

  def hash(value: String): String = {
    val mac = Mac.getInstance(algorithm)
    mac.init(new SecretKeySpec(subkey, algorithm))
    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)).map(byte => f"${byte & 0xff}%02x").mkString
  }
}
