package ru.bitec.app.ops
package persistence.postgres

import application.port.AuditEventRepository
import cats.syntax.all._
import domain.audit.{AuditAction, AuditCursor, AuditEvent, AuditTargetType}
import org.typelevel.doobie.{ConnectionIO, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

import PostgresAuditEventRepository.AuditEventRow

/** The journal has inserts and one listing query; it deliberately has no update and no delete. */
final class PostgresAuditEventRepository extends AuditEventRepository[ConnectionIO] {

  private val columns =
    fr"id, organization_id, actor_user_id, action, target_type, target_id, occurred_at, created_at"

  override def save(event: AuditEvent): ConnectionIO[Unit] = saveAll(List(event))

  override def saveAll(events: List[AuditEvent]): ConnectionIO[Unit] =
    if (events.isEmpty) ().pure[ConnectionIO]
    else
      Update[(UUID, UUID, UUID, String, String, Option[UUID], Instant, Instant)]("""
        insert into audit_event (
          id, organization_id, actor_user_id, action, target_type, target_id, occurred_at, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?)
      """).updateMany(events.map(event =>
        (event.id, event.organizationId, event.actorUserId, event.action.code,
          event.targetType.code, event.targetId, event.occurredAt, event.createdAt)
      )).void

  /** One query per page. The cursor is the (occurred_at, id) pair of the last row returned, so
    * events sharing a timestamp still paginate deterministically.
    */
  override def listByOrganization(
    organizationId: UUID,
    before: Option[AuditCursor],
    limit: Int
  ): ConnectionIO[List[AuditEvent]] = {
    val cursor = before.fold(fr"")(value =>
      fr"and (occurred_at, id) < (${value.occurredAt}, ${value.id})"
    )

    (fr"select" ++ columns ++ fr"from audit_event where organization_id = $organizationId" ++
      cursor ++ fr"order by occurred_at desc, id desc limit $limit")
      .query[AuditEventRow]
      .to[List]
      .flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))
  }
}

object PostgresAuditEventRepository {

  private[postgres] final case class AuditEventRow(
    id: UUID,
    organizationId: UUID,
    actorUserId: UUID,
    action: String,
    targetType: String,
    targetId: Option[UUID],
    occurredAt: Instant,
    createdAt: Instant
  ) {
    def toDomain: Either[IllegalArgumentException, AuditEvent] =
      for {
        typedAction <- AuditAction.fromCode(action)
        typedTargetType <- AuditTargetType.fromCode(targetType)
      } yield AuditEvent(id, organizationId, actorUserId, typedAction, typedTargetType, targetId,
        occurredAt, createdAt)
  }
}
