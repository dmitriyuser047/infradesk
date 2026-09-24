package ru.bitec.app.ops
package integration.ssh

import cats.effect.kernel.Resource.ExitCase
import cats.effect.{Async, Resource}
import cats.syntax.all._
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.{PasswordFinder, PasswordUtils}

import java.io.IOException
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.{Collections, List => JList}

final class SshHostKeyMismatch(cause: Throwable = null)
  extends RuntimeException("SSH host key mismatch", cause)

final class SshjClient[F[_]: Async](
  commandPolicy: SshCommandExecutionPolicy = SshCommandExecutionPolicy.Default
) extends SshClient[F] {

  private val commandExecutor = new SshCommandExecutor[F](commandPolicy)

  override def withSession[A](config: SshConnectionConfig, authentication: SshAuthentication)
                             (use: SshSession[F] => F[A]): F[A] =
    Resource.makeCase(Async[F].blocking(open(config, authentication))) {
      case ((ssh, _), exitCase) => closeClient(ssh, exitCase)
    }.use { case (ssh, observedFingerprint) =>
      use(new SshSession[F] {
        override def execute(command: String): F[SshCommandResult] =
          runCommand(ssh, observedFingerprint, config, command)
      })
    }

  private def runCommand(
    ssh: SSHClient,
    observedFingerprint: AtomicReference[Option[String]],
    config: SshConnectionConfig,
    command: String
  ): F[SshCommandResult] =
    Async[F].blocking(startCommand(ssh, command)).flatMap { running =>
      commandExecutor.execute(running, config.commandTimeoutSeconds).flatMap { captured =>
        Async[F].delay(observedFingerprint.get().getOrElse {
          throw new IllegalStateException(
            s"SSH host key was not received from ${config.host}:${config.port}"
          )
        }).map { fingerprint =>
          SshCommandResult(captured.exitCode, captured.stdout, captured.stderr, fingerprint)
        }
      }
    }.adaptError {
      case failure: SshTransportFailure => failure
      case error: IOException => SshTransportFailure.fromCommandTransport(error)
    }

  private def startCommand(ssh: SSHClient, command: String): RunningSshCommand = {
    val session = ssh.startSession()
    try {
      val remoteCommand = session.exec(command)
      new RunningSshCommand {
        override val stdout = remoteCommand.getInputStream
        override val stderr = remoteCommand.getErrorStream
        override def await(timeoutSeconds: Int): Option[Int] = {
          remoteCommand.join(timeoutSeconds.toLong, TimeUnit.SECONDS)
          Option(remoteCommand.getExitStatus).map(_.intValue())
        }
        override def close(): Unit = closeCommand(remoteCommand, session)
      }
    } catch {
      case primary: Throwable =>
        try session.close()
        catch {
          case closeError: Throwable if closeError ne primary => primary.addSuppressed(closeError)
        }
        throw primary
    }
  }

  private def closeCommand(command: Session.Command, session: Session): Unit = {
    var primary: Option[Throwable] = None
    try command.close()
    catch { case error: Throwable => primary = Some(error) }
    try session.close()
    catch {
      case closeError: Throwable => primary match {
        case Some(error) if closeError ne error => error.addSuppressed(closeError)
        case None => primary = Some(closeError)
        case _ => ()
      }
    }
    primary.foreach(throw _)
  }

  private def closeClient(ssh: SSHClient, exitCase: ExitCase): F[Unit] =
    Async[F].blocking(ssh.close()).attempt.flatMap {
      case Right(_) => Async[F].unit
      case Left(closeError) => exitCase match {
        case ExitCase.Errored(primary) => Async[F].delay {
          if (closeError ne primary) primary.addSuppressed(closeError)
        }
        case _ => closeError.raiseError[F, Unit]
      }
    }

  private def open(config: SshConnectionConfig, authentication: SshAuthentication):
    (SSHClient, AtomicReference[Option[String]]) = {
    val ssh = new SSHClient()
    try {
      ssh.setConnectTimeout(config.connectTimeoutSeconds * 1000)
      val observedFingerprint = new AtomicReference[Option[String]](None)

      ssh.addHostKeyVerifier(new HostKeyVerifier {
        override def verify(hostname: String, port: Int, key: PublicKey): Boolean = {
          val fingerprint = SecurityUtils.getFingerprint(key)
          observedFingerprint.set(Some(fingerprint))
          config.hostKeyFingerprint.forall(_ == fingerprint)
        }

        override def findExistingAlgorithms(hostname: String, port: Int): JList[String] =
          Collections.emptyList[String]()
      })

      try ssh.connect(config.host, config.port)
      catch {
        case error: IOException =>
          val mismatch = observedFingerprint.get().exists(observed =>
            config.hostKeyFingerprint.exists(_ != observed)
          )
          throw SshTransportFailure.fromConnect(error, mismatch)
      }

      try authenticate(ssh, config.username, authentication)
      catch {
        case error: UserAuthException => throw SshTransportFailure.fromAuthentication(error)
        case error: TransportException => throw SshTransportFailure.fromAuthenticationTransport(error)
      }

      (ssh, observedFingerprint)
    } catch {
      case error: Throwable =>
        try ssh.close()
        catch {
          case closeError: Throwable if closeError ne error => error.addSuppressed(closeError)
        }
        throw error
    }
  }

  private def authenticate(ssh: SSHClient, username: String, authentication: SshAuthentication): Unit =
    authentication match {
      case SshAuthentication.Password(value) => ssh.authPassword(username, value.toCharArray)
      case SshAuthentication.PrivateKey(pem, passphrase) =>
        val passwordFinder: PasswordFinder = passphrase
          .map(value => PasswordUtils.createOneOff(value.toCharArray)).orNull
        val keyProvider = ssh.loadKeys(pem, null, passwordFinder)
        ssh.authPublickey(username, keyProvider)
    }
}
