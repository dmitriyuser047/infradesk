package ru.bitec.app.ops
package application.port

import domain.integration._

import java.time.Instant
import java.util.UUID

/** A desired state as a reader sees it: the intent plus its derived status. */
final case class DesiredStateView(id: UUID, state: IntegrationDesiredNodeState, version: Long,
  status: IntegrationDesiredStateStatus, lastActionExecutionId: Option[UUID], updatedAt: Instant)

/** How an integration's managed nodes stand, for its overview. */
final case class DesiredStateCounts(managed: Long, compliant: Long, drifted: Long, applying: Long,
  needsAttention: Long)
object DesiredStateCounts { val Empty: DesiredStateCounts = DesiredStateCounts(0, 0, 0, 0, 0) }

/** One claimed desired state with everything a reconciliation decision needs, read in the same
  * statement that claimed it: the intent, the observed node and the actions already made for it.
  */
final case class DesiredStateCandidate(
  id: UUID,
  organizationId: UUID,
  integrationId: UUID,
  inventoryObjectId: UUID,
  state: IntegrationDesiredNodeState,
  version: Long,
  lastAttemptObservationAt: Option[Instant],
  objectActive: Boolean,
  observedDisabled: Boolean,
  lastSeenAt: Instant,
  /** Any QUEUED or RUNNING action on the node, whoever asked for it. */
  activeAction: Boolean,
  lastAction: Option[(IntegrationActionStatus, Option[Instant])],
  /** When the latest action of unknown outcome on this node finished, from any source. */
  latestUnknownFinishedAt: Option[Instant]
)

/** The action one candidate needs, pinned to the intent version and the observation it was decided on. */
final case class DesiredStateIntent(desiredStateId: UUID, version: Long, action: IntegrationActionCode,
  observedAt: Instant)

final case class CreatedDesiredAction(executionId: UUID, desiredStateId: UUID, desiredStateVersion: Long,
  organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID, action: IntegrationActionCode)

trait IntegrationDesiredStateRepository[F[_]] {
  def find(organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID): F[Option[IntegrationDesiredState]]
  /** Writes a new or materially changed intent: the claim is dropped and reconciliation is due now. */
  def save(value: IntegrationDesiredState, at: Instant): F[Unit]
  def delete(organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID): F[Int]
  def deleteAll(organizationId: UUID, integrationId: UUID): F[Int]
  /** Whether a QUEUED or RUNNING action exists on one node, or on any managed node of the integration. */
  def activeActionExists(organizationId: UUID, integrationId: UUID, inventoryObjectId: Option[UUID]): F[Boolean]
  def view(organizationId: UUID, integrationId: UUID, inventoryObjectId: UUID): F[Option[DesiredStateView]]
  /** A new observation was applied: every intent of the integration is due for a decision. */
  def nudge(organizationId: UUID, integrationId: UUID, at: Instant): F[Int]

  /** Claims up to `limit` due intents of managed, observed integrations, skipping rows other
    * workers hold, and returns each with the facts a decision needs — one statement.
    */
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): F[List[DesiredStateCandidate]]
  /** Creates one QUEUED action per intent that is still exactly what was decided on: the same claim,
    * the same version, the same observation, a managed integration and no active action. Everything
    * else is fenced out. One statement for the whole batch.
    */
  def createActions(token: UUID, now: Instant, intents: List[DesiredStateIntent]): F[List[CreatedDesiredAction]]
  /** Releases the claim. An intent nudged while it was claimed stays due. */
  def release(token: UUID, claimedAt: Instant, nextReconcileAt: Instant): F[Int]
}
