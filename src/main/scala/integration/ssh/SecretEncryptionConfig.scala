package ru.bitec.app.ops
package integration.ssh

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.util.Base64

final class SecretEncryptionConfig private (private val key: Array[Byte]) {
  // Shared by every secret cipher of the integration layer, and by nothing above it.
  private[integration] def keyBytes: Array[Byte] = key.clone()

  /** A purpose-separated 32-byte subkey derived from the master key by HMAC-SHA256 over a fixed
    * label. It lets a feature that is not a secret cipher — the login-throttle key hashing — use
    * the deployment's one configured secret without sharing the master key itself or adding a
    * second env var. Different labels yield independent keys.
    */
  def deriveSubkey(label: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(label.getBytes(StandardCharsets.UTF_8))
  }

  override def toString: String = "SecretEncryptionConfig(<redacted>)"
}

object SecretEncryptionConfig {
  def fromEnvironment(environment: Map[String, String]): Either[IllegalArgumentException, SecretEncryptionConfig] =
    environment.get("INFRADESK_SECRET_MASTER_KEY_BASE64").toRight(
      new IllegalArgumentException("INFRADESK_SECRET_MASTER_KEY_BASE64 is required")
    ).flatMap { value =>
      scala.util.Try(Base64.getDecoder.decode(value)).toEither.left.map(_ =>
        new IllegalArgumentException("INFRADESK_SECRET_MASTER_KEY_BASE64 must be Base64")
      ).flatMap { bytes =>
        if (bytes.length == 32) Right(new SecretEncryptionConfig(bytes))
        else Left(new IllegalArgumentException("INFRADESK_SECRET_MASTER_KEY_BASE64 must decode to 32 bytes"))
      }
    }
}
