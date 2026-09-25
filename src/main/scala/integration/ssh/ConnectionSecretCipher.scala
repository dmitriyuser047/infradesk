package ru.bitec.app.ops
package integration.ssh

import application.port.{ConnectionSecret, ConnectionSecretCryptography}
import domain.connection.{SshAuthenticationType, SshCredential}
import integration.secret.AesGcmSecretEnvelope
import io.circe.syntax._
import io.circe.{Json, parser}

import java.util.UUID

/** Encrypts the whole credential, whatever kind it is.
  *
  * A password and a private key are the same thing to everything above this class: an opaque
  * payload that only the SSH transport ever sees in the clear. The encoded form carries its own
  * type, so a connection can never end up with an authentication method and a credential of
  * another kind.
  */
final class ConnectionSecretCipher private (envelope: AesGcmSecretEnvelope)
  extends ConnectionSecretCryptography {

  override def encrypt(id: UUID, organizationId: UUID, credential: SshCredential): ConnectionSecret = {
    val (nonce, ciphertext) =
      envelope.seal(id, organizationId, ConnectionSecretCipher.CredentialKind, encode(credential))
    ConnectionSecret(id, organizationId, ConnectionSecretCipher.CredentialKind, nonce, ciphertext)
  }

  override def decrypt(secret: ConnectionSecret): SshCredential = {
    val plaintext =
      envelope.open(secret.id, secret.organizationId, secret.kind, secret.nonce, secret.ciphertext)

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
}

object ConnectionSecretCipher {

  /** Secrets written before Stage 16: the ciphertext is a bare password. */
  val LegacyPasswordKind = "SSH_PASSWORD"
  val CredentialKind = "SSH_CREDENTIAL"

  def fromConfig(config: SecretEncryptionConfig): ConnectionSecretCipher =
    new ConnectionSecretCipher(new AesGcmSecretEnvelope(config.keyBytes))
}
