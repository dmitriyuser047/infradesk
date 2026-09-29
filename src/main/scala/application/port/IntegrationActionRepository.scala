package ru.bitec.app.ops
package application.port

import domain.integration.IntegrationActionExecution
import java.time.Instant
import java.util.UUID

trait IntegrationActionRepository[F[_]] {
  /** Uses database uniqueness for both request idempotency and the active object slot. */
  def insertOrFind(value: IntegrationActionExecution): F[(IntegrationActionExecution, Boolean)]
  def findByRequest(organizationId: UUID, requestId: UUID): F[Option[IntegrationActionExecution]]
  def find(organizationId: UUID, integrationId: UUID, id: UUID): F[Option[IntegrationActionExecution]]
  def recent(organizationId: UUID, integrationId: UUID, limit: Int): F[List[IntegrationActionExecution]]
  def latestUnknownFinishedAt(organizationId: UUID, integrationId: UUID,
    inventoryObjectId: UUID): F[Option[Instant]]
  /** Called while holding the integration row lock during endpoint/credential changes or deletion. */
  def hasActive(organizationId: UUID, integrationId: UUID): F[Boolean]
  /** Retires stale claims as UNKNOWN, then claims at most limit queued rows. */
  def recoverAndClaim(owner: UUID, token: UUID, at: Instant, recoverAfter: Instant,
    limit: Int): F[(Int, List[IntegrationActionExecution])]
  def complete(value: IntegrationActionExecution, token: UUID, at: Instant,
    status: String, errorCode: Option[String], errorMessage: Option[String]): F[Boolean]
}
