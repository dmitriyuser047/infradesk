package ru.bitec.app.ops
package application.connector

import application.port.{ConnectionRepository, ConnectionSynchronizer, TransactionRunner}
import domain.resource.Resource

import cats.MonadThrow
import cats.syntax.all._

import java.util.UUID

final class SyncConnectionById[F[_]: MonadThrow, Tx[_]: MonadThrow](
                                                                     connectionRepository: ConnectionRepository[Tx],
                                                                     transactionRunner: TransactionRunner[F, Tx],
                                                                     syncConnection: SyncConnection[F, Tx]
                                                                   ) extends ConnectionSynchronizer[F] {

  override def execute(
               organizationId: UUID,
               connectionId: UUID
             ): F[List[Resource]] =
    for {
      connection <- transactionRunner
        .run(
          connectionRepository
            .findById(organizationId, connectionId)
            .flatMap {
              case Some(connection) =>
                connection.pure[Tx]

              case None =>
                new IllegalStateException(
                  s"Connection $connectionId was not found for organization $organizationId"
                ).raiseError[Tx, domain.connection.Connection]
            }
        )

      _ <-
        if (connection.isActive)
          ().pure[F]
        else
          new IllegalStateException(
            s"Connection ${connection.id} is inactive"
          ).raiseError[F, Unit]

      resources <- syncConnection.execute(connection)
    } yield resources
}
