package ru.bitec.app.ops
package application.connection

import application.port.{ConnectionRepository, TransactionRunner}
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all._
import domain.connection.Connection
import integration.ssh.{InteractiveSshTerminal, SshAuthenticationProvider, SshClient, SshConnectionConfig, TerminalSize}

import java.util.UUID

sealed abstract class TerminalOpenFailure(val code: String, message: String)
  extends RuntimeException(message)

object TerminalOpenFailure {
  case object NotFound extends TerminalOpenFailure("CONNECTION_NOT_FOUND", "Connection was not found")
  case object Unsupported extends TerminalOpenFailure("TERMINAL_UNSUPPORTED", "Connection does not support SSH terminal")
  case object Inactive extends TerminalOpenFailure("CONNECTION_INACTIVE", "Connection is inactive")
  case object Capacity extends TerminalOpenFailure("TERMINAL_CAPACITY", "Terminal capacity is full")
  case object InvalidConfiguration extends TerminalOpenFailure("TERMINAL_UNAVAILABLE", "SSH terminal is unavailable")
}

/** Resolves only an organization-owned active SSH connection before opening a terminal. */
final class OpenSshTerminal[Tx[_]] private (
  connections: ConnectionRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  ssh: SshClient[IO],
  credentials: SshAuthenticationProvider[IO],
  capacity: Semaphore[IO]
) {
  import TerminalOpenFailure._

  final case class Prepared private[OpenSshTerminal](
    config: SshConnectionConfig,
    authentication: integration.ssh.SshAuthentication
  )

  def prepare(organizationId: UUID, connectionId: UUID): IO[Prepared] =
    for {
      connection <- runner.run(connections.findById(organizationId, connectionId))
        .flatMap(IO.fromOption(_)(NotFound))
      _ <- validate(connection)
      config <- IO.fromEither(SshConnectionConfig.from(connection.config)
        .leftMap(_ => InvalidConfiguration))
      authentication <- credentials.resolve(connection)
    } yield Prepared(config, authentication)

  final class CapacityPermit private[OpenSshTerminal] (released: Ref[IO, Boolean]) {
    def release: IO[Unit] = IO.uncancelable { _ =>
      released.modify(done => (true, !done)).flatMap {
        case true => capacity.release
        case false => IO.unit
      }
    }
  }

  def reserveCapacity: IO[Option[CapacityPermit]] = IO.uncancelable { _ =>
    capacity.tryAcquire.flatMap {
      case false => IO.pure(None)
      case true => Ref.of[IO, Boolean](false).map(ref => Some(new CapacityPermit(ref)))
        .onError(_ => capacity.release)
    }
  }

  def resource(prepared: Prepared, size: TerminalSize, permit: CapacityPermit):
    Resource[IO, InteractiveSshTerminal[IO]] =
    for {
      _ <- Resource.make(IO.unit)(_ => permit.release)
      terminal <- ssh.terminal(prepared.config, prepared.authentication, size)
    } yield terminal

  private def validate(connection: Connection): IO[Unit] =
    if (connection.connectorType != "SSH") IO.raiseError(Unsupported)
    else if (!connection.isActive) IO.raiseError(Inactive)
    else IO.unit
}

object OpenSshTerminal {
  def create[Tx[_]](
    connections: ConnectionRepository[Tx],
    runner: TransactionRunner[IO, Tx],
    ssh: SshClient[IO],
    credentials: SshAuthenticationProvider[IO],
    maxConcurrentSessions: Int
  ): IO[OpenSshTerminal[Tx]] =
    Semaphore[IO](maxConcurrentSessions.toLong).map(
      new OpenSshTerminal(connections, runner, ssh, credentials, _))
}
