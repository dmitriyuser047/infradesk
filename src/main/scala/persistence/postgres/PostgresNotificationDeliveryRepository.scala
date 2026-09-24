package ru.bitec.app.ops
package persistence.postgres

import application.port.NotificationDeliveryRepository
import cats.syntax.all._
import domain.incident.IncidentReason
import domain.notification.{
  NotificationChannel,
  NotificationDelivery,
  NotificationDeliveryStatus,
  NotificationEventType
}
import org.typelevel.doobie.{ConnectionIO, Query0, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

import PostgresNotificationDeliveryRepository.DeliveryRow

final class PostgresNotificationDeliveryRepository extends NotificationDeliveryRepository[ConnectionIO] {

  private val columns =
    fr"""
      id, organization_id, incident_id, resource_id, monitor_rule_id,
      event_type, reason, channel, occurred_at, status, attempt_count, next_attempt_at,
      claimed_by, claimed_until, sent_at, last_error_code, created_at, updated_at
    """

  private def rows(query: Query0[DeliveryRow]): ConnectionIO[List[NotificationDelivery]] =
    query.to[List].flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  /** Recording the same incident transition twice is a no-op: the unique event index decides. */
  override def saveAll(deliveries: List[NotificationDelivery]): ConnectionIO[Unit] =
    if (deliveries.isEmpty) ().pure[ConnectionIO]
    else
      Update[(UUID, UUID, UUID, UUID, UUID, String, String, String, Instant, String, Long, Instant,
        Option[UUID], Option[Instant], Option[Instant], Option[String], Instant, Instant)]("""
        insert into notification_delivery (
          id, organization_id, incident_id, resource_id, monitor_rule_id,
          event_type, reason, channel, occurred_at, status, attempt_count, next_attempt_at,
          claimed_by, claimed_until, sent_at, last_error_code, created_at, updated_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (organization_id, incident_id, event_type, channel) do nothing
      """).updateMany(deliveries.map(delivery =>
        (delivery.id, delivery.organizationId, delivery.incidentId, delivery.resourceId,
          delivery.monitorRuleId, delivery.eventType.code, delivery.reason.code, delivery.channel.code,
          delivery.occurredAt, delivery.status.code, delivery.attemptCount, delivery.nextAttemptAt,
          delivery.claimedBy, delivery.claimedUntil, delivery.sentAt, delivery.lastErrorCode,
          delivery.createdAt, delivery.updatedAt)
      )).void

  override def findById(organizationId: UUID, id: UUID): ConnectionIO[Option[NotificationDelivery]] =
    (fr"select" ++ columns ++
      fr"from notification_delivery where organization_id = $organizationId and id = $id")
      .query[DeliveryRow]
      .option
      .flatMap {
        case Some(row) => row.toDomain.map(Option(_)).liftTo[ConnectionIO]
        case None => none[NotificationDelivery].pure[ConnectionIO]
      }

  /** One statement: pick due rows that nobody holds, lease them, return them.
    *
    * `for update skip locked` lets several dispatcher instances claim disjoint batches, and the
    * lease is measured on the database clock so instances do not need synchronized clocks.
    */
  override def claimPending(
    claimedBy: UUID,
    limit: Int,
    leaseSeconds: Long
  ): ConnectionIO[List[NotificationDelivery]] =
    rows(
      sql"""
        with due as (
          select id
          from notification_delivery
          where status = 'PENDING'
            and next_attempt_at <= current_timestamp
            and (claimed_until is null or claimed_until <= current_timestamp)
          order by next_attempt_at, created_at, id
          for update skip locked
          limit $limit
        )
        update notification_delivery d
        set claimed_by = $claimedBy,
            claimed_until = current_timestamp + ($leaseSeconds * interval '1 second'),
            updated_at = current_timestamp
        from due
        where d.id = due.id
        returning d.id, d.organization_id, d.incident_id, d.resource_id, d.monitor_rule_id,
                  d.event_type, d.reason, d.channel, d.occurred_at, d.status, d.attempt_count,
                  d.next_attempt_at, d.claimed_by, d.claimed_until, d.sent_at, d.last_error_code,
                  d.created_at, d.updated_at
      """.query[DeliveryRow]
    )

  override def markSent(
    organizationId: UUID,
    id: UUID,
    claimedBy: UUID,
    sentAt: Instant
  ): ConnectionIO[Boolean] =
    sql"""
      update notification_delivery
      set status = ${NotificationDeliveryStatus.Sent.code},
          sent_at = $sentAt,
          claimed_by = null,
          claimed_until = null,
          attempt_count = attempt_count + 1,
          last_error_code = null,
          updated_at = $sentAt
      where id = $id
        and organization_id = $organizationId
        and claimed_by = $claimedBy
    """.update.run.map(_ == 1)

  override def reschedule(
    organizationId: UUID,
    id: UUID,
    claimedBy: UUID,
    attemptCount: Long,
    nextAttemptAt: Instant,
    errorCode: String,
    updatedAt: Instant
  ): ConnectionIO[Boolean] =
    sql"""
      update notification_delivery
      set attempt_count = $attemptCount,
          next_attempt_at = $nextAttemptAt,
          last_error_code = $errorCode,
          claimed_by = null,
          claimed_until = null,
          updated_at = $updatedAt
      where id = $id
        and organization_id = $organizationId
        and claimed_by = $claimedBy
    """.update.run.map(_ == 1)

  override def markDead(
    organizationId: UUID,
    id: UUID,
    claimedBy: UUID,
    attemptCount: Long,
    errorCode: String,
    updatedAt: Instant
  ): ConnectionIO[Boolean] =
    sql"""
      update notification_delivery
      set status = ${NotificationDeliveryStatus.Dead.code},
          attempt_count = $attemptCount,
          last_error_code = $errorCode,
          claimed_by = null,
          claimed_until = null,
          updated_at = $updatedAt
      where id = $id
        and organization_id = $organizationId
        and claimed_by = $claimedBy
    """.update.run.map(_ == 1)
}

object PostgresNotificationDeliveryRepository {

  private[postgres] final case class DeliveryRow(
    id: UUID,
    organizationId: UUID,
    incidentId: UUID,
    resourceId: UUID,
    monitorRuleId: UUID,
    eventType: String,
    reason: String,
    channel: String,
    occurredAt: Instant,
    status: String,
    attemptCount: Long,
    nextAttemptAt: Instant,
    claimedBy: Option[UUID],
    claimedUntil: Option[Instant],
    sentAt: Option[Instant],
    lastErrorCode: Option[String],
    createdAt: Instant,
    updatedAt: Instant
  ) {
    def toDomain: Either[IllegalArgumentException, NotificationDelivery] =
      for {
        typedEventType <- NotificationEventType.fromCode(eventType)
        typedReason <- IncidentReason.fromCode(reason)
        typedChannel <- NotificationChannel.fromCode(channel)
        typedStatus <- NotificationDeliveryStatus.fromCode(status)
      } yield NotificationDelivery(
        id, organizationId, incidentId, resourceId, monitorRuleId,
        typedEventType, typedReason, typedChannel, occurredAt, typedStatus, attemptCount,
        nextAttemptAt, claimedBy, claimedUntil, sentAt, lastErrorCode, createdAt, updatedAt
      )
  }
}
