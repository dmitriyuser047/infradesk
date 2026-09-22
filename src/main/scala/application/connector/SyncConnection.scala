package ru.bitec.app.ops
package application.connector

import application.discovery.{PendingDiscoveredResource, SyncDiscoveredSnapshot}
import application.port.{
  ConnectionRepository,
  ConnectionSyncResult,
  IdGenerator,
  SyncSessionRepository,
  TimeProvider,
  TransactionRunner
}
import application.resource.{PendingMetricObservation, RecordResourceObservations}
import domain.connection.{Connection, ConnectionConfig}
import domain.resource.Resource
import domain.sync.{SyncSession, SyncSessionStatus}

import cats.MonadThrow
import cats.syntax.all._

final class SyncConnection[F[_]: MonadThrow, Tx[_]: MonadThrow](
                                                     connectorRegistry: ResourceConnectorRegistry[F],
                                                     syncDiscoveredSnapshot: SyncDiscoveredSnapshot[Tx],
                                                     connectionRepository: ConnectionRepository[Tx],
                                                     syncSessionRepository: SyncSessionRepository[Tx],
                                                     transactionRunner: TransactionRunner[F, Tx],
                                                     idGenerator: IdGenerator[F],
                                                     timeProvider: TimeProvider[F],
                                                     recordResourceObservations: RecordResourceObservations[Tx]
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

      created <- transactionRunner.run(syncSessionRepository.tryCreate(syncSession))
      _ <- if (created) ().pure[F] else SyncAlreadyRunning().raiseError[F, Unit]

      result <- syncDiscoveredResources(
        connection,
        connector,
        syncSession
      ).attempt

      resources <- result match {
        case Right(resources) =>
          resources.pure[F]

        case Left(error) =>
          failSync(connection, syncSessionId, error)
      }
    } yield ConnectionSyncResult(syncSessionId, resources)

  private def syncDiscoveredResources(
                                       connection: Connection,
                                       connector: application.port.ResourceConnector[F],
                                       syncSession: SyncSession
                                     ): F[List[Resource]] =
    for {
      discovery <- connector.discover(connection)

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
    } yield resources

  private def failSync(
                       connection: Connection,
                       syncSessionId: java.util.UUID,
                       error: Throwable
                     ): F[List[Resource]] =
    timeProvider.now.flatMap { finishedAt =>
      transactionRunner
        .run(syncSessionRepository.fail(connection.organizationId, syncSessionId, finishedAt,
          "SYNC_FAILED", "Synchronization failed"))
        .attempt
        .flatMap {
          case Right(_) => ConnectionSyncExecutionFailed(syncSessionId, error).raiseError[F, List[Resource]]
          case Left(persistenceError) => persistenceError.raiseError[F, List[Resource]]
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
}
