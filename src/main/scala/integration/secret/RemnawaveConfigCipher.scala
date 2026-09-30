package ru.bitec.app.ops
package integration.secret

import domain.configuration.CanonicalJson
import integration.ssh.SecretEncryptionConfig
import java.util.UUID

/** Persistence-only envelope. Neither the plaintext nor the encrypted bytes belong in profile DTOs. */
final case class RemnawaveSecurePayload(revisionId: UUID, organizationId: UUID, profileId: UUID,
  purpose: String, nonce: Array[Byte], ciphertext: Array[Byte], contentSha256: String)

final class RemnawaveConfigCipher private (envelope: AesGcmSecretEnvelope) {
  import RemnawaveConfigCipher._

  def encrypt(revisionId: UUID, organizationId: UUID, profileId: UUID,
    canonical: String): RemnawaveSecurePayload = {
    val (nonce, ciphertext) = envelope.seal(revisionId, organizationId, aadKind(profileId), canonical)
    RemnawaveSecurePayload(revisionId, organizationId, profileId, Purpose, nonce, ciphertext,
      CanonicalJson.sha256(canonical))
  }

  def decrypt(payload: RemnawaveSecurePayload): String = {
    if (payload.purpose != Purpose) throw new IllegalArgumentException("Stored configuration is not readable")
    val canonical = envelope.open(payload.revisionId, payload.organizationId, aadKind(payload.profileId),
      payload.nonce, payload.ciphertext)
    if (CanonicalJson.sha256(canonical) != payload.contentSha256)
      throw new IllegalArgumentException("Stored configuration is not readable")
    canonical
  }

  private def aadKind(profileId: UUID): String = s"$Purpose:$profileId"
}

object RemnawaveConfigCipher {
  val Purpose = "infradesk/configuration/remnawave/v1"
  def fromConfig(config: SecretEncryptionConfig): RemnawaveConfigCipher =
    new RemnawaveConfigCipher(new AesGcmSecretEnvelope(config.deriveSubkey(Purpose)))
}
