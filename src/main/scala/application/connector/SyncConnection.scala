package ru.bitec.app.ops
package application.connector

import application.discovery.SyncDiscoveredResource
import application.port.{
  ConnectionRepository,
  IdGenerator,
  ResourceRepository,
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
                                                     syncDiscoveredResource: SyncDiscoveredResource[F, Tx],
                                                     connectionRepository: ConnectionRepository[Tx],
                                                     resourceRepository: ResourceRepository[Tx],
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
        syncSessionId
      ).attempt

      resources <- result match {
        case Right(resources) =>
          completeSync(connection, syncSessionId).as(resources)

        case Left(error) =>
          failSync(connection, syncSessionId, error)
      }
    } yield resources

  private def syncDiscoveredResources(
                                       connection: Connection,
                                       connector: application.port.ResourceConnector[F],
                                       syncSessionId: java.util.UUID
                                     ): F[List[Resource]] =
    for {
      discovery <- connector.discover(connection)

      _ <- saveConnectionConfigIfChanged(connection, discovery.connectionConfig)

      resources <- discovery.resources.traverse { discovered =>
        syncDiscoveredResource.execute(connection, discovered, syncSessionId)
      }
    } yield resources

  private def completeSync(
                           connection: Connection,
                           syncSessionId: java.util.UUID
                         ): F[Unit] =
    timeProvider.now.flatMap { finishedAt =>
      transactionRunner.run(
        syncSessionRepository.complete(connection.organizationId, syncSessionId, finishedAt) *>
          resourceRepository.deactivateMissingForConnection(
            connection.organizationId,
            connection.id,
            syncSessionId,
            finishedAt
          ).void
      )
    }

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
