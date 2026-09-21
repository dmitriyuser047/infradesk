package ru.bitec.app.ops
package application.connector

import application.discovery.{PendingDiscoveredResource, SyncDiscoveredSnapshot}
import application.port.{
  ConnectionRepository,
  IdGenerator,
  SyncSessionRepository,
  TimeProvider,
  TransactionRunner
}
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
                                                     timeProvider: TimeProvider[F]
                                                   ) {

  def execute(
               connection: Connection
             ): F[List[Resource]] =
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

      _ <- transactionRunner.run(syncSessionRepository.create(syncSession))

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
    } yield resources

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
        } yield PendingDiscoveredResource(discovered, resourceId, externalRefId)
      }

      completedAt <- timeProvider.now

      resources <- transactionRunner.run(
        syncDiscoveredSnapshot.execute(
          connection,
          syncSession,
          pendingResources,
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
        .run(syncSessionRepository.fail(connection.organizationId, syncSessionId, finishedAt))
        .attempt
        .flatMap(_ => error.raiseError[F, List[Resource]])
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
