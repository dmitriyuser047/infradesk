package ru.bitec.app.ops
package persistence.postgres

import application.port.{SecurityEventQuery, SecurityEventRepository}
import cats.syntax.all._
import domain.auth.{SecurityEvent, SecurityEventCursor, SecurityEventType}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresSecurityEventRepository
  extends SecurityEventRepository[ConnectionIO] with SecurityEventQuery[ConnectionIO] {
  override def save(event: SecurityEvent): ConnectionIO[Unit] =
    sql"""insert into account_security_event
          (id, user_id, event_type, occurred_at, session_id, source, affected_session_count)
          values (${event.id}, ${event.userId}, ${event.eventType.code}, ${event.occurredAt},
                  ${event.sessionId}, ${event.source}, ${event.affectedSessionCount})""".update.run.void

  override def listByUser(userId: UUID, before: Option[SecurityEventCursor], limit: Int): ConnectionIO[List[SecurityEvent]] = {
    val cursor = before.fold(fr"")(value => fr"and (occurred_at, id) < (${value.occurredAt}, ${value.id})")
    (fr"select id, user_id, event_type, occurred_at, session_id, source, affected_session_count " ++
      fr"from account_security_event where user_id = $userId " ++ cursor ++
      fr"order by occurred_at desc, id desc limit $limit")
      .query[(UUID, UUID, String, Instant, Option[UUID], Option[String], Option[Int])].to[List]
      .flatMap(_.traverse { case (id, owner, kind, at, session, source, count) =>
        SecurityEventType.fromCode(kind).map(eventType =>
          SecurityEvent(id, owner, eventType, at, session, source, count)).liftTo[ConnectionIO]
      })
  }

  override def deleteBefore(cutoff: Instant): ConnectionIO[Int] =
    sql"delete from account_security_event where occurred_at < $cutoff".update.run
}
