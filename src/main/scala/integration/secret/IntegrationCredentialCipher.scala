package ru.bitec.app.ops
package integration.secret

import application.port.{IntegrationCryptography, IntegrationSecret}
import domain.integration.{IntegrationCredential, RemnawaveCredential}
import integration.secret.AesGcmSecretEnvelope
import integration.ssh.SecretEncryptionConfig
import io.circe.{Json, parser}
import java.util.UUID

/** Separate AAD and authenticated payload from SSH and notification credentials. */
final class IntegrationCredentialCipher private (envelope: AesGcmSecretEnvelope)
  extends IntegrationCryptography {
  import IntegrationCredentialCipher._

  override def encrypt(id: UUID, organizationId: UUID, credential: IntegrationCredential): IntegrationSecret = {
    val payload = credential match {
      case RemnawaveCredential(apiToken, caddyApiKey) => Json.obj(
        "version" -> Json.fromInt(1), "provider" -> Json.fromString("REMNAWAVE"),
        "apiToken" -> Json.fromString(apiToken),
        "caddyApiKey" -> caddyApiKey.fold[Json](Json.Null)(Json.fromString)
      ).noSpaces
    }
    val (nonce, ciphertext) = envelope.seal(id, organizationId, Kind, payload)
    IntegrationSecret(id, organizationId, Kind, nonce, ciphertext)
  }

  override def decrypt(secret: IntegrationSecret): IntegrationCredential = {
    if (secret.kind != Kind) throw unreadable
    val cursor = parser.parse(envelope.open(secret.id, secret.organizationId, secret.kind,
      secret.nonce, secret.ciphertext)).getOrElse(throw unreadable).hcursor
    if (cursor.get[Int]("version").toOption != Some(1) ||
      cursor.get[String]("provider").toOption != Some("REMNAWAVE")) throw unreadable
    val token = cursor.get[String]("apiToken").getOrElse(throw unreadable)
    val caddy = cursor.get[Option[String]]("caddyApiKey").getOrElse(throw unreadable)
    val credential = RemnawaveCredential(token, caddy)
    if (!credential.valid) throw unreadable
    credential
  }

  private def unreadable = new IllegalArgumentException("Stored integration credential is not readable")
}

object IntegrationCredentialCipher {
  val Kind = "INTEGRATION_CREDENTIAL"
  def fromConfig(config: SecretEncryptionConfig): IntegrationCredentialCipher =
    new IntegrationCredentialCipher(new AesGcmSecretEnvelope(config.keyBytes))
}

