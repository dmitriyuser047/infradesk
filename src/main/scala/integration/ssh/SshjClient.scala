package ru.bitec.app.ops
package integration.ssh

import cats.effect.{Resource, Sync}

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.{PasswordFinder, PasswordUtils}

import java.nio.charset.StandardCharsets
import java.util.concurrent.{TimeUnit, TimeoutException}
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier

import java.security.PublicKey
import java.util.{Collections, List => JList}
import java.util.concurrent.atomic.AtomicReference

final class SshHostKeyMismatch extends RuntimeException("SSH host key mismatch")

final class SshjClient[F[_]: Sync] extends SshClient[F] {

  override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                             (use: SshSession[F] => F[A]): F[A] =
    Resource.make(Sync[F].blocking(open(config, authentication))) {
      case (ssh, _) => Sync[F].blocking(ssh.close())
    }.use { case (ssh, observedFingerprint) =>
      use(new SshSession[F] {
        override def execute(command: String): F[SshCommandResult] =
          Sync[F].blocking(runCommand(ssh, observedFingerprint, config, command))
      })
    }

  private def open(config: SshConnectionConfig, authentication: SshAuthentication):
    (SSHClient, AtomicReference[String]) = {
      val ssh = new SSHClient()
      try {

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

        try ssh.connect(config.host, config.port)
        catch {
          case error: Exception if config.hostKeyFingerprint.exists(_ != observedFingerprint.get()) &&
            observedFingerprint.get() != null => throw new SshHostKeyMismatch
        }

        authenticate(
          ssh,
          config.username,
          authentication
        )

        (ssh, observedFingerprint)
      } catch {
        case error: Throwable =>
          try ssh.close()
          catch {
            case closeError: Throwable =>
              if (closeError ne error) error.addSuppressed(closeError)
          }
          throw error
      }
    }

  private def runCommand(ssh: SSHClient, observedFingerprint: AtomicReference[String],
                         config: SshConnectionConfig, command: String): SshCommandResult = {
    val session = ssh.startSession()
    try {
      val remoteCommand = session.exec(command)
      remoteCommand.join(config.commandTimeoutSeconds.toLong, TimeUnit.SECONDS)

      val exitCode = Option(remoteCommand.getExitStatus).map(_.intValue()).getOrElse {
        throw new TimeoutException(
          s"SSH command timed out after ${config.commandTimeoutSeconds} seconds: $command"
        )
      }

      val stdout = new String(remoteCommand.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val stderr = new String(remoteCommand.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
      val fingerprint = Option(observedFingerprint.get()).getOrElse {
        throw new IllegalStateException(s"SSH host key was not received from ${config.host}:${config.port}")
      }

      SshCommandResult(exitCode, stdout, stderr, fingerprint)
    } finally {
      session.close()
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
