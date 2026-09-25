package ru.bitec.app.ops
package integration.notification

import application.port.NotificationChannelSecret
import domain.notification.NotificationChannelCredential
import integration.ssh.SecretEncryptionConfig
import munit.FunSuite

import java.util.{Base64, UUID}
import javax.crypto.AEADBadTagException

/** A channel credential is protected exactly like an SSH one: the same key, the same primitive,
  * the same binding to id, tenant and kind. What differs is the payload it encodes.
  */
final class NotificationChannelCipherSpec extends FunSuite {

  private val key = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
  private val cipher = NotificationChannelCipher.fromConfig(
    SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key))
      .toOption.get)
  private val org = UUID.randomUUID()
  private val id = UUID.randomUUID()

  test("a bot token survives the round trip and is bound to its row, tenant and kind") {
    val token = NotificationChannelCredential.TelegramBotToken("123456:very-secret-token")
    val first = cipher.encrypt(id, org, token)
    val second = cipher.encrypt(id, org, token)

    assertEquals(cipher.decrypt(first), token)
    assertEquals(first.kind, NotificationChannelCipher.CredentialKind)
    assert(!first.nonce.sameElements(second.nonce), "a nonce was reused")
    assert(!new String(first.ciphertext, "UTF-8").contains("very-secret-token"))
    intercept[AEADBadTagException](cipher.decrypt(first.copy(organizationId = UUID.randomUUID())))
    intercept[AEADBadTagException](cipher.decrypt(first.copy(id = UUID.randomUUID())))
    val corrupted = first.ciphertext.clone()
    corrupted(0) = (corrupted(0) ^ 1).toByte
    intercept[AEADBadTagException](cipher.decrypt(first.copy(ciphertext = corrupted)))
  }

  test("a webhook URL is a credential and round-trips as one") {
    val url = NotificationChannelCredential.WebhookUrl("https://hooks.example.test/t/secret-token")

    assertEquals(cipher.decrypt(cipher.encrypt(id, org, url)), url)
  }

  test("the stored payload knows which kind of credential it is") {
    val webhook = cipher.encrypt(id, org,
      NotificationChannelCredential.WebhookUrl("https://hooks.example.test/x"))
    val telegram = cipher.encrypt(id, org,
      NotificationChannelCredential.TelegramBotToken("123:abc"))

    // A webhook URL can never come back as a Telegram token, whatever the channel now claims.
    assert(cipher.decrypt(webhook).isInstanceOf[NotificationChannelCredential.WebhookUrl])
    assert(cipher.decrypt(telegram).isInstanceOf[NotificationChannelCredential.TelegramBotToken])
  }

  test("a secret of another kind is refused rather than guessed at") {
    val foreign = NotificationChannelSecret(id, org, "SSH_CREDENTIAL", new Array[Byte](12),
      new Array[Byte](16))

    val error = intercept[IllegalArgumentException](cipher.decrypt(foreign))
    assert(error.getMessage.contains("SSH_CREDENTIAL"))
  }
}
