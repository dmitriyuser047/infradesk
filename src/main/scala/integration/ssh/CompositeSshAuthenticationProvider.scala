package ru.bitec.app.ops
package integration.ssh

import application.port.{ConnectionSecretRepository, TransactionRunner}
import cats.effect.IO
import domain.connection.{Connection, SecretRef}
import application.port.SshPasswordResolver

final class CompositeSshAuthenticationProvider[Tx[_]](
  secrets: ConnectionSecretRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  cipher: ConnectionSecretCipher,
  environmentSecrets: EnvironmentSecrets
) extends SshAuthenticationProvider[IO] with SshPasswordResolver[IO] {
  override def resolvePassword(connection: Connection): IO[String] =
    resolve(connection).flatMap {
      case SshAuthentication.Password(value) => IO.pure(value)
      case _ => IO.raiseError(new IllegalStateException("SSH credential is not a password"))
    }
  override def resolve(connection: Connection): IO[SshAuthentication] =
    connection.secretRef match {
      case None => IO.raiseError(new IllegalStateException("SSH credential is not configured"))
      case Some(raw) => SecretRef.parse(raw) match {
        case Left(error) => IO.raiseError(error)
        case Right(SecretRef.Environment(name)) =>
          IO.fromOption(environmentSecrets.get(name))(
            new IllegalStateException(s"SSH credential environment variable $name is not set")
          ).map(SshAuthentication.Password.apply)
        case Right(SecretRef.Database(id)) =>
          runner.run(secrets.find(connection.organizationId, id)).flatMap {
            case Some(secret) => IO(cipher.decrypt(secret)).map(SshAuthentication.Password.apply)
            case None => IO.raiseError(new IllegalStateException("SSH credential was not found"))
          }
      }
    }
}
