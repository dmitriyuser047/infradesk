package ru.bitec.app.ops
package integration.ssh

import application.port.{ConnectionSecret, ConnectionSecretCryptography}

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

final class ConnectionSecretCipher private (key: Array[Byte]) extends ConnectionSecretCryptography {
  private val random = new SecureRandom()

  override def encrypt(id: UUID, organizationId: UUID, password: String): ConnectionSecret = {
    val nonce = new Array[Byte](12)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad(id, organizationId))
    ConnectionSecret(id, organizationId, "SSH_PASSWORD", nonce,
      cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)))
  }

  override def decrypt(secret: ConnectionSecret): String = {
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
  def fromConfig(config: SecretEncryptionConfig): ConnectionSecretCipher =
    new ConnectionSecretCipher(config.keyBytes)
}
