package ru.bitec.app.ops
package integration.ssh

import domain.connection.Connection

import cats.MonadThrow
import cats.syntax.all._
import ru.bitec.app.ops.integration.ssh.EnvironmentSshAuthenticationProvider.EnvironmentPrefix

final class EnvironmentSshAuthenticationProvider[F[_]: MonadThrow](environmentSecrets: EnvironmentSecrets)
  extends SshAuthenticationProvider[F] {

  override def resolve(
                        connection: Connection
                      ): F[SshAuthentication] =
    connection.secretRef match {
      case Some(secretRef) =>
        resolveEnvironmentVariable(
          connection,
          secretRef
        )

      case None =>
        new IllegalStateException(
          s"SSH connection ${connection.id} has no secretRef"
        ).raiseError[F, SshAuthentication]
    }

  private def resolveEnvironmentVariable(
                                          connection: Connection,
                                          secretRef: String
                                        ): F[SshAuthentication] =
    if (!secretRef.startsWith(EnvironmentPrefix)) {
      new IllegalStateException(
        s"SSH connection ${connection.id} has unsupported secretRef: $secretRef"
      ).raiseError[F, SshAuthentication]
    } else {
      val variableName =
        secretRef.stripPrefix(EnvironmentPrefix).trim

      if (variableName.isEmpty) {
        new IllegalStateException(
          s"SSH connection ${connection.id} has empty environment secret reference"
        ).raiseError[F, SshAuthentication]
      } else {
        environmentSecrets
          .get(variableName)
          .map[SshAuthentication] { password =>
            SshAuthentication.Password(password)
          }
          .liftTo[F](
            new IllegalStateException(
              s"Environment variable $variableName is not set"
            )
          )
      }
    }
}

object EnvironmentSshAuthenticationProvider {
  val EnvironmentPrefix = "env:"
}
