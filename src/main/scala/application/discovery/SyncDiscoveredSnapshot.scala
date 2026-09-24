package ru.bitec.app.ops
package application.discovery

import application.history.{HistoryEntry, HistoryRecorder}
import application.port.{ExternalRefRepository, ResourceRepository, SyncSessionRepository}
import application.resource.{PendingMetricObservation, RecordResourceObservations}
import domain.connection.Connection
import domain.externalref.ExternalRef
import domain.history.HistoryEventType
import domain.resource.Resource
import domain.sync.SyncSession

import cats.MonadThrow
import cats.syntax.all._

import java.time.Instant
import java.util.UUID

final case class PendingDiscoveredResource(
                                            discovered: DiscoveredResource,
                                            resourceId: UUID,
                                            externalRefId: UUID,
                                            pendingMetricObservations: List[PendingMetricObservation] = List.empty
                                          )

final class SyncDiscoveredSnapshot[Tx[_]: MonadThrow](
                                                        createDiscoveredResource: CreateDiscoveredResource[Tx],
                                                        reconcileDiscoveredResource: ReconcileDiscoveredResource[Tx],
                                                        externalRefRepository: ExternalRefRepository[Tx],
                                                        resourceRepository: ResourceRepository[Tx],
                                                        syncSessionRepository: SyncSessionRepository[Tx],
                                                        recordResourceObservations: RecordResourceObservations[Tx],
                                                        historyRecorder: HistoryRecorder[Tx]
                                                      ) {

  def execute(
               connection: Connection,
               syncSession: SyncSession,
               discoveredResources: List[PendingDiscoveredResource],
               completeExternalTypes: Set[String],
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
            if completeExternalTypes.contains(externalRef.externalType) &&
              !seenIdentities.contains(identity(externalRef.externalType, externalRef.externalId)) &&
              !seenResourceIds.contains(externalRef.resourceId) =>
          externalRef.resourceId
      }.distinct

      // Only a resource this call actually deactivated becomes a fact; a partial snapshot
      // deactivates nothing and therefore journals nothing.
      deactivatedResourceIds <- missingResourceIds.traverseFilter { resourceId =>
        resourceRepository.deactivateIfExclusiveToConnection(
          connection.organizationId,
          resourceId,
          connection.id,
          completedAt
        ).map(deactivated => Option.when(deactivated)(resourceId))
      }

      resources <- discoveredResources.traverse { pending =>
        val known = externalRefsByIdentity.get(identity(pending.discovered))
        val resource =
          known match {
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

        resource.flatTap { reconciledResource =>
          recordResourceObservations.execute(
            reconciledResource,
            pending.pendingMetricObservations,
            completedAt
          )
        }
      }

      newResourceIds = discoveredResources.collect {
        case pending if !externalRefsByIdentity.contains(identity(pending.discovered)) =>
          pending.resourceId
      }

      // Reconciliation decides what the facts are; the repositories only store rows. Both kinds
      // of fact are written in this transaction, in one batch.
      _ <- historyRecorder.recordAll(
        newResourceIds.map(resourceId =>
          lifecycleEntry(connection, syncSession, HistoryEventType.ResourceDiscovered, resourceId,
            completedAt)
        ) ++
          deactivatedResourceIds.map(resourceId =>
            lifecycleEntry(connection, syncSession, HistoryEventType.ResourceDeactivated,
              resourceId, completedAt)
          )
      )

      _ <- syncSessionRepository.complete(
        connection.organizationId,
        syncSession.id,
        completedAt
      )
    } yield resources

  private def lifecycleEntry(
                              connection: Connection,
                              syncSession: SyncSession,
                              eventType: HistoryEventType,
                              resourceId: UUID,
                              occurredAt: Instant
                            ): HistoryEntry =
    HistoryEntry
      .system(connection.organizationId, eventType, occurredAt)
      .copy(
        resourceId = Some(resourceId),
        connectionId = Some(connection.id),
        syncSessionId = Some(syncSession.id)
      )

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
