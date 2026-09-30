package ru.bitec.app.ops
package integration.secret

import integration.ssh.SecretEncryptionConfig
import munit.FunSuite

import java.util.{Base64, UUID}
import javax.crypto.AEADBadTagException

final class RemnawaveConfigCipherSpec extends FunSuite {
  private val master = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
  private val cipher = RemnawaveConfigCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(
    Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> master)).toOption.get)
  private val org = UUID.randomUUID()
  private val profile = UUID.randomUUID()
  private val revision = UUID.randomUUID()
  private val raw = """{"privateKey":"SUPER-SECRET-PRIVATE-KEY"}"""

  test("encrypted revision round trips and binds tenant, profile, revision and purpose") {
    val payload = cipher.encrypt(revision, org, profile, raw)
    assertEquals(cipher.decrypt(payload), raw)
    assert(!new String(payload.ciphertext, "UTF-8").contains("SUPER-SECRET-PRIVATE-KEY"))
    intercept[AEADBadTagException](cipher.decrypt(payload.copy(organizationId = UUID.randomUUID())))
    intercept[AEADBadTagException](cipher.decrypt(payload.copy(profileId = UUID.randomUUID())))
    intercept[AEADBadTagException](cipher.decrypt(payload.copy(revisionId = UUID.randomUUID())))
    intercept[IllegalArgumentException](cipher.decrypt(payload.copy(purpose = "wrong")))
    val tampered = payload.ciphertext.clone()
    tampered(0) = (tampered(0) ^ 1).toByte
    intercept[AEADBadTagException](cipher.decrypt(payload.copy(ciphertext = tampered)))
  }
}
