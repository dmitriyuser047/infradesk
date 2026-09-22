package ru.bitec.app.ops
package application.connection

import application.connector.ConnectionSyncExecutionFailed
import application.port.{ConnectionRepository, ConnectionSyncRunner, SyncSessionRepository, TransactionRunner}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.sync.SyncSession

import java.util.UUID

final class ListConnectionSyncSessions[Tx[_]: Monad](
  connections: ConnectionRepository[Tx],
  sessions: SyncSessionRepository[Tx]
) {
  def execute(organizationId: UUID, connectionId: UUID): Tx[Option[List[SyncSession]]] =
    connections.findById(organizationId, connectionId).flatMap {
      case None => Monad[Tx].pure(None)
      case Some(_) => sessions.findRecentByConnection(organizationId, connectionId,
        ListConnectionSyncSessions.RecentSyncSessionLimit).map(Some(_))
    }
}

object ListConnectionSyncSessions {
  val RecentSyncSessionLimit = 20
}

final class GetConnectionSyncSession[Tx[_]: Monad](sessions: SyncSessionRepository[Tx]) {
  def execute(organizationId: UUID, connectionId: UUID, sessionId: UUID): Tx[Option[SyncSession]] =
    sessions.findById(organizationId, connectionId, sessionId)
}

final class RunManualConnectionSync[Tx[_]](
  syncRunner: ConnectionSyncRunner[IO],
  sessions: SyncSessionRepository[Tx],
  transactionRunner: TransactionRunner[IO, Tx]
) {
  def execute(organizationId: UUID, connectionId: UUID): IO[SyncSession] =
    syncRunner.execute(organizationId, connectionId).attempt.flatMap {
      case Right(result) => loadSession(organizationId, connectionId, result.sessionId)
      case Left(error: ConnectionSyncExecutionFailed) =>
        loadSession(organizationId, connectionId, error.sessionId)
      case Left(error) => IO.raiseError(error)
    }

  private def loadSession(organizationId: UUID, connectionId: UUID, sessionId: UUID): IO[SyncSession] =
    transactionRunner.run(sessions.findById(organizationId, connectionId, sessionId)).flatMap {
      case Some(session) => IO.pure(session)
      case None => IO.raiseError(new IllegalStateException("Completed synchronization session was not found"))
    }
}
