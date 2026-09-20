package ru.bitec.app.ops
package application.connector

import application.discovery.SyncDiscoveredResource
import application.port.{
  ConnectionRepository,
  TimeProvider,
  TransactionRunner
}
import domain.connection.{Connection, ConnectionConfig}
import domain.resource.Resource

import cats.MonadThrow
import cats.syntax.all._

final class SyncConnection[F[_]: MonadThrow, Tx[_]](
                                                     connectorRegistry: ResourceConnectorRegistry[F],
                                                     syncDiscoveredResource: SyncDiscoveredResource[F, Tx],
                                                     connectionRepository: ConnectionRepository[Tx],
                                                     transactionRunner: TransactionRunner[F, Tx],
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

      discovery <- connector.discover(connection)

      _ <- saveConnectionConfigIfChanged(
        connection,
        discovery.connectionConfig
      )

      resources <- discovery.resources.traverse { discovered =>
        syncDiscoveredResource.execute(
          connection,
          discovered
        )
      }
    } yield resources

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