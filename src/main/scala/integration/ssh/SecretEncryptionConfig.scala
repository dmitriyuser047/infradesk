package ru.bitec.app.ops
package integration.ssh

import java.util.Base64

final class SecretEncryptionConfig private (private val key: Array[Byte]) {
  private[ssh] def keyBytes: Array[Byte] = key.clone()
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
