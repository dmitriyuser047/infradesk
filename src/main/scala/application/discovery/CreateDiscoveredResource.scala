package ru.bitec.app.ops
package application.discovery

import application.port.{ExternalRefRepository, ResourceTypeRepository}
import application.resource.PersistExternalResource
import domain.connection.{Connection, ConnectionScope}
import domain.externalref.ExternalRef
import domain.resource.Resource

import cats.MonadThrow
import cats.syntax.all._

import java.time.Instant
import java.util.UUID

final class CreateDiscoveredResource[Tx[_]: MonadThrow](
                                                         resourceTypeRepository: ResourceTypeRepository[Tx],
                                                         externalRefRepository: ExternalRefRepository[Tx],
                                                         persistExternalResource: PersistExternalResource[Tx]
                                                       ) {

  def execute(
               connection: Connection,
               discovered: DiscoveredResource,
               resourceId: UUID,
               externalRefId: UUID,
               now: Instant
             ): Tx[Resource] =
    for {
      environmentId <- getEnvironmentId(connection)
      parentResourceId <-
        resolveParentResourceId(
          connection,
          discovered
        )

      resourceType <- resourceTypeRepository
        .findByCode(discovered.resourceTypeCode)
        .flatMap {
          case Some(resourceType) =>
            resourceType.pure[Tx]

          case None =>
            new IllegalStateException(
              s"Resource type '${discovered.resourceTypeCode}' not found"
            ).raiseError[Tx, domain.resource.ResourceType]
        }

      resource = Resource(
        id = resourceId,
        organizationId = connection.organizationId,
        environmentId = environmentId,
        resourceTypeId = resourceType.id,
        parentResourceId = parentResourceId,
        code = discovered.code,
        name = discovered.name,
        isActive = true,
        createdAt = now,
        updatedAt = now
      )

      externalRef = ExternalRef(
        id = externalRefId,
        organizationId = connection.organizationId,
        connectionId = connection.id,
        externalType = discovered.externalType,
        externalId = discovered.externalId,
        resourceId = resource.id,
        firstSeenAt = now,
        lastSeenAt = now,
        createdAt = now,
        updatedAt = now
      )

      _ <- persistExternalResource.execute(resource, externalRef)
    } yield resource

  private def getEnvironmentId(connection: Connection): Tx[UUID] =
    connection.scope match {
      case ConnectionScope.Environment(_, environmentId) =>
        environmentId.pure[Tx]

      case scope =>
        new IllegalStateException(
          s"Connection ${connection.id} has scope ${scope.code}, but environment scope is required to create a resource"
        ).raiseError[Tx, UUID]
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
