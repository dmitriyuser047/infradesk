package ru.bitec.app.ops
package application.discovery

import application.port.{ExternalRefRepository, ResourceRepository}
import domain.connection.Connection
import domain.externalref.ExternalRef
import domain.resource.Resource

import cats.MonadThrow
import cats.syntax.all._

import java.time.Instant
import java.util.UUID

final class ReconcileDiscoveredResource[Tx[_]: MonadThrow](
                                                            resourceRepository: ResourceRepository[Tx],
                                                            externalRefRepository: ExternalRefRepository[Tx]
                                                          ) {

  def execute(
               connection: Connection,
               discovered: DiscoveredResource,
               externalRef: ExternalRef,
               syncSessionId: UUID,
               now: Instant
             ): Tx[Resource] =
    for {
      resource <- findResource(connection, externalRef)
      parentResourceId <- resolveParentResourceId(connection, discovered)

      reconciledResource = resource.copy(
        parentResourceId = parentResourceId,
        code = discovered.code,
        name = discovered.name,
        isActive = true,
        updatedAt = now
      )

      _ <- resourceRepository.save(reconciledResource)
      _ <- externalRefRepository.save(
        externalRef.copy(
          lastSeenAt = now,
          updatedAt = now,
          lastSeenSyncSessionId = Some(syncSessionId)
        )
      )
    } yield reconciledResource

  private def findResource(
                            connection: Connection,
                            externalRef: ExternalRef
                          ): Tx[Resource] =
    resourceRepository
      .findById(connection.organizationId, externalRef.resourceId)
      .flatMap {
        case Some(resource) =>
          resource.pure[Tx]

        case None =>
          new IllegalStateException(
            s"Resource ${externalRef.resourceId} referenced by ExternalRef ${externalRef.id} was not found"
          ).raiseError[Tx, Resource]
      }

  private def resolveParentResourceId(
                                       connection: Connection,
                                       discovered: DiscoveredResource
                                     ): Tx[Option[UUID]] =
    discovered.parentExternalIdentity match {
      case None =>
        Option.empty[UUID].pure[Tx]

      case Some(parentIdentity) =>
        externalRefRepository
          .findByExternalIdentity(
            organizationId = connection.organizationId,
            connectionId = connection.id,
            externalType = parentIdentity.externalType,
            externalId = parentIdentity.externalId
          )
          .flatMap {
            case Some(parentExternalRef) =>
              (Some(parentExternalRef.resourceId): Option[UUID]).pure[Tx]

            case None =>
              new IllegalStateException(
                s"Parent resource " +
                  s"'${parentIdentity.externalType}/${parentIdentity.externalId}' " +
                  s"was not found for connection ${connection.id}"
              ).raiseError[Tx, Option[UUID]]
          }
    }
}
