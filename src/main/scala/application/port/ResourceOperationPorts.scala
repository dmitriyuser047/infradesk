package ru.bitec.app.ops
package application.port

import domain.connection.Connection
import domain.operation.{OperationExecution, OperationExecutionCursor, ResourceOperationCode}

import java.time.Instant
import java.util.UUID

trait OperationExecutionRepository[F[_]] {
  def tryCreateRunning(execution: OperationExecution): F[Boolean]
  def recoverStaleRunning(organizationId: UUID, resourceId: UUID, staleBefore: Instant,
                          recoveredAt: Instant, errorCode: String, errorMessage: String): F[List[UUID]]
  def markSucceeded(organizationId: UUID, id: UUID, finishedAt: Instant): F[Boolean]
  def markFailed(organizationId: UUID, id: UUID, finishedAt: Instant,
                 errorCode: String, errorMessage: String): F[Boolean]
  def findById(organizationId: UUID, resourceId: UUID, id: UUID): F[Option[OperationExecution]]
  def listByResource(organizationId: UUID, resourceId: UUID,
                     before: Option[OperationExecutionCursor], limit: Int): F[List[OperationExecution]]
}

final case class ResourceOperationTarget(connection: Connection, externalType: String, externalId: String)
final case class ResourceOperationTargetProjection(
  resourceId: UUID,
  resourceTypeCode: String,
  resourceActive: Boolean,
  targets: List[ResourceOperationTarget]
)

trait ResourceOperationTargetQuery[F[_]] {
  /** One tenant-scoped SQL statement returns the resource and all actionable SSH mappings. */
  def find(organizationId: UUID, resourceId: UUID): F[Option[ResourceOperationTargetProjection]]
}

trait ResourceOperationExecutor[F[_]] {
  def execute(target: ResourceOperationTarget, operation: ResourceOperationCode): F[Unit]
}
