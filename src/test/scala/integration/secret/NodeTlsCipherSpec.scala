package ru.bitec.app.ops
package integration.secret

import domain.integration.NodeTlsMaterial
import integration.ssh.SecretEncryptionConfig
import java.util.{Base64,UUID}
import munit.FunSuite

final class NodeTlsCipherSpec extends FunSuite {
  test("TLS encryption authenticates purpose, organization and version and redacts material") {
    val cipher=NodeInstallationCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(Map(
      "INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))).toOption.get)
    val material=new NodeTlsMaterial("certificate-fixture","private-key-fixture")
    val encrypted=cipher.encryptTls(UUID.randomUUID(),UUID.randomUUID(),material)
    assertEquals(cipher.decryptTls(encrypted).privateKeyPem,material.privateKeyPem)
    assert(!new String(encrypted.ciphertext,java.nio.charset.StandardCharsets.UTF_8).contains("private-key-fixture"))
    List(encrypted.copy(id=UUID.randomUUID()),encrypted.copy(organizationId=UUID.randomUUID()),
      encrypted.copy(kind=NodeInstallationCipher.Kind),encrypted.copy(ciphertext=encrypted.ciphertext.updated(0,(encrypted.ciphertext(0)^1).toByte)))
      .foreach(secret => intercept[Exception](cipher.decryptTls(secret)))
    assert(!material.toString.contains(material.privateKeyPem))
    intercept[Exception](cipher.decrypt(encrypted))
  }
}
