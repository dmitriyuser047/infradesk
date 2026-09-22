package ru.bitec.app.ops
package integration.ssh

import application.port.ConnectionSecret

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.{Base64, UUID}
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

final class ConnectionSecretCipher private (key: Array[Byte]) {
  private val random = new SecureRandom()

  def encrypt(id: UUID, organizationId: UUID, password: String): ConnectionSecret = {
    val nonce = new Array[Byte](12)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad(id, organizationId))
    ConnectionSecret(id, organizationId, "SSH_PASSWORD", nonce,
      cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)))
  }

  def decrypt(secret: ConnectionSecret): String = {
    require(secret.kind == "SSH_PASSWORD", "Unsupported secret kind")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, secret.nonce))
    cipher.updateAAD(aad(secret.id, secret.organizationId))
    new String(cipher.doFinal(secret.ciphertext), StandardCharsets.UTF_8)
  }

  private def aad(id: UUID, organizationId: UUID): Array[Byte] =
    s"$id:$organizationId:SSH_PASSWORD".getBytes(StandardCharsets.UTF_8)
}

object ConnectionSecretCipher {
  def fromEnvironment(environment: Map[String, String]): Either[IllegalArgumentException, ConnectionSecretCipher] =
    environment.get("INFRADESK_SECRET_MASTER_KEY_BASE64").toRight(
      new IllegalArgumentException("INFRADESK_SECRET_MASTER_KEY_BASE64 is required")
    ).flatMap { value =>
      scala.util.Try(Base64.getDecoder.decode(value)).toEither.left.map(_ =>
        new IllegalArgumentException("INFRADESK_SECRET_MASTER_KEY_BASE64 must be Base64")
      ).flatMap { bytes =>
        if (bytes.length == 32) Right(new ConnectionSecretCipher(bytes))
        else Left(new IllegalArgumentException("INFRADESK_SECRET_MASTER_KEY_BASE64 must decode to 32 bytes"))
      }
    }
}
