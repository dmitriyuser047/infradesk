package ru.bitec.app.ops
package integration.ssh

import application.port.{ConnectionSecret, ConnectionSecretCryptography}
import domain.connection.{SshAuthenticationType, SshCredential}
import io.circe.syntax._
import io.circe.{Json, parser}

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** Encrypts the whole credential, whatever kind it is.
  *
  * A password and a private key are the same thing to everything above this class: an opaque
  * payload that only the SSH transport ever sees in the clear. The encoded form carries its own
  * type, so a connection can never end up with an authentication method and a credential of
  * another kind.
  */
final class ConnectionSecretCipher private (key: Array[Byte]) extends ConnectionSecretCryptography {
  private val random = new SecureRandom()

  override def encrypt(id: UUID, organizationId: UUID, credential: SshCredential): ConnectionSecret = {
    val nonce = new Array[Byte](12)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad(id, organizationId, ConnectionSecretCipher.CredentialKind))
    ConnectionSecret(id, organizationId, ConnectionSecretCipher.CredentialKind, nonce,
      cipher.doFinal(encode(credential).getBytes(StandardCharsets.UTF_8)))
  }

  override def decrypt(secret: ConnectionSecret): SshCredential = {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, secret.nonce))
    cipher.updateAAD(aad(secret.id, secret.organizationId, secret.kind))
    val plaintext = new String(cipher.doFinal(secret.ciphertext), StandardCharsets.UTF_8)

    secret.kind match {
      // Written before credentials became typed: the payload is the password itself.
      case ConnectionSecretCipher.LegacyPasswordKind => SshCredential.Password(plaintext)
      case ConnectionSecretCipher.CredentialKind => decode(plaintext)
      case other => throw new IllegalArgumentException(s"Unsupported secret kind '$other'")
    }
  }

  private def encode(credential: SshCredential): String = credential match {
    case SshCredential.Password(value) =>
      Json.obj(
        "type" -> Json.fromString(SshAuthenticationType.Password.code),
        "password" -> Json.fromString(value)
      ).noSpaces
    case SshCredential.PrivateKey(pem, passphrase) =>
      Json.obj(
        "type" -> Json.fromString(SshAuthenticationType.PrivateKey.code),
        "privateKey" -> Json.fromString(pem),
        "passphrase" -> passphrase.asJson
      ).noSpaces
  }

  private def decode(payload: String): SshCredential = {
    val cursor = parser.parse(payload)
      .getOrElse(throw new IllegalArgumentException("Stored SSH credential is not readable"))
      .hcursor

    cursor.get[String]("type").flatMap(SshAuthenticationType.fromCode(_).left.map(io.circe.DecodingFailure.fromThrowable(_, Nil))) match {
      case Right(SshAuthenticationType.Password) =>
        SshCredential.Password(required(cursor.get[String]("password")))
      case Right(SshAuthenticationType.PrivateKey) =>
        SshCredential.PrivateKey(
          required(cursor.get[String]("privateKey")),
          cursor.get[Option[String]]("passphrase").toOption.flatten.filter(_.nonEmpty)
        )
      case Left(_) => throw new IllegalArgumentException("Stored SSH credential is not readable")
    }
  }

  private def required(value: Either[io.circe.DecodingFailure, String]): String =
    value.getOrElse(throw new IllegalArgumentException("Stored SSH credential is not readable"))

  private def aad(id: UUID, organizationId: UUID, kind: String): Array[Byte] =
    s"$id:$organizationId:$kind".getBytes(StandardCharsets.UTF_8)
}

object ConnectionSecretCipher {

  /** Secrets written before Stage 16: the ciphertext is a bare password. */
  val LegacyPasswordKind = "SSH_PASSWORD"
  val CredentialKind = "SSH_CREDENTIAL"

  def fromConfig(config: SecretEncryptionConfig): ConnectionSecretCipher =
    new ConnectionSecretCipher(config.keyBytes)
}
