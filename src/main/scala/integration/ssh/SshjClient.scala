package ru.bitec.app.ops
package integration.ssh

import cats.effect.Sync

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.{PasswordFinder, PasswordUtils}

import java.nio.charset.StandardCharsets
import java.util.concurrent.{TimeUnit, TimeoutException}
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier

import java.security.PublicKey
import java.util.{Collections, List => JList}
import java.util.concurrent.atomic.AtomicReference

final class SshjClient[F[_]: Sync] extends SshClient[F] {

  override def execute(
                        config: SshConnectionConfig,
                        authentication: SshAuthentication,
                        command: String
                      ): F[SshCommandResult] =
    Sync[F].blocking {
      val ssh = new SSHClient()

      ssh.setConnectTimeout(
        config.connectTimeoutSeconds * 1000
      )

      val observedFingerprint =
        new AtomicReference[String]()

      ssh.addHostKeyVerifier(
        new HostKeyVerifier {

          override def verify(
                               hostname: String,
                               port: Int,
                               key: PublicKey
                             ): Boolean = {
            val fingerprint =
              SecurityUtils.getFingerprint(key)

            observedFingerprint.set(fingerprint)

            config.hostKeyFingerprint match {
              case Some(expected) =>
                expected == fingerprint

              case None =>
                true
            }
          }

          override def findExistingAlgorithms(
                                               hostname: String,
                                               port: Int
                                             ): JList[String] =
            Collections.emptyList[String]()
        }
      )

      try {
        ssh.connect(
          config.host,
          config.port
        )

        authenticate(
          ssh,
          config.username,
          authentication
        )

        val session = ssh.startSession()

        try {
          val remoteCommand =
            session.exec(command)

          remoteCommand.join(
            config.commandTimeoutSeconds.toLong,
            TimeUnit.SECONDS
          )

          val exitCode =
            Option(remoteCommand.getExitStatus)
              .map(_.intValue())
              .getOrElse {
                throw new TimeoutException(
                  s"SSH command timed out after " +
                    s"${config.commandTimeoutSeconds} seconds: $command"
                )
              }

          val stdout =
            new String(
              remoteCommand.getInputStream.readAllBytes(),
              StandardCharsets.UTF_8
            )

          val stderr =
            new String(
              remoteCommand.getErrorStream.readAllBytes(),
              StandardCharsets.UTF_8
            )

          val hostKeyFingerprint =
            Option(observedFingerprint.get())
              .getOrElse {
                throw new IllegalStateException(
                  s"SSH host key was not received from ${config.host}:${config.port}"
                )
              }

          SshCommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            hostKeyFingerprint = hostKeyFingerprint
          )
        } finally {
          session.close()
        }
      } finally {
        ssh.close()
      }
    }

  private def authenticate(
                            ssh: SSHClient,
                            username: String,
                            authentication: SshAuthentication
                          ): Unit =
    authentication match {
      case SshAuthentication.Password(value) =>
        ssh.authPassword(
          username,
          value.toCharArray
        )

      case SshAuthentication.PrivateKey(
        pem,
        passphrase
      ) =>
        val passwordFinder: PasswordFinder =
          passphrase
            .map { value =>
              PasswordUtils.createOneOff(
                value.toCharArray
              )
            }
            .orNull

        val keyProvider =
          ssh.loadKeys(
            pem,
            null,
            passwordFinder
          )

        ssh.authPublickey(
          username,
          keyProvider
        )
    }
}