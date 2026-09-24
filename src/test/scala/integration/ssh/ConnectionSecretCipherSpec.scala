package ru.bitec.app.ops
package integration.ssh

import application.port.ConnectionSecret
import domain.connection.{SecretRef, SshCredential}
import munit.FunSuite

import java.util.{Base64, UUID}
import javax.crypto.AEADBadTagException

final class ConnectionSecretCipherSpec extends FunSuite {
  private val key = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
  private val cipher = ConnectionSecretCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key)).toOption.get)
  private val org = UUID.randomUUID()
  private val id = UUID.randomUUID()

  test("requires a Base64-encoded 32-byte master key") {
    assert(SecretEncryptionConfig.fromEnvironment(Map.empty).isLeft)
    assert(SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> "invalid")).isLeft)
    assert(SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(new Array[Byte](16)))).isLeft)
  }

  test("AES-GCM uses fresh nonces and binds ciphertext to tenant, id and kind") {
    val password = SshCredential.Password("very-secret")
    val first = cipher.encrypt(id, org, password)
    val second = cipher.encrypt(id, org, password)
    assertEquals(cipher.decrypt(first), password)
    assertEquals(first.kind, ConnectionSecretCipher.CredentialKind)
    assert(!first.nonce.sameElements(second.nonce))
    assert(!first.ciphertext.sameElements("very-secret".getBytes("UTF-8")))
    intercept[AEADBadTagException](cipher.decrypt(first.copy(organizationId = UUID.randomUUID())))
    intercept[AEADBadTagException](cipher.decrypt(first.copy(id = UUID.randomUUID())))
    intercept[AEADBadTagException](cipher.decrypt(first.copy(kind = "SSH_PASSWORD")))
    val corrupted = first.ciphertext.clone()
    corrupted(0) = (corrupted(0) ^ 1).toByte
    intercept[AEADBadTagException](cipher.decrypt(first.copy(ciphertext = corrupted)))
  }

  test("a private key and its passphrase survive the round trip as one credential") {
    val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nbody\n-----END OPENSSH PRIVATE KEY-----\n"
    val credential = SshCredential.PrivateKey(pem, Some("phrase"))

    val secret = cipher.encrypt(id, org, credential)

    assertEquals(cipher.decrypt(secret), credential)
    // Neither half is readable without the key.
    assert(!new String(secret.ciphertext, "UTF-8").contains("BEGIN OPENSSH"))
    assert(!new String(secret.ciphertext, "UTF-8").contains("phrase"))
    assertEquals(cipher.decrypt(cipher.encrypt(id, org,
      SshCredential.PrivateKey("-----BEGIN-----", None))),
      SshCredential.PrivateKey("-----BEGIN-----", None))
  }

  test("a credential never prints itself") {
    val password = SshCredential.Password("very-secret")
    val privateKey = SshCredential.PrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----", Some("unlock-me"))

    assert(!password.toString.contains("very-secret"))
    assert(!privateKey.toString.contains("BEGIN OPENSSH"))
    assert(!privateKey.toString.contains("unlock-me"))
    // Whether a passphrase exists is operational information; its value is not.
    assert(privateKey.toString.contains("passphrase=set"))
    assert(SshCredential.PrivateKey("pem", None).toString.contains("passphrase=none"))
    // Interpolating a whole credential, as a log line would, is also safe.
    assert(!s"credential=$privateKey".contains("BEGIN OPENSSH"))
    assert(!s"credential=$privateKey".contains("unlock-me"))
  }

  test("secrets written before typed credentials are still read as passwords") {
    // Exactly what V10 stored: the ciphertext is the password, and the kind says so.
    val legacy = legacyPasswordSecret("legacy-password")

    assertEquals(cipher.decrypt(legacy), SshCredential.Password("legacy-password"))
  }

  /** Reproduces the pre-Stage-16 encoding, whose payload was the bare password. */
  private def legacyPasswordSecret(password: String): ConnectionSecret = {
    val keyBytes = Base64.getDecoder.decode(key)
    val nonce = new Array[Byte](12)
    new java.security.SecureRandom().nextBytes(nonce)
    val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    aes.init(javax.crypto.Cipher.ENCRYPT_MODE,
      new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
      new javax.crypto.spec.GCMParameterSpec(128, nonce))
    aes.updateAAD(s"$id:$org:${ConnectionSecretCipher.LegacyPasswordKind}".getBytes("UTF-8"))
    ConnectionSecret(id, org, ConnectionSecretCipher.LegacyPasswordKind, nonce,
      aes.doFinal(password.getBytes("UTF-8")))
  }

  test("secret refs parse only valid env and db variants") {
    assertEquals(SecretRef.parse("db:" + id), Right(SecretRef.Database(id)))
    assertEquals(SecretRef.parse("env:SSH_PASSWORD"), Right(SecretRef.Environment("SSH_PASSWORD")))
    assert(SecretRef.parse("db:invalid").isLeft)
    assert(SecretRef.parse("env: ").isLeft)
    assert(SecretRef.parse("other:secret").isLeft)
  }
}
