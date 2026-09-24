package ru.bitec.app.ops
package application.connection

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.connector.{ConnectionSyncExecutionFailed, ConnectionSyncNotFound}
import application.port.{ConnectionRepository, ConnectionSyncRunner, SyncSessionRepository, TransactionRunner}
import cats.MonadThrow
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
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

/** Runs a synchronization on behalf of a user.
  *
  * The run itself is not one transaction: it opens a session, talks to the host and writes its
  * results over several of them. What is journalled here is the request, in its own short
  * transaction together with the existence check that justifies it; if that write fails, the
  * synchronization does not start. The outcome of the run is sync session history, not audit.
  */
final class RunManualConnectionSync[Tx[_]: MonadThrow](
  syncRunner: ConnectionSyncRunner[IO],
  sessions: SyncSessionRepository[Tx],
  connections: ConnectionRepository[Tx],
  audit: AuditRecorder[Tx],
  transactionRunner: TransactionRunner[IO, Tx]
) {
  def execute(actor: ActorContext, connectionId: UUID): IO[SyncSession] = {
    val organizationId = actor.organizationId
    transactionRunner.run(recordRequest(actor, connectionId)) *>
      syncRunner.execute(organizationId, connectionId).attempt.flatMap {
        case Right(result) => loadSession(organizationId, connectionId, result.sessionId)
        case Left(error: ConnectionSyncExecutionFailed) =>
          loadSession(organizationId, connectionId, error.sessionId)
        case Left(error) => IO.raiseError(error)
      }
  }

  private def recordRequest(actor: ActorContext, connectionId: UUID): Tx[Unit] =
    connections.findById(actor.organizationId, connectionId).flatMap {
      case None => MonadThrow[Tx].raiseError[Unit](ConnectionSyncNotFound())
      case Some(_) => audit.record(actor, AuditAction.ManualSyncRequested,
        AuditTargetType.Connection, Some(connectionId))
    }

  private def loadSession(organizationId: UUID, connectionId: UUID, sessionId: UUID): IO[SyncSession] =
    transactionRunner.run(sessions.findById(organizationId, connectionId, sessionId)).flatMap {
      case Some(session) => IO.pure(session)
      case None => IO.raiseError(new IllegalStateException("Completed synchronization session was not found"))
    }
}
