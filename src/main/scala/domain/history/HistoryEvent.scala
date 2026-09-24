package ru.bitec.app.ops
package domain.history

import java.time.Instant
import java.util.UUID

/** What happened to the infrastructure, as an operational fact.
  *
  * The journal answers "what happened and when"; the audit journal answers "who changed the
  * configuration"; the tables it points at stay the source of truth for current state. Nothing is
  * ever rebuilt from these rows.
  */
sealed trait HistoryEventType {
  def code: String
}

object HistoryEventType {

  case object ResourceDiscovered extends HistoryEventType { override val code: String = "RESOURCE_DISCOVERED" }
  case object ResourceDeactivated extends HistoryEventType { override val code: String = "RESOURCE_DEACTIVATED" }
  case object IncidentOpened extends HistoryEventType { override val code: String = "INCIDENT_OPENED" }
  case object IncidentResolved extends HistoryEventType { override val code: String = "INCIDENT_RESOLVED" }
  case object OperationRequested extends HistoryEventType { override val code: String = "OPERATION_REQUESTED" }
  case object OperationSucceeded extends HistoryEventType { override val code: String = "OPERATION_SUCCEEDED" }
  case object OperationFailed extends HistoryEventType { override val code: String = "OPERATION_FAILED" }
  case object OperationUnknown extends HistoryEventType { override val code: String = "OPERATION_UNKNOWN" }
  case object SyncFailed extends HistoryEventType { override val code: String = "SYNC_FAILED" }

  val All: List[HistoryEventType] = List(
    ResourceDiscovered,
    ResourceDeactivated,
    IncidentOpened,
    IncidentResolved,
    OperationRequested,
    OperationSucceeded,
    OperationFailed,
    OperationUnknown,
    SyncFailed
  )

  def fromCode(code: String): Either[IllegalArgumentException, HistoryEventType] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported history event type '$code'"))
}

/** Who caused the fact: a person acting through the API, or the runtime itself. */
sealed trait HistoryEventSource {
  def code: String
}

object HistoryEventSource {

  case object User extends HistoryEventSource { override val code: String = "USER" }
  case object System extends HistoryEventSource { override val code: String = "SYSTEM" }

  val All: List[HistoryEventSource] = List(User, System)

  def fromCode(code: String): Either[IllegalArgumentException, HistoryEventSource] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported history event source '$code'"))
}

/** One entry of the journal: typed references only, never a rendered message.
  *
  * Detail lives in the row the entry points at, so the timeline can be presented in any language
  * and the journal never duplicates — or contradicts — the state it describes.
  */
final case class HistoryEvent(
  id: UUID,
  organizationId: UUID,
  eventType: HistoryEventType,
  source: HistoryEventSource,
  resourceId: Option[UUID],
  connectionId: Option[UUID],
  incidentId: Option[UUID],
  operationExecutionId: Option[UUID],
  syncSessionId: Option[UUID],
  actorUserId: Option[UUID],
  occurredAt: Instant,
  createdAt: Instant
)

/** Where a page of the timeline continues: the exact row the previous page ended on. */
final case class HistoryEventCursor(occurredAt: Instant, id: UUID)
