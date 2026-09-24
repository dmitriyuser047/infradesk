package ru.bitec.app.ops
package domain.audit

import java.time.Instant
import java.util.UUID

/** What a user did to the configuration of an organization. */
sealed trait AuditAction {
  def code: String
}

object AuditAction {

  case object ProjectCreated extends AuditAction { override val code: String = "PROJECT_CREATED" }
  case object EnvironmentCreated extends AuditAction { override val code: String = "ENVIRONMENT_CREATED" }
  case object ConnectionCreated extends AuditAction { override val code: String = "CONNECTION_CREATED" }
  case object ConnectionUpdated extends AuditAction { override val code: String = "CONNECTION_UPDATED" }
  case object ConnectionDeleted extends AuditAction { override val code: String = "CONNECTION_DELETED" }
  case object MonitorRuleCreated extends AuditAction { override val code: String = "MONITOR_RULE_CREATED" }
  case object MonitorRuleUpdated extends AuditAction { override val code: String = "MONITOR_RULE_UPDATED" }

  /** The user asked for a synchronization; whether it succeeded is sync session history. */
  case object ManualSyncRequested extends AuditAction { override val code: String = "MANUAL_SYNC_REQUESTED" }

  val All: List[AuditAction] = List(
    ProjectCreated,
    EnvironmentCreated,
    ConnectionCreated,
    ConnectionUpdated,
    ConnectionDeleted,
    MonitorRuleCreated,
    MonitorRuleUpdated,
    ManualSyncRequested
  )

  def fromCode(code: String): Either[IllegalArgumentException, AuditAction] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported audit action '$code'"))
}

/** The kind of object an action was applied to. */
sealed trait AuditTargetType {
  def code: String
}

object AuditTargetType {

  case object Project extends AuditTargetType { override val code: String = "PROJECT" }
  case object Environment extends AuditTargetType { override val code: String = "ENVIRONMENT" }
  case object Connection extends AuditTargetType { override val code: String = "CONNECTION" }
  case object MonitorRule extends AuditTargetType { override val code: String = "MONITOR_RULE" }

  val All: List[AuditTargetType] = List(Project, Environment, Connection, MonitorRule)

  def fromCode(code: String): Either[IllegalArgumentException, AuditTargetType] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported audit target type '$code'"))
}

/** One journal entry: who did what, to which object, and when.
  *
  * The entry deliberately carries identifiers only. Old and new values, request bodies,
  * configuration and secrets of any kind stay out of the journal.
  */
final case class AuditEvent(
  id: UUID,
  organizationId: UUID,
  actorUserId: UUID,
  action: AuditAction,
  targetType: AuditTargetType,
  targetId: Option[UUID],
  occurredAt: Instant,
  createdAt: Instant
)

/** Where a page of the journal continues: the last row the previous page returned. */
final case class AuditCursor(occurredAt: Instant, id: UUID)
