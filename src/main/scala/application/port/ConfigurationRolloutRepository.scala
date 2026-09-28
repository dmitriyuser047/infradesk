package ru.bitec.app.ops
package application.port

import domain.configuration.{ConfigurationRollout, ConfigurationRolloutItem, ConfigurationRolloutItemState, ConfigurationRolloutState}

import java.time.Instant
import java.util.UUID

sealed trait ConfigurationRolloutInsert
object ConfigurationRolloutInsert {
  case object Written extends ConfigurationRolloutInsert
  final case class Repeated(id: UUID) extends ConfigurationRolloutInsert
}

trait ConfigurationRolloutRepository[F[_]] {
  def insert(rollout: ConfigurationRollout, items: List[ConfigurationRolloutItem]): F[ConfigurationRolloutInsert]
  def find(organizationId: UUID, id: UUID): F[Option[ConfigurationRollout]]
  def findRequest(organizationId: UUID, requestId: UUID): F[Option[ConfigurationRollout]]
  def items(organizationId: UUID, rolloutId: UUID): F[List[ConfigurationRolloutItem]]
  def history(organizationId: UUID, before: Option[(Instant, UUID)], limit: Int): F[List[ConfigurationRollout]]
  def claim(owner: UUID, token: UUID, now: Instant, until: Instant): F[Option[ConfigurationRollout]]
  def renew(organizationId: UUID, id: UUID, token: UUID, now: Instant, until: Instant): F[Boolean]
  def setItem(organizationId: UUID, rolloutId: UUID, token: UUID, itemId: UUID,
              expected: ConfigurationRolloutItemState, next: ConfigurationRolloutItemState,
              deploymentId: Option[UUID], now: Instant): F[Boolean]
  def setState(organizationId: UUID, id: UUID, token: UUID, state: ConfigurationRolloutState,
               now: Instant, nextActionAt: Option[Instant], lastPausedPosition: Option[Int]): F[Boolean]
  def cancel(organizationId: UUID, id: UUID): F[Boolean]
}
