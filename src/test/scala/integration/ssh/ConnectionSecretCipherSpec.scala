package ru.bitec.app.ops
package integration.ssh

import application.port.ConnectionSecret
import domain.connection.SecretRef
import munit.FunSuite

import java.util.{Base64, UUID}
import javax.crypto.AEADBadTagException

final class ConnectionSecretCipherSpec extends FunSuite {
  private val key = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
  private val cipher = ConnectionSecretCipher.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key)).toOption.get
  private val org = UUID.randomUUID()
  private val id = UUID.randomUUID()

  test("requires a Base64-encoded 32-byte master key") {
    assert(ConnectionSecretCipher.fromEnvironment(Map.empty).isLeft)
    assert(ConnectionSecretCipher.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> "invalid")).isLeft)
    assert(ConnectionSecretCipher.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(new Array[Byte](16)))).isLeft)
  }

  test("AES-GCM uses fresh nonces and binds ciphertext to tenant, id and kind") {
    val first = cipher.encrypt(id, org, "very-secret")
    val second = cipher.encrypt(id, org, "very-secret")
    assertEquals(cipher.decrypt(first), "very-secret")
    assert(!first.nonce.sameElements(second.nonce))
    assert(!first.ciphertext.sameElements("very-secret".getBytes("UTF-8")))
    intercept[AEADBadTagException](cipher.decrypt(first.copy(organizationId = UUID.randomUUID())))
    intercept[AEADBadTagException](cipher.decrypt(first.copy(id = UUID.randomUUID())))
    intercept[IllegalArgumentException](cipher.decrypt(first.copy(kind = "OTHER")))
    val corrupted = first.ciphertext.clone()
    corrupted(0) = (corrupted(0) ^ 1).toByte
    intercept[AEADBadTagException](cipher.decrypt(first.copy(ciphertext = corrupted)))
  }

  test("secret refs parse only valid env and db variants") {
    assertEquals(SecretRef.parse("db:" + id), Right(SecretRef.Database(id)))
    assertEquals(SecretRef.parse("env:SSH_PASSWORD"), Right(SecretRef.Environment("SSH_PASSWORD")))
    assert(SecretRef.parse("db:invalid").isLeft)
    assert(SecretRef.parse("env: ").isLeft)
    assert(SecretRef.parse("other:secret").isLeft)
  }
}
