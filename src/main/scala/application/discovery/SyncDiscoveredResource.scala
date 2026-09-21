package ru.bitec.app.ops
package application.discovery

import application.discovery.DiscoveredResourceResolution.{Existing, New}
import application.port.{
  IdGenerator,
  TimeProvider,
  TransactionRunner
}
import domain.connection.Connection
import domain.resource.Resource

import cats.{Monad, MonadThrow}
import cats.syntax.all._

import java.time.Instant
import java.util.UUID

final class SyncDiscoveredResource[F[_]: Monad, Tx[_]: MonadThrow](
                                                                    resolveDiscoveredResource: ResolveDiscoveredResource[Tx],
                                                                    createDiscoveredResource: CreateDiscoveredResource[Tx],
                                                                    reconcileDiscoveredResource: ReconcileDiscoveredResource[Tx],
                                                                    transactionRunner: TransactionRunner[F, Tx],
                                                                    idGenerator: IdGenerator[F],
                                                                    timeProvider: TimeProvider[F]
                                                                  ) {

  def execute(
               connection: Connection,
               discovered: DiscoveredResource,
               syncSessionId: UUID
             ): F[Resource] =
    for {
      resourceId <- idGenerator.nextId
      externalRefId <- idGenerator.nextId
      now <- timeProvider.now
      resource <- transactionRunner.run(
        program(
          connection,
          discovered,
          resourceId,
          externalRefId,
          syncSessionId,
          now
        )
      )
    } yield resource

  private def program(
                       connection: Connection,
                       discovered: DiscoveredResource,
                       newResourceId: UUID,
                       newExternalRefId: UUID,
                       syncSessionId: UUID,
                       now: Instant
                     ): Tx[Resource] =
    resolveDiscoveredResource.execute(connection, discovered).flatMap {
      case Existing(externalRef) =>
        reconcileDiscoveredResource.execute(
          connection,
          discovered,
          externalRef,
          syncSessionId,
          now
        )

      case New =>
        createDiscoveredResource.execute(
          connection,
          discovered,
          newResourceId,
          newExternalRefId,
          syncSessionId,
          now
        )
    }
}
