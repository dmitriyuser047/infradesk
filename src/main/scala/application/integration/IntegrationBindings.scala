package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{IdGenerator, IntegrationBindingRepository, IntegrationInventoryRepository,
  IntegrationRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.integration.{IntegrationInventoryObject, IntegrationObjectType, IntegrationResourceBinding}

import java.util.UUID

/** Manual links from an external node to an InfraDesk NODE. Nothing binds on its own: no address
  * matching, no automatic creation. Each method is one transaction.
  */
final class IntegrationBindings[Tx[_]: MonadThrow](integrations: IntegrationRepository[Tx],
  inventory: IntegrationInventoryRepository[Tx], bindings: IntegrationBindingRepository[Tx],
  ids: IdGenerator[Tx], time: TimeProvider[Tx], audit: AuditRecorder[Tx]) {

  /** Reviewed onboarding may create a link, but can never replace somebody else's link. */
  def bindCreated(actor: ActorContext, integrationId: UUID, objectId: UUID, resourceId: UUID): Tx[IntegrationResourceBinding] = for {
    node <- lockedNode(actor.organizationId,integrationId,objectId)
    existing <- bindings.find(actor.organizationId,node.id)
    _ <- MonadThrow[Tx].raiseUnless(existing.forall(_.resourceId==resourceId))(
      IntegrationError("REMNAWAVE_ONBOARDING_BINDING_CONFLICT","The node is already bound elsewhere"))
    result <- bind(actor,integrationId,objectId,resourceId)
  } yield result

  /** Binding to the resource already bound changes nothing, not even the update time, and is not
    * audited. Binding to another resource replaces the link in place.
    */
  def bind(actor: ActorContext, integrationId: UUID, objectId: UUID, resourceId: UUID): Tx[IntegrationResourceBinding] =
    for {
      node <- lockedNode(actor.organizationId, integrationId, objectId)
      resource <- bindings.resource(actor.organizationId, resourceId)
      _ <- resource.filter(value => value.active && value.resourceTypeCode == "NODE")
        .liftTo[Tx](IntegrationError(InvalidResourceCode, "Only an active NODE resource can be bound")).void
      existing <- bindings.find(actor.organizationId, node.id)
      result <- existing match {
        case Some(current) if current.resourceId == resourceId => current.pure[Tx]
        case _ => for {
          id <- ids.nextId
          now <- time.now
          next = existing.fold(IntegrationResourceBinding(id, actor.organizationId, integrationId, node.id,
            resourceId, actor.userId, now, now))(current =>
            current.copy(resourceId = resourceId, createdByUserId = actor.userId, updatedAt = now))
          _ <- bindings.upsert(next)
          _ <- audit.record(actor, AuditAction.IntegrationResourceBound, AuditTargetType.Integration, Some(integrationId))
        } yield next
      }
    } yield result

  /** Removing a binding that does not exist is not an error and is not audited. */
  def unbind(actor: ActorContext, integrationId: UUID, objectId: UUID): Tx[Unit] = for {
    node <- lockedNode(actor.organizationId, integrationId, objectId)
    removed <- bindings.delete(actor.organizationId, node.id)
    _ <- if (removed > 0)
      audit.record(actor, AuditAction.IntegrationResourceUnbound, AuditTargetType.Integration, Some(integrationId))
    else ().pure[Tx]
  } yield ()

  // The object row is locked so concurrent bind and unbind of one node are serialized.
  private def lockedNode(org: UUID, integrationId: UUID, objectId: UUID): Tx[IntegrationInventoryObject] = for {
    _ <- integrations.findByIdForUpdate(org, integrationId).flatMap(
      _.liftTo[Tx](IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found")))
    found <- inventory.findObject(org, integrationId, objectId, forUpdate = true)
    node <- found.filter(_.objectType == IntegrationObjectType.Node)
      .liftTo[Tx](IntegrationError(ObjectNotFoundCode, "Integration object was not found"))
  } yield node

  private val InvalidResourceCode = "INTEGRATION_BINDING_INVALID_RESOURCE"
  private val ObjectNotFoundCode = "INTEGRATION_OBJECT_NOT_FOUND"
}
