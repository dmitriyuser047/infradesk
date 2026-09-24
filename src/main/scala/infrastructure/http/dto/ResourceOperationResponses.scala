package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

final case class AvailableResourceOperationsResponse(operations: List[String], unavailableReason: Option[String])
final case class OperationExecutionResponse(
  id: UUID,
  resourceId: UUID,
  operationCode: String,
  status: String,
  actorUserId: UUID,
  startedAt: Instant,
  finishedAt: Option[Instant],
  errorCode: Option[String],
  errorMessage: Option[String]
)
