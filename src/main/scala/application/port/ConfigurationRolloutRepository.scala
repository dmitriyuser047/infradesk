package ru.bitec.app.ops
package application.port

import domain.configuration.{
  ConfigurationRollout,
  ConfigurationRolloutItem,
  ConfigurationRolloutItemState,
  ConfigurationRolloutItemView,
  ConfigurationRolloutListItem,
  ConfigurationRolloutState
}

import java.time.Instant
import java.util.UUID

sealed trait ConfigurationRolloutInsert
object ConfigurationRolloutInsert {
  case object Written extends ConfigurationRolloutInsert
  final case class Repeated(id: UUID) extends ConfigurationRolloutInsert
}

/** Rollouts and their items. Orchestration updates are fenced on the rollout lease token. */
trait ConfigurationRolloutRepository[F[_]] {
  def insert(rollout: ConfigurationRollout, items: List[ConfigurationRolloutItem]): F[ConfigurationRolloutInsert]
  def find(organizationId: UUID, id: UUID): F[Option[ConfigurationRollout]]
  def findRequest(organizationId: UUID, requestId: UUID): F[Option[ConfigurationRollout]]
  def items(organizationId: UUID, rolloutId: UUID): F[List[ConfigurationRolloutItem]]

  /** Items with their child deployments and resource names, in a fixed number of statements. */
  def view(organizationId: UUID, rolloutId: UUID): F[Option[(ConfigurationRolloutListItem, List[ConfigurationRolloutItemView])]]
  def history(organizationId: UUID, profileId: Option[UUID], before: Option[(Instant, UUID)],
              limit: Int): F[List[ConfigurationRolloutListItem]]
  /** Claims one rollout that is due, other than those in `exclude` (already handled this pass). */
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant, exclude: List[UUID],
            scope: Option[UUID] = None): F[Option[ConfigurationRollout]]
  def renew(organizationId: UUID, id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]
  def setItem(organizationId: UUID, rolloutId: UUID, token: UUID, itemId: UUID,
              expected: ConfigurationRolloutItemState, next: ConfigurationRolloutItemState,
              deploymentId: Option[UUID], now: Instant): F[Boolean]
  /** Moves the rollout to `state` and gives up its lease; the next claim continues it. */
  def setState(organizationId: UUID, id: UUID, token: UUID, state: ConfigurationRolloutState,
               now: Instant, nextActionAt: Option[Instant], lastPausedPosition: Option[Int]): F[Boolean]
  /** Stops future items; with `rollback`, also rolls back every node the rollout applied. */
  def cancel(organizationId: UUID, id: UUID, rollback: Boolean, now: Instant): F[Boolean]
}
