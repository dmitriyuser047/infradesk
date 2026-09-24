package ru.bitec.app.ops
package integration.ssh

import application.port.{ConnectionSecretRepository, SshCredentialResolver, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.connection.{Connection, SecretRef, SshCredential}

final class CompositeSshAuthenticationProvider[Tx[_]](
  secrets: ConnectionSecretRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  cipher: ConnectionSecretCipher,
  environmentSecrets: EnvironmentSecrets
) extends SshAuthenticationProvider[IO] with SshCredentialResolver[IO] {

  override def resolveCredential(connection: Connection): IO[SshCredential] =
    credential(connection)

  override def resolve(connection: Connection): IO[SshAuthentication] =
    credential(connection).map(SshAuthentication.from)

  private def credential(connection: Connection): IO[SshCredential] =
    connection.secretRef match {
      case None => IO.raiseError(new IllegalStateException("SSH credential is not configured"))
      case Some(raw) => SecretRef.parse(raw) match {
        case Left(error) => IO.raiseError(error)
        case Right(SecretRef.Environment(name)) =>
          IO.fromOption(environmentSecrets.get(name))(
            new IllegalStateException(s"SSH credential environment variable $name is not set")
          ).map(SshCredential.Password.apply)
        case Right(SecretRef.Database(id)) =>
          runner.run(secrets.find(connection.organizationId, id)).flatMap {
            case Some(secret) => IO(cipher.decrypt(secret))
            case None => IO.raiseError(new IllegalStateException("SSH credential was not found"))
          }
      }
    }
}
