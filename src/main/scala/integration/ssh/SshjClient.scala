package ru.bitec.app.ops
package integration.ssh

import cats.effect.kernel.Resource.ExitCase
import cats.effect.{Async, Resource}
import cats.syntax.all._
import fs2.Chunk
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.{PTYMode, Session}
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

final class SshHostKeyNotTrusted(message: String)
  extends RuntimeException(message)

/** Stops the handshake once the host key has been read, so a probe learns the identity of a host
  * without ever offering it a credential.
  */
private[ssh] final class SshHostKeyProbed(val fingerprint: String)
  extends RuntimeException("SSH host key probed")

final class SshjClient[F[_]: Async](
  commandPolicy: SshCommandExecutionPolicy = SshCommandExecutionPolicy.Default
) extends SshClient[F] {

  private val commandExecutor = new SshCommandExecutor[F](commandPolicy)

  /** Reads the host key and disconnects. The verifier refuses every key, so sshj aborts the
    * transport before user authentication can start: nothing secret leaves this process.
    */
  override def probeHostKey(config: SshConnectionConfig): F[String] =
    Async[F].blocking {
      val ssh = new SSHClient()
      val observed = new AtomicReference[Option[String]](None)
      try {
        ssh.setConnectTimeout(config.connectTimeoutSeconds * 1000)
        ssh.addHostKeyVerifier(new HostKeyVerifier {
          override def verify(hostname: String, port: Int, key: PublicKey): Boolean = {
            observed.set(Some(SecurityUtils.getFingerprint(key)))
            false
          }

          override def findExistingAlgorithms(hostname: String, port: Int): JList[String] =
            Collections.emptyList[String]()
        })
        try ssh.connect(config.host, config.port)
        catch {
          case error: IOException => observed.get() match {
            case Some(fingerprint) => throw new SshHostKeyProbed(fingerprint)
            case None => throw SshTransportFailure.fromConnect(error, hostKeyMismatch = false)
          }
        }
        observed.get().getOrElse(
          throw new IllegalStateException(s"SSH host key was not received from ${config.host}:${config.port}")
        )
      } finally {
        try ssh.close()
        catch { case _: Throwable => () }
      }
    }.recover { case probed: SshHostKeyProbed => probed.fingerprint }

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

  override def terminal(
    config: SshConnectionConfig,
    authentication: SshAuthentication,
    size: TerminalSize
  ): Resource[F, InteractiveSshTerminal[F]] =
    Resource.makeCase(Async[F].blocking(open(config, authentication))) {
      case ((ssh, _), exitCase) => closeClient(ssh, exitCase)
    }.flatMap { case (ssh, _) =>
      Resource.makeCase(Async[F].blocking(startTerminal(ssh, size))) {
        case (terminal, exitCase) => closeTerminal(terminal, exitCase)
      }.map { terminal =>
        val shell = terminal.shell
        new InteractiveSshTerminal[F] {
          override val output: fs2.Stream[F, Byte] =
            fs2.io.readInputStream(Async[F].pure(shell.getInputStream), 8192, closeAfterUse = false)
              .handleErrorWith(error => fs2.Stream.raiseError[F](SshTransportFailure.fromCommandTransport(
                error match {
                  case io: IOException => io
                  case other => new IOException("SSH terminal output failed", other)
                }
              )))

          override def write(bytes: Chunk[Byte]): F[Unit] =
            Async[F].blocking {
              val stream = shell.getOutputStream
              bytes.foreach(byte => stream.write(byte.toInt))
              stream.flush()
            }.adaptError {
              case error: IOException => SshTransportFailure.fromCommandTransport(error)
            }

          override def resize(next: TerminalSize): F[Unit] =
            Async[F].blocking(shell.changeWindowDimensions(next.columns, next.rows, 0, 0))
              .adaptError {
                case error: IOException => SshTransportFailure.fromCommandTransport(error)
              }
        }
      }
    }

  private final case class RunningTerminal(session: Session, shell: Session.Shell)

  private def startTerminal(ssh: SSHClient, size: TerminalSize): RunningTerminal = {
    val session = ssh.startSession()
    try {
      session.allocatePTY("xterm-256color", size.columns, size.rows, 0, 0,
        Collections.emptyMap[PTYMode, Integer]())
      RunningTerminal(session, session.startShell())
    } catch {
      case primary: Throwable =>
        try session.close()
        catch {
          case closeError: Throwable if closeError ne primary => primary.addSuppressed(closeError)
        }
        throw primary
    }
  }

  private def closeTerminal(terminal: RunningTerminal, exitCase: ExitCase): F[Unit] =
    Async[F].blocking {
      var primary: Option[Throwable] = None
      try terminal.shell.close()
      catch { case error: Throwable => primary = Some(error) }
      try terminal.session.close()
      catch {
        case closeError: Throwable => primary match {
          case Some(error) if closeError ne error => error.addSuppressed(closeError)
          case None => primary = Some(closeError)
          case _ => ()
        }
      }
      primary.foreach(throw _)
    }.attempt.flatMap {
      case Right(_) => Async[F].unit
      case Left(closeError) => exitCase match {
        case ExitCase.Errored(primary) => Async[F].delay {
          if (closeError ne primary) primary.addSuppressed(closeError)
        }
        case _ => closeError.raiseError[F, Unit]
      }
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

      // An unconfirmed host is refused here, before authentication: a credential is never the
      // way InfraDesk finds out who it is talking to.
      val trusted = config.hostKeyFingerprint.getOrElse(
        throw SshTransportFailure.hostKeyNotTrusted(config.host, config.port)
      )

      ssh.addHostKeyVerifier(new HostKeyVerifier {
        override def verify(hostname: String, port: Int, key: PublicKey): Boolean = {
          val fingerprint = SecurityUtils.getFingerprint(key)
          observedFingerprint.set(Some(fingerprint))
          fingerprint == trusted
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
        case error: SshTransportFailure => throw error
        case error: UserAuthException =>
          val failure = authentication match {
            case SshAuthentication.PrivateKey(_, _) =>
              SshTransportFailure.fromPrivateKeyAuthentication(error)
            case SshAuthentication.Password(_) => SshTransportFailure.fromAuthentication(error)
          }
          throw failure
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
        // The key is parsed once per attempt, from memory: it is never written to disk.
        val keyProvider =
          try ssh.loadKeys(pem, null, passwordFinder)
          catch { case error: IOException => throw SshTransportFailure.fromPrivateKey(error) }
        ssh.authPublickey(username, keyProvider)
    }
}
