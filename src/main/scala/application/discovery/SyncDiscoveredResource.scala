package ru.bitec.app.ops
package application.discovery

import application.discovery.DiscoveredResourceResolution.{Existing, New}
import application.port.{
  ExternalRefRepository,
  IdGenerator,
  ResourceRepository,
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
                                                                    resourceRepository: ResourceRepository[Tx],
                                                                    externalRefRepository: ExternalRefRepository[Tx],
                                                                    transactionRunner: TransactionRunner[F, Tx],
                                                                    idGenerator: IdGenerator[F],
                                                                    timeProvider: TimeProvider[F]
                                                                  ) {

  def execute(connection: Connection, discovered: DiscoveredResource): F[Resource] =
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
          now
        )
      )
    } yield resource

  private def program(
                       connection: Connection,
                       discovered: DiscoveredResource,
                       newResourceId: UUID,
                       newExternalRefId: UUID,
                       now: Instant
                     ): Tx[Resource] =
    resolveDiscoveredResource.execute(connection, discovered).flatMap {
      case Existing(externalRef) =>
        for {
          resource <- resourceRepository
            .findById(connection.organizationId, externalRef.resourceId)
            .flatMap {
              case Some(resource) =>
                resource.pure[Tx]

              case None =>
                new IllegalStateException(
                  s"Resource ${externalRef.resourceId} referenced by ExternalRef ${externalRef.id} was not found"
                ).raiseError[Tx, Resource]
            }

          _ <- externalRefRepository.save(
            externalRef.copy(
              lastSeenAt = now,
              updatedAt = now
            )
          )
        } yield resource

      case New =>
        createDiscoveredResource.execute(
          connection,
          discovered,
          newResourceId,
          newExternalRefId,
          now
        )
    }
}