package ru.bitec.app.ops
package application.port

import domain.integration._

import java.time.Instant
import java.util.UUID

/** Whether a new RUNNING session was created, and which abandoned ones were retired first. */
final case class IntegrationSyncClaim(created: Boolean, recovered: List[IntegrationSyncSession])

trait IntegrationSyncSessionRepository[F[_]] {
  /** Fails RUNNING sessions of the integration whose own deadline has passed, then tries to create
    * `session`; the partial unique index admits at most one RUNNING session per integration.
    */
  def recoverStaleAndTryCreate(session: IntegrationSyncSession, at: Instant, errorCode: String,
    errorMessage: String): F[IntegrationSyncClaim]
  /** Completes a still-RUNNING session; false when it was already retired (for example as stale). */
  def complete(organizationId: UUID, id: UUID, finishedAt: Instant, counts: IntegrationSyncCounts): F[Boolean]
  def fail(organizationId: UUID, id: UUID, finishedAt: Instant, errorCode: String, errorMessage: String): F[Boolean]
  def recent(organizationId: UUID, integrationId: UUID, limit: Int): F[List[IntegrationSyncSession]]
  def find(organizationId: UUID, id: UUID): F[Option[IntegrationSyncSession]]
}

trait IntegrationInventoryRepository[F[_]] {
  /** Writes one complete snapshot in a fixed number of statements: every observed object is
    * inserted or updated (and reactivated), every stored object of a complete type that the
    * snapshot does not contain becomes inactive. Returns how many objects became inactive.
    */
  def applySnapshot(organizationId: UUID, integrationId: UUID, sessionId: UUID,
    observation: IntegrationObservation, at: Instant): F[Int]
  def findObject(organizationId: UUID, integrationId: UUID, objectId: UUID,
    forUpdate: Boolean): F[Option[IntegrationInventoryObject]]
  /** Invalidates observations from a replaced endpoint/credential without deleting bindings. */
  def deactivateAll(organizationId: UUID, integrationId: UUID, at: Instant): F[Int]
}

/** A due automatic synchronization, fenced by the token of this claim. */
final case class ClaimedIntegrationSync(organizationId: UUID, integrationId: UUID,
  consecutiveFailures: Long, token: UUID)

trait IntegrationSyncStateRepository[F[_]] {
  def ensure(organizationId: UUID, integrationId: UUID, nextRunAt: Instant): F[Unit]
  def scheduleAt(organizationId: UUID, integrationId: UUID, nextRunAt: Instant): F[Unit]
  /** Claims up to `limit` due schedules of enabled integrations, skipping rows other workers hold. */
  def claimDue(owner: UUID, limit: Int, leaseSeconds: Long, now: Instant): F[List[ClaimedIntegrationSync]]
  /** Releases the claim; false when this claim no longer holds the row. */
  def completeClaimedRun(claim: ClaimedIntegrationSync, nextRunAt: Instant, consecutiveFailures: Long,
    now: Instant): F[Boolean]
}

/** A resource as a binding needs to know it: its type and whether it is still active. */
final case class BindableResource(id: UUID, resourceTypeCode: String, active: Boolean)

trait IntegrationBindingRepository[F[_]] {
  def find(organizationId: UUID, inventoryObjectId: UUID): F[Option[IntegrationResourceBinding]]
  def resource(organizationId: UUID, resourceId: UUID): F[Option[BindableResource]]
  def upsert(binding: IntegrationResourceBinding): F[Unit]
  def delete(organizationId: UUID, inventoryObjectId: UUID): F[Int]
}

// Read models ----------------------------------------------------------------------------------

final case class InventoryFilter(active: Option[Boolean], search: Option[String], state: Option[RemnawaveNodeState],
  limit: Int, offset: Int)
final case class InventoryPage[A](items: List[A], total: Long)

final case class BoundResourceView(id: UUID, code: String, name: String, environmentId: UUID,
  environmentName: String, projectId: UUID, projectName: String)
final case class InventoryItem(obj: IntegrationInventoryObject, binding: Option[BoundResourceView],
  desiredState: Option[DesiredStateView] = None,
  configManagement: Option[ConfigManagementSummary] = None)

final case class ConfigManagementSummary(configurationProfileId: UUID, name: String,
  revisionNumber: Int, status: String)

final case class InventoryTypeCounts(active: Long, inactive: Long)
final case class IntegrationInventorySummary(nodes: InventoryTypeCounts, hosts: InventoryTypeCounts,
  configProfiles: InventoryTypeCounts)

final case class IntegrationOverview(integrationId: UUID, lastSync: Option[IntegrationSyncSession],
  lastSuccessfulSyncAt: Option[Instant], nextRunAt: Option[Instant], inventory: IntegrationInventorySummary,
  desired: DesiredStateCounts = DesiredStateCounts.Empty)

final case class BindingCandidate(id: UUID, code: String, name: String, environmentId: UUID,
  environmentName: String, projectId: UUID, projectName: String)

final case class ResourceIntegrationContext(integrationId: UUID, integrationName: String,
  providerType: IntegrationProviderType, obj: IntegrationInventoryObject,
  managementMode: IntegrationManagementMode = IntegrationManagementMode.Observe,
  desiredState: Option[DesiredStateView] = None)

trait IntegrationInventoryQuery[F[_]] {
  def list(organizationId: UUID, integrationId: UUID, objectType: IntegrationObjectType,
    filter: InventoryFilter): F[InventoryPage[InventoryItem]]
  /** Overview of every integration of the organization, in one statement. */
  def overviews(organizationId: UUID): F[Map[UUID, IntegrationOverview]]
  def bindingCandidates(organizationId: UUID, search: Option[String], limit: Int): F[List[BindingCandidate]]
  def resourceContexts(organizationId: UUID, resourceId: UUID): F[List[ResourceIntegrationContext]]
}
