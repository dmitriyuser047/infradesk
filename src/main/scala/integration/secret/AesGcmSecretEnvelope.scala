package ru.bitec.app.ops
package integration.secret

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** The one encryption primitive of the application: AES-GCM with a fresh nonce per secret.
  *
  * The owner of a secret — its id, its organization and the kind of payload it holds — is
  * authenticated as additional data, so a ciphertext cannot be moved to another row, another
  * tenant or another kind of secret and still decrypt.
  *
  * Every kind of stored secret goes through this class. There is no second cipher and no second
  * key: what differs between an SSH credential and a notification channel credential is the
  * payload that is encoded, not how it is protected.
  */
final class AesGcmSecretEnvelope(key: Array[Byte]) {

  private val random = new SecureRandom()

  /** Returns the fresh nonce together with the ciphertext it belongs to. */
  def seal(id: UUID, organizationId: UUID, kind: String, plaintext: String): (Array[Byte], Array[Byte]) = {
    val nonce = new Array[Byte](AesGcmSecretEnvelope.NonceLength)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance(AesGcmSecretEnvelope.Transformation)
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
      new GCMParameterSpec(AesGcmSecretEnvelope.TagBits, nonce))
    cipher.updateAAD(aad(id, organizationId, kind))
    (nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)))
  }

  def open(
    id: UUID,
    organizationId: UUID,
    kind: String,
    nonce: Array[Byte],
    ciphertext: Array[Byte]
  ): String = {
    val cipher = Cipher.getInstance(AesGcmSecretEnvelope.Transformation)
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
      new GCMParameterSpec(AesGcmSecretEnvelope.TagBits, nonce))
    cipher.updateAAD(aad(id, organizationId, kind))
    new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
  }

  private def aad(id: UUID, organizationId: UUID, kind: String): Array[Byte] =
    s"$id:$organizationId:$kind".getBytes(StandardCharsets.UTF_8)
}

object AesGcmSecretEnvelope {
  val NonceLength: Int = 12
  private val TagBits: Int = 128
  private val Transformation: String = "AES/GCM/NoPadding"
}
