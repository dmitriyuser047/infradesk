package ru.bitec.app.ops
package application.connector

import application.discovery.{PendingDiscoveredResource, SyncDiscoveredSnapshot}
import application.history.{HistoryEntry, HistoryRecorder}
import application.port.{
  ConnectionRepository,
  ConnectionSyncResult,
  IdGenerator,
  ResourceConnectorFailure,
  SyncSessionRepository,
  TimeProvider,
  TransactionRunner
}
import application.resource.{PendingMetricObservation, RecordResourceObservations}
import domain.connection.{Connection, ConnectionConfig}
import domain.history.HistoryEventType
import domain.resource.Resource
import domain.sync.{SyncSession, SyncSessionStatus}

import cats.MonadThrow
import cats.syntax.all._
import org.typelevel.log4cats.Logger

import java.time.Duration

final class SyncConnection[F[_]: MonadThrow, Tx[_]: MonadThrow](
                                                     connectorRegistry: ResourceConnectorRegistry[F],
                                                     syncDiscoveredSnapshot: SyncDiscoveredSnapshot[Tx],
                                                     connectionRepository: ConnectionRepository[Tx],
                                                     syncSessionRepository: SyncSessionRepository[Tx],
                                                     transactionRunner: TransactionRunner[F, Tx],
                                                     idGenerator: IdGenerator[F],
                                                     timeProvider: TimeProvider[F],
                                                     recordResourceObservations: RecordResourceObservations[Tx],
                                                     historyRecorder: HistoryRecorder[Tx],
                                                     logger: Logger[F]
                                                   ) {

  def execute(
               connection: Connection
             ): F[ConnectionSyncResult] =
    for {
      connector <- connectorRegistry
        .find(connection.connectorType)
        .liftTo[F](
          new IllegalStateException(
            s"Connector '${connection.connectorType}' is not registered"
          )
        )

      startedAt <- timeProvider.now
      syncSessionId <- idGenerator.nextId
      syncSession = SyncSession(
        id = syncSessionId,
        organizationId = connection.organizationId,
        connectionId = connection.id,
        startedAt = startedAt,
        finishedAt = None,
        status = SyncSessionStatus.Running
      )

      // Retiring an abandoned session is a fact of its own, and it is journalled in the same
      // transaction that retires it: no discovery starts until both are committed.
      claim <- transactionRunner.run(
        syncSessionRepository.recoverStaleAndTryCreate(
          syncSession,
          startedAt.minusSeconds(SyncSessionPolicy.StaleAfterSeconds),
          startedAt,
          SyncFailure.Stale.code,
          SyncFailure.Stale.message
        ).flatTap(claim => historyRecorder.recordAll(claim.recovered.map(recovered =>
          HistoryEntry
            .system(connection.organizationId, HistoryEventType.SyncFailed,
              recovered.finishedAt.getOrElse(startedAt))
            .copy(connectionId = Some(connection.id), syncSessionId = Some(recovered.id))
        )))
      )
      _ <- claim.recovered.traverse_(recovered =>
        logInfo(s"sync.stale.recovered organizationId=${connection.organizationId} " +
          s"connectionId=${connection.id} syncSessionId=${recovered.id} errorCode=${SyncFailure.Stale.code}")
      )
      _ <- if (claim.created) ().pure[F] else SyncAlreadyRunning().raiseError[F, Unit]
      _ <- logInfo(s"sync.started organizationId=${connection.organizationId} connectionId=${connection.id} syncSessionId=$syncSessionId connectorType=${connection.connectorType}")

      result <- syncDiscoveredResources(
        connection,
        connector,
        syncSession
      ).attempt

      resources <- result match {
        case Right(resources) =>
          resources.pure[F]

        case Left(error) =>
          failSync(connection, syncSession, error)
      }
    } yield ConnectionSyncResult(syncSessionId, resources)

  private def syncDiscoveredResources(
                                       connection: Connection,
                                       connector: application.port.ResourceConnector[F],
                                       syncSession: SyncSession
                                     ): F[List[Resource]] =
    for {
      discovery <- connector.discover(connection).onError { case error =>
        val failure = SyncFailure.from(error)
        val message = s"connector.discovery.failed organizationId=${connection.organizationId} connectionId=${connection.id} syncSessionId=${syncSession.id} connectorType=${connection.connectorType} errorCode=${failure.code} errorType=${error.getClass.getSimpleName}"
        error match {
          case _: ResourceConnectorFailure => logError(message)
          case _ => logError(message, error)
        }
      }
      _ <- logInfo(s"sync.discovery.completed organizationId=${connection.organizationId} connectionId=${connection.id} syncSessionId=${syncSession.id} connectorType=${connection.connectorType} resourcesCount=${discovery.resources.size}")

      _ <- saveConnectionConfigIfChanged(connection, discovery.connectionConfig)

      pendingResources <- discovery.resources.traverse { discovered =>
        for {
          resourceId <- idGenerator.nextId
          externalRefId <- idGenerator.nextId
          metricObservations <- recordResourceObservations
            .metricCodesFor(discovered.resourceTypeCode, discovered.data)
            .traverse { metricCode =>
              idGenerator.nextId.map(id => PendingMetricObservation(id, metricCode))
            }
        } yield PendingDiscoveredResource(
          discovered,
          resourceId,
          externalRefId,
          metricObservations
        )
      }

      completedAt <- timeProvider.now

      resources <- transactionRunner.run(
        syncDiscoveredSnapshot.execute(
          connection,
          syncSession,
          pendingResources,
          discovery.completeExternalTypes,
          completedAt
        )
      )
      _ <- logInfo(s"sync.snapshot.persisted organizationId=${connection.organizationId} connectionId=${connection.id} syncSessionId=${syncSession.id} resourcesCount=${resources.size}")
      _ <- logInfo(s"sync.completed organizationId=${connection.organizationId} connectionId=${connection.id} syncSessionId=${syncSession.id} connectorType=${connection.connectorType} resourcesCount=${resources.size} durationMs=${Duration.between(syncSession.startedAt, completedAt).toMillis}")
    } yield resources

  private def failSync(
                       connection: Connection,
                       syncSession: SyncSession,
                       error: Throwable
                     ): F[List[Resource]] =
    timeProvider.now.flatMap { finishedAt =>
      val failure = SyncFailure.from(error)
      val message = s"sync.failed organizationId=${connection.organizationId} connectionId=${connection.id} syncSessionId=${syncSession.id} connectorType=${connection.connectorType} errorCode=${failure.code} errorType=${error.getClass.getSimpleName} durationMs=${Duration.between(syncSession.startedAt, finishedAt).toMillis}"
      val failureLog = error match {
        case _: ResourceConnectorFailure => logError(message)
        case _ => logError(message, error)
      }
      failureLog *>
      transactionRunner
        // The failed session and the fact that reports it are one commit; the safe error code
        // stays in the session and is read through it.
        .run(syncSessionRepository.fail(connection.organizationId, syncSession.id, finishedAt,
          failure.code, failure.message) *>
          historyRecorder.record(HistoryEntry
            .system(connection.organizationId, HistoryEventType.SyncFailed, finishedAt)
            .copy(connectionId = Some(connection.id), syncSessionId = Some(syncSession.id))))
        .attempt
        .flatMap {
          case Right(_) => ConnectionSyncExecutionFailed(syncSession.id, error).raiseError[F, List[Resource]]
          case Left(persistenceError) =>
            logError(s"sync.failure.persistence.failed organizationId=${connection.organizationId} connectionId=${connection.id} syncSessionId=${syncSession.id} errorType=${persistenceError.getClass.getSimpleName}", persistenceError) *>
              persistenceError.raiseError[F, List[Resource]]
        }
    }

  private def saveConnectionConfigIfChanged(
                                             connection: Connection,
                                             config: ConnectionConfig
                                           ): F[Unit] =
    if (connection.config == config) {
      ().pure[F]
    } else {
      for {
        now <- timeProvider.now

        updatedConnection =
          connection.copy(
            config = config,
            updatedAt = now
          )

        _ <- transactionRunner.run(
          connectionRepository.save(
            updatedConnection
          )
        )
      } yield ()
    }

  private def logInfo(message: String): F[Unit] =
    logger.info(message).handleErrorWith(_ => ().pure[F])

  private def logError(message: String): F[Unit] =
    logger.error(message).handleErrorWith(_ => ().pure[F])

  private def logError(message: String, error: Throwable): F[Unit] =
    logger.error(error)(message).handleErrorWith(_ => ().pure[F])
}
