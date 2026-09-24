package ru.bitec.app.ops
package persistence.postgres

import application.port.OperationExecutionRepository
import cats.syntax.all._
import domain.operation._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresOperationExecutionRepository extends OperationExecutionRepository[ConnectionIO] {
  private val columns = fr"id, organization_id, resource_id, actor_user_id, operation," ++
    fr"target_connection_id, target_external_type, target_external_id, status, started_at," ++
    fr"recover_after_at, finished_at, error_code, error_message, created_at, updated_at"

  override def tryCreateRunning(value: OperationExecution): ConnectionIO[Boolean] =
    sql"""insert into operation_execution
      (id, organization_id, resource_id, actor_user_id, operation, target_connection_id,
       target_external_type, target_external_id, status, started_at, recover_after_at, finished_at,
       error_code, error_message, created_at, updated_at)
      values (${value.id}, ${value.organizationId}, ${value.resourceId}, ${value.actorUserId},
       ${value.operation.code}, ${value.targetConnectionId}, ${value.targetExternalType},
       ${value.targetExternalId}, ${value.status.code}, ${value.startedAt}, ${value.recoverAfterAt},
       ${value.finishedAt}, ${value.errorCode}, ${value.errorMessage}, ${value.createdAt},
       ${value.updatedAt})
      on conflict (organization_id, resource_id) where status = 'RUNNING' do nothing"""
      .update.run.map(_ == 1)

  override def recoverStaleRunning(organizationId: UUID, resourceId: UUID, at: Instant,
    errorCode: String, errorMessage: String): ConnectionIO[List[UUID]] =
    sql"""update operation_execution set status = 'UNKNOWN', finished_at = $at,
      error_code = $errorCode, error_message = $errorMessage, updated_at = $at
      where organization_id = $organizationId and resource_id = $resourceId
        and status = 'RUNNING' and recover_after_at <= $at returning id""".query[UUID].to[List]

  override def markSucceeded(organizationId: UUID, id: UUID, finishedAt: Instant): ConnectionIO[Boolean] =
    sql"""update operation_execution set status = 'SUCCEEDED', finished_at = $finishedAt,
      updated_at = $finishedAt where organization_id = $organizationId and id = $id and status = 'RUNNING'"""
      .update.run.map(_ == 1)

  override def markFailed(organizationId: UUID, id: UUID, finishedAt: Instant,
    errorCode: String, errorMessage: String): ConnectionIO[Boolean] =
    sql"""update operation_execution set status = 'FAILED', finished_at = $finishedAt,
      error_code = $errorCode, error_message = $errorMessage, updated_at = $finishedAt
      where organization_id = $organizationId and id = $id and status = 'RUNNING'"""
      .update.run.map(_ == 1)

  override def findById(organizationId: UUID, resourceId: UUID, id: UUID): ConnectionIO[Option[OperationExecution]] =
    (fr"select" ++ columns ++ fr"from operation_execution where organization_id = $organizationId" ++
      fr"and resource_id = $resourceId and id = $id").query[Row].option.flatMap(_.traverse(_.domain.liftTo[ConnectionIO]))

  override def listByResource(organizationId: UUID, resourceId: UUID,
    before: Option[OperationExecutionCursor], limit: Int): ConnectionIO[List[OperationExecution]] = {
    val cursor = before.fold(fr"")(c => fr"and (started_at, id) < (${c.startedAt}, ${c.id})")
    (fr"select" ++ columns ++ fr"from operation_execution where organization_id = $organizationId" ++
      fr"and resource_id = $resourceId" ++ cursor ++ fr"order by started_at desc, id desc limit $limit")
      .query[Row].to[List].flatMap(_.traverse(_.domain.liftTo[ConnectionIO]))
  }

  private final case class Row(id: UUID, organizationId: UUID, resourceId: UUID, actorUserId: UUID,
    operation: String, targetConnectionId: UUID, targetExternalType: String, targetExternalId: String,
    status: String, startedAt: Instant, recoverAfterAt: Instant, finishedAt: Option[Instant],
    errorCode: Option[String], errorMessage: Option[String], createdAt: Instant, updatedAt: Instant) {
    def domain: Either[IllegalArgumentException, OperationExecution] = for {
      op <- ResourceOperationCode.fromCode(operation)
      state <- OperationExecutionStatus.fromCode(status)
    } yield OperationExecution(id, organizationId, resourceId, actorUserId, op, targetConnectionId,
      targetExternalType, targetExternalId, state, startedAt, recoverAfterAt, finishedAt, errorCode,
      errorMessage, createdAt, updatedAt)
  }
}
