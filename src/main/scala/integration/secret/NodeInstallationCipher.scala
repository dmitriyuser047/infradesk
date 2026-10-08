package ru.bitec.app.ops
package integration.secret

import application.port.{IntegrationSecret,NodeInstallationCryptography}
import domain.integration.NodeInstallationData
import integration.ssh.SecretEncryptionConfig
import java.util.UUID

/** Reuses authenticated InfraDesk encryption with a separate purpose/AAD. No plaintext persistence. */
final class NodeInstallationCipher private(envelope: AesGcmSecretEnvelope) extends NodeInstallationCryptography {
  override def encryptTls(id: UUID, org: UUID, material: domain.integration.NodeTlsMaterial): IntegrationSecret = {
    val payload=io.circe.Json.obj("certificate"->io.circe.Json.fromString(material.certificatePem),
      "privateKey"->io.circe.Json.fromString(material.privateKeyPem)).noSpaces
    val (nonce,ciphertext)=envelope.seal(id,org,"REMNAWAVE_NODE_TLS",payload)
    IntegrationSecret(id,org,"REMNAWAVE_NODE_TLS",nonce,ciphertext)
  }
  override def decryptTls(secret: IntegrationSecret): domain.integration.NodeTlsMaterial = {
    require(secret.kind=="REMNAWAVE_NODE_TLS")
    val payload=io.circe.parser.parse(envelope.open(secret.id,secret.organizationId,secret.kind,secret.nonce,secret.ciphertext)).toOption.get.hcursor
    new domain.integration.NodeTlsMaterial(payload.get[String]("certificate").toOption.get,payload.get[String]("privateKey").toOption.get)
  }
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
