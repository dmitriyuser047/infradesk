package ru.bitec.app.ops
package integration.secret

import application.port.{IntegrationSecret,NodeInstallationCryptography}
import domain.integration.NodeInstallationData
import integration.ssh.SecretEncryptionConfig
import java.util.UUID

/** Reuses authenticated InfraDesk encryption with a separate purpose/AAD. No plaintext persistence. */
final class NodeInstallationCipher private(envelope: AesGcmSecretEnvelope) extends NodeInstallationCryptography {
  def encrypt(runId: UUID, org: UUID, data: NodeInstallationData): IntegrationSecret = {
    val (nonce, ciphertext) = envelope.seal(runId, org, NodeInstallationCipher.Kind, data.secretKey)
    IntegrationSecret(runId,org,NodeInstallationCipher.Kind,nonce,ciphertext)
  }
  def decrypt(secret: IntegrationSecret): NodeInstallationData = {
    require(secret.kind == NodeInstallationCipher.Kind,"Invalid installation credential kind")
    NodeInstallationData.fromSecretKey(envelope.open(secret.id,secret.organizationId,secret.kind,secret.nonce,secret.ciphertext))
  }
}
object NodeInstallationCipher {
  val Kind = "REMNAWAVE_NODE_INSTALLATION"
  def fromConfig(config: SecretEncryptionConfig): NodeInstallationCipher =
    new NodeInstallationCipher(new AesGcmSecretEnvelope(config.deriveSubkey("remnawave-node-installation-v1")))
}
