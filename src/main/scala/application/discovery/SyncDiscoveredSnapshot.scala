package ru.bitec.app.ops
package application.discovery

import application.port.{ExternalRefRepository, ResourceRepository, SyncSessionRepository}
import domain.connection.Connection
import domain.externalref.ExternalRef
import domain.resource.Resource
import domain.sync.SyncSession

import cats.MonadThrow
import cats.syntax.all._

import java.time.Instant
import java.util.UUID

final case class PendingDiscoveredResource(
                                            discovered: DiscoveredResource,
                                            resourceId: UUID,
                                            externalRefId: UUID
                                          )

final class SyncDiscoveredSnapshot[Tx[_]: MonadThrow](
                                                        createDiscoveredResource: CreateDiscoveredResource[Tx],
                                                        reconcileDiscoveredResource: ReconcileDiscoveredResource[Tx],
                                                        externalRefRepository: ExternalRefRepository[Tx],
                                                        resourceRepository: ResourceRepository[Tx],
                                                        syncSessionRepository: SyncSessionRepository[Tx]
                                                      ) {

  def execute(
               connection: Connection,
               syncSession: SyncSession,
               discoveredResources: List[PendingDiscoveredResource],
               completedAt: Instant
             ): Tx[List[Resource]] =
    for {
      _ <- validateUniqueIdentities(discoveredResources)
      externalRefs <- externalRefRepository.findByConnection(
        connection.organizationId,
        connection.id
      )

      externalRefsByIdentity = externalRefs.map { externalRef =>
        identity(externalRef.externalType, externalRef.externalId) -> externalRef
      }.toMap

      seenIdentities = discoveredResources.map(resource => identity(resource.discovered))
      seenResourceIds = externalRefs.collect {
        case externalRef if seenIdentities.contains(identity(externalRef.externalType, externalRef.externalId)) =>
          externalRef.resourceId
      }.toSet

      missingResourceIds = externalRefs.collect {
        case externalRef
            if !seenIdentities.contains(identity(externalRef.externalType, externalRef.externalId)) &&
              !seenResourceIds.contains(externalRef.resourceId) =>
          externalRef.resourceId
      }.distinct

      _ <- missingResourceIds.traverse_ { resourceId =>
        resourceRepository.deactivateIfExclusiveToConnection(
          connection.organizationId,
          resourceId,
          connection.id,
          completedAt
        )
      }

      resources <- discoveredResources.traverse { pending =>
        externalRefsByIdentity.get(identity(pending.discovered)) match {
          case Some(externalRef) =>
            reconcileDiscoveredResource.execute(
              connection,
              pending.discovered,
              externalRef,
              syncSession.id,
              completedAt
            )

          case None =>
            createDiscoveredResource.execute(
              connection,
              pending.discovered,
              pending.resourceId,
              pending.externalRefId,
              syncSession.id,
              completedAt
            )
        }
      }

      _ <- syncSessionRepository.complete(
        connection.organizationId,
        syncSession.id,
        completedAt
      )
    } yield resources

  private def validateUniqueIdentities(
                                        discoveredResources: List[PendingDiscoveredResource]
                                      ): Tx[Unit] = {
    val identities = discoveredResources.map(resource => identity(resource.discovered))

    if (identities.distinct.size == identities.size)
      ().pure[Tx]
    else
      new IllegalArgumentException(
        s"Discovery for connection contains duplicate external identities: ${identities.mkString(", ")}"
      ).raiseError[Tx, Unit]
  }

  private def identity(discovered: DiscoveredResource): DiscoveredExternalIdentity =
    identity(discovered.externalType, discovered.externalId)

  private def identity(externalType: String, externalId: String): DiscoveredExternalIdentity =
    DiscoveredExternalIdentity(externalType, externalId)
}
