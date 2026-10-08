package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.integration.IntegrationObjectType
import java.util.UUID

/** One short transaction. Archiving changes only InfraDesk's presentation; it never contacts
  * the provider and never destroys inventory identities or durable recovery history.
  */
final class IntegrationInventoryMaintenance[Tx[_]: MonadThrow](integrations: IntegrationRepository[Tx],
  inventory: IntegrationInventoryRepository[Tx], actions: IntegrationActionRepository[Tx],
  desired: IntegrationDesiredStates[Tx], bindings: IntegrationBindings[Tx],
  time: TimeProvider[Tx], audit: AuditRecorder[Tx]) {
  def archive(actor: ActorContext, integrationId: UUID, objectId: UUID): Tx[Unit] = for {
    _ <- integrations.findByIdForUpdate(actor.organizationId, integrationId).flatMap(_.liftTo[Tx](
      IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found")))
    obj <- inventory.findObject(actor.organizationId, integrationId, objectId, forUpdate = true)
      .flatMap(_.liftTo[Tx](IntegrationError("INTEGRATION_OBJECT_NOT_FOUND", "Object was not found")))
    _ <- MonadThrow[Tx].raiseWhen(obj.isActive)(IntegrationError("INTEGRATION_OBJECT_STILL_PRESENT", "Only absent objects can be removed from the list"))
    busy <- actions.hasActive(actor.organizationId, integrationId)
    _ <- MonadThrow[Tx].raiseWhen(busy)(IntegrationError("INTEGRATION_ACTION_ALREADY_RUNNING", "An operation is active"))
    member <- inventory.hasFleetMembership(actor.organizationId, integrationId, objectId)
    _ <- MonadThrow[Tx].raiseWhen(member)(IntegrationError("INTEGRATION_NODE_FLEET_MEMBERSHIP_REQUIRED_REMOVAL", "Remove the node from its fleet first"))
    _ <- if (obj.objectType == IntegrationObjectType.Node)
      desired.remove(actor, integrationId, objectId) *> bindings.unbind(actor, integrationId, objectId)
      else ().pure[Tx]
    now <- time.now
    changed <- inventory.archiveAbsent(actor.organizationId, integrationId, objectId, now)
    _ <- if (changed) audit.record(actor, AuditAction.IntegrationInventoryArchived,
      AuditTargetType.Integration, Some(integrationId)) else ().pure[Tx]
  } yield ()
}
