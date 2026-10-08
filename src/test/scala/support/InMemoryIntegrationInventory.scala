package ru.bitec.app.ops
package support

import application.port._
import cats.effect.IO
import domain.integration._

import java.time.Instant
import java.util.UUID

/** Every Stage 24B port over one in-memory state, with the same observable rules as PostgreSQL:
  * one RUNNING session per integration, whole-snapshot upsert with deactivation, token-fenced
  * claims and one binding per external object.
  */
final class InMemoryIntegrationInventory {
  private val lock = new Object
  @volatile var sessionRows: Vector[IntegrationSyncSession] = Vector.empty
  @volatile var objects: Vector[IntegrationInventoryObject] = Vector.empty
  private var archivedObjects: Set[UUID] = Set.empty
  @volatile var states: Map[(UUID, UUID), (Instant, Long, Option[UUID])] = Map.empty
  @volatile var bindingRows: Vector[IntegrationResourceBinding] = Vector.empty
  @volatile var resources: Map[UUID, (UUID, BindableResource)] = Map.empty
  @volatile var enabled: Set[UUID] = Set.empty
  @volatile var snapshotWrites = 0

  private def sync[A](body: => A): IO[A] = IO(lock.synchronized(body))

  val sessions: IntegrationSyncSessionRepository[IO] = new IntegrationSyncSessionRepository[IO] {
    override def recoverStaleAndTryCreate(session: IntegrationSyncSession, at: Instant, errorCode: String,
      errorMessage: String): IO[IntegrationSyncClaim] = sync {
      val stale = sessionRows.filter(value => value.organizationId == session.organizationId &&
        value.integrationId == session.integrationId && value.status == IntegrationSyncStatus.Running &&
        value.recoverAfterAt.isBefore(at))
      val recovered = stale.map(_.copy(status = IntegrationSyncStatus.Failed, finishedAt = Some(at),
        errorCode = Some(errorCode), errorMessage = Some(errorMessage)))
      sessionRows = sessionRows.map(value => recovered.find(_.id == value.id).getOrElse(value))
      val busy = sessionRows.exists(value => value.organizationId == session.organizationId &&
        value.integrationId == session.integrationId && value.status == IntegrationSyncStatus.Running)
      if (!busy) sessionRows :+= session
      IntegrationSyncClaim(!busy, recovered.toList)
    }
    override def complete(organizationId: UUID, id: UUID, finishedAt: Instant,
      counts: IntegrationSyncCounts): IO[Boolean] = finish(organizationId, id)(_.copy(
      status = IntegrationSyncStatus.Completed, finishedAt = Some(finishedAt), counts = Some(counts)))
    override def fail(organizationId: UUID, id: UUID, finishedAt: Instant, errorCode: String,
      errorMessage: String): IO[Boolean] = finish(organizationId, id)(_.copy(status = IntegrationSyncStatus.Failed,
      finishedAt = Some(finishedAt), errorCode = Some(errorCode), errorMessage = Some(errorMessage)))
    private def finish(org: UUID, id: UUID)(next: IntegrationSyncSession => IntegrationSyncSession): IO[Boolean] = sync {
      sessionRows.find(value => value.organizationId == org && value.id == id &&
        value.status == IntegrationSyncStatus.Running) match {
        case Some(found) => sessionRows = sessionRows.map(value => if (value.id == found.id) next(value) else value); true
        case None => false
      }
    }
    override def find(organizationId: UUID, id: UUID): IO[Option[IntegrationSyncSession]] = sync {
      sessionRows.find(value => value.organizationId == organizationId && value.id == id)
    }
    override def recent(organizationId: UUID, integrationId: UUID, limit: Int): IO[List[IntegrationSyncSession]] = sync {
      sessionRows.filter(value => value.organizationId == organizationId && value.integrationId == integrationId)
        .sortBy(value => (value.startedAt, value.id.toString)).reverse.take(limit).toList
    }
  }

  val inventory: IntegrationInventoryRepository[IO] = new IntegrationInventoryRepository[IO] {
    override def hasFleetMembership(org: UUID, integration: UUID, obj: UUID): IO[Boolean] = IO.pure(false)
    override def archiveAbsent(org: UUID, integration: UUID, obj: UUID, at: Instant): IO[Boolean] = sync {
      val changed = objects.exists(o => o.organizationId == org && o.integrationId == integration && o.id == obj && !o.isActive) && !archivedObjects(obj)
      if (changed) archivedObjects += obj
      changed
    }
    override def deactivateAll(organizationId: UUID, integrationId: UUID, at: Instant): IO[Int] = sync {
      val affected = objects.count(value => value.organizationId == organizationId &&
        value.integrationId == integrationId && value.isActive)
      objects = objects.map(value => if (value.organizationId == organizationId &&
        value.integrationId == integrationId) value.copy(isActive = false) else value)
      affected
    }
    override def applySnapshot(organizationId: UUID, integrationId: UUID, sessionId: UUID,
      observation: IntegrationObservation, at: Instant): IO[Int] = sync {
      snapshotWrites += 1
      observation.objects.foreach { observed =>
        objects.indexWhere(value => value.organizationId == organizationId && value.integrationId == integrationId &&
          value.objectType == observed.objectType && value.externalId == observed.externalId) match {
          case -1 => objects :+= IntegrationInventoryObject(UUID.randomUUID(), organizationId, integrationId,
            observed.objectType, observed.externalId, observed.displayName, observed.summary, isActive = true, at, at, sessionId)
          case index => objects = objects.updated(index, objects(index).copy(displayName = observed.displayName,
            summary = observed.summary, isActive = true, lastSeenAt = at, lastSeenSyncSessionId = sessionId))
        }
      }
      archivedObjects = archivedObjects.filterNot(id => objects.exists(o => o.id == id && o.isActive))
      val missing = objects.filter(value => value.organizationId == organizationId &&
        value.integrationId == integrationId && value.isActive &&
        observation.completeObjectTypes.contains(value.objectType) && value.lastSeenSyncSessionId != sessionId)
      objects = objects.map(value => if (missing.exists(_.id == value.id)) value.copy(isActive = false) else value)
      missing.size
    }
    override def findObject(organizationId: UUID, integrationId: UUID, objectId: UUID,
      forUpdate: Boolean): IO[Option[IntegrationInventoryObject]] = sync {
      objects.find(value => value.organizationId == organizationId && value.integrationId == integrationId &&
        value.id == objectId)
    }
  }

  val syncState: IntegrationSyncStateRepository[IO] = new IntegrationSyncStateRepository[IO] {
    override def ensure(organizationId: UUID, integrationId: UUID, nextRunAt: Instant): IO[Unit] = sync {
      if (!states.contains((organizationId, integrationId))) states += (organizationId, integrationId) -> ((nextRunAt, 0L, None))
    }
    override def scheduleAt(organizationId: UUID, integrationId: UUID, nextRunAt: Instant): IO[Unit] = sync {
      val claim = states.get((organizationId, integrationId)).flatMap(_._3)
      states += (organizationId, integrationId) -> ((nextRunAt, 0L, claim))
    }
    override def claimDue(owner: UUID, limit: Int, leaseSeconds: Long, now: Instant): IO[List[ClaimedIntegrationSync]] = sync {
      val due = states.toList.filter { case ((_, integration), (next, _, claim)) =>
        enabled.contains(integration) && !next.isAfter(now) && claim.isEmpty
      }.sortBy(_._2._1).take(limit)
      due.map { case (key @ (org, integration), (next, failures, _)) =>
        val token = UUID.randomUUID()
        states += key -> ((next, failures, Some(token)))
        ClaimedIntegrationSync(org, integration, failures, token)
      }
    }
    override def completeClaimedRun(claim: ClaimedIntegrationSync, nextRunAt: Instant, consecutiveFailures: Long,
      now: Instant): IO[Boolean] = sync {
      states.get((claim.organizationId, claim.integrationId)) match {
        case Some((_, _, Some(token))) if token == claim.token =>
          states += (claim.organizationId, claim.integrationId) -> ((nextRunAt, consecutiveFailures, None)); true
        case _ => false
      }
    }
  }

  val bindings: IntegrationBindingRepository[IO] = new IntegrationBindingRepository[IO] {
    override def find(organizationId: UUID, inventoryObjectId: UUID): IO[Option[IntegrationResourceBinding]] = sync {
      bindingRows.find(value => value.organizationId == organizationId && value.inventoryObjectId == inventoryObjectId)
    }
    override def resource(organizationId: UUID, resourceId: UUID): IO[Option[BindableResource]] = sync {
      resources.get(resourceId).filter(_._1 == organizationId).map(_._2)
    }
    override def upsert(binding: IntegrationResourceBinding): IO[Unit] = sync {
      bindingRows = bindingRows.filterNot(_.inventoryObjectId == binding.inventoryObjectId) :+ binding
    }
    override def delete(organizationId: UUID, inventoryObjectId: UUID): IO[Int] = sync {
      val before = bindingRows.size
      bindingRows = bindingRows.filterNot(value => value.organizationId == organizationId &&
        value.inventoryObjectId == inventoryObjectId)
      before - bindingRows.size
    }
  }

  val query: IntegrationInventoryQuery[IO] = new IntegrationInventoryQuery[IO] {
    override def list(organizationId: UUID, integrationId: UUID, objectType: IntegrationObjectType,
      filter: InventoryFilter): IO[InventoryPage[InventoryItem]] = sync {
      val matching = objects.filter(value => value.organizationId == organizationId &&
        value.integrationId == integrationId && value.objectType == objectType && !archivedObjects(value.id) &&
        filter.active.forall(_ == value.isActive) &&
        filter.search.forall(term => value.displayName.toLowerCase.contains(term.toLowerCase)))
        .sortBy(value => (value.displayName, value.id.toString))
      InventoryPage(matching.slice(filter.offset, filter.offset + filter.limit).map(value => InventoryItem(value,
        bindingRows.find(_.inventoryObjectId == value.id).map(binding => BoundResourceView(binding.resourceId, "node",
          "Node", UUID.randomUUID(), "Env", UUID.randomUUID(), "Project")))).toList, matching.size.toLong)
    }
    override def overviews(organizationId: UUID): IO[Map[UUID, IntegrationOverview]] = IO.pure(Map.empty)
    override def bindingCandidates(organizationId: UUID, search: Option[String], limit: Int): IO[List[BindingCandidate]] =
      sync {
        resources.values.filter { case (org, value) => org == organizationId && value.active &&
          value.resourceTypeCode == "NODE" }.map { case (_, value) =>
          BindingCandidate(value.id, "node", "Node", UUID.randomUUID(), "Env", UUID.randomUUID(), "Project")
        }.toList.take(limit)
      }
    override def resourceContexts(organizationId: UUID, resourceId: UUID): IO[List[ResourceIntegrationContext]] =
      IO.pure(Nil)
  }
}

/** For tests of integrations that manage no nodes: there is never a desired state. */
object NoDesiredStates extends IntegrationDesiredStateRepository[IO] {
  override def find(organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID): IO[Option[IntegrationDesiredState]] =
    IO.pure(None)
  override def save(value: IntegrationDesiredState, at: Instant): IO[Unit] = IO.raiseError(new UnsupportedOperationException)
  override def delete(organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID): IO[Int] = IO.pure(0)
  override def deleteAll(organizationId: UUID, integrationId: UUID): IO[Int] = IO.pure(0)
  override def activeActionExists(organizationId: UUID, integrationId: UUID, inventoryObjectId: Option[UUID]): IO[Boolean] =
    IO.pure(false)
  override def view(organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID): IO[Option[DesiredStateView]] =
    IO.pure(None)
  override def nudge(organizationId: UUID, integrationId: UUID, at: Instant): IO[Int] = IO.pure(0)
  override def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): IO[List[DesiredStateCandidate]] =
    IO.pure(Nil)
  override def createActions(token: UUID, now: Instant, intents: List[DesiredStateIntent]): IO[List[CreatedDesiredAction]] =
    IO.pure(Nil)
  override def release(token: UUID, claimedAt: Instant, nextReconcileAt: Instant): IO[Int] = IO.pure(0)
}
