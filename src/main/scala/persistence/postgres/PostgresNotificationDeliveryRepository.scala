package ru.bitec.app.ops
package persistence.postgres

import application.port.{NotificationDeliveryRepository, NotificationDeliveryScope}
import cats.syntax.all._
import domain.incident.IncidentReason
import domain.notification.{
  NotificationChannelType,
  NotificationContext,
  NotificationDelivery,
  NotificationDeliveryStatus,
  NotificationDeliveryTarget,
  NotificationEventType
}
import org.typelevel.doobie.{ConnectionIO, Fragment, Query0, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import serialization.notification.NotificationContextJson

import java.time.Instant
import java.util.UUID

import PostgresNotificationDeliveryRepository.DeliveryRow

final class PostgresNotificationDeliveryRepository extends NotificationDeliveryRepository[ConnectionIO] {

  private val columns =
    fr"""
      id, organization_id, incident_id, resource_id, monitor_rule_id,
      event_type, reason, channel, notification_channel_id, occurred_at, status, attempt_count,
      next_attempt_at, claimed_by, claimed_until, sent_at, last_error_code, created_at, updated_at,
      context::text
    """

  private def rows(query: Query0[DeliveryRow]): ConnectionIO[List[NotificationDelivery]] =
    query.to[List].flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  /** Recording the same incident transition twice is a no-op: the unique event index decides.
    *
    * Which index that is depends on the kind of target, and Postgres infers a partial index only
    * from a predicate, so the rows are written in at most two statements — one per kind — rather
    * than one statement per channel.
    */
  override def saveAll(deliveries: List[NotificationDelivery]): ConnectionIO[Unit] = {
    val (managed, legacy) = deliveries.partition(_.target.channelId.isDefined)
    insert(legacy, NotificationDeliveryScope.Legacy) *>
      insert(managed, NotificationDeliveryScope.Managed)
  }

  private def insert(
    deliveries: List[NotificationDelivery],
    scope: NotificationDeliveryScope
  ): ConnectionIO[Unit] =
    if (deliveries.isEmpty) ().pure[ConnectionIO]
    else {
      // Each scope has its own partial unique index, and Postgres infers one only from its
      // predicate, so the conflict target is named in full.
      val (conflictColumns, predicate) = scope match {
        case NotificationDeliveryScope.Legacy =>
          ("organization_id, incident_id, event_type, channel",
            "where notification_channel_id is null")
        case NotificationDeliveryScope.Managed =>
          ("organization_id, incident_id, event_type, notification_channel_id",
            "where notification_channel_id is not null")
      }
      Update[(UUID, UUID, UUID, UUID, UUID, String, String, String, Option[UUID], Instant, String,
        Long, Instant, Option[UUID], Option[Instant], Option[Instant], Option[String], Instant,
        Instant, Option[String])](s"""
        insert into notification_delivery (
          id, organization_id, incident_id, resource_id, monitor_rule_id,
          event_type, reason, channel, notification_channel_id, occurred_at, status, attempt_count,
          next_attempt_at, claimed_by, claimed_until, sent_at, last_error_code, created_at, updated_at,
          context
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
        on conflict ($conflictColumns) $predicate do nothing
      """).updateMany(deliveries.map(delivery =>
        (delivery.id, delivery.organizationId, delivery.incidentId, delivery.resourceId,
          delivery.monitorRuleId, delivery.eventType.code, delivery.reason.code,
          delivery.channelType.code, delivery.target.channelId,
          delivery.occurredAt, delivery.status.code, delivery.attemptCount, delivery.nextAttemptAt,
          delivery.claimedBy, delivery.claimedUntil, delivery.sentAt, delivery.lastErrorCode,
          delivery.createdAt, delivery.updatedAt, delivery.context.map(NotificationContextJson.encode))
      )).void
    }

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
    scope: NotificationDeliveryScope,
    claimedBy: UUID,
    limit: Int,
    leaseSeconds: Long
  ): ConnectionIO[List[NotificationDelivery]] = {
    // Part of the claim, not a filter afterwards: a worker never takes a delivery whose
    // transport it cannot speak.
    val inScope = scope match {
      case NotificationDeliveryScope.Legacy => fr"and notification_channel_id is null"
      case NotificationDeliveryScope.Managed => fr"and notification_channel_id is not null"
    }
    rows(
      (fr"""
        with due as (
          select id
          from notification_delivery
          where status = 'PENDING'
            and next_attempt_at <= current_timestamp""" ++ inScope ++ fr"""
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
                  d.event_type, d.reason, d.channel, d.notification_channel_id, d.occurred_at,
                  d.status, d.attempt_count, d.next_attempt_at, d.claimed_by, d.claimed_until,
                  d.sent_at, d.last_error_code, d.created_at, d.updated_at, d.context::text
      """).query[DeliveryRow]
    )
  }

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
    notificationChannelId: Option[UUID],
    occurredAt: Instant,
    status: String,
    attemptCount: Long,
    nextAttemptAt: Instant,
    claimedBy: Option[UUID],
    claimedUntil: Option[Instant],
    sentAt: Option[Instant],
    lastErrorCode: Option[String],
    createdAt: Instant,
    updatedAt: Instant,
    context: Option[String]
  ) {
    def toDomain: Either[IllegalArgumentException, NotificationDelivery] =
      for {
        typedEventType <- NotificationEventType.fromCode(eventType)
        typedReason <- IncidentReason.fromCode(reason)
        typedChannel <- NotificationChannelType.fromCode(channel)
        typedStatus <- NotificationDeliveryStatus.fromCode(status)
        target = notificationChannelId.fold[NotificationDeliveryTarget](
          NotificationDeliveryTarget.LegacyWebhook)(NotificationDeliveryTarget.Managed(_, typedChannel))
      } yield NotificationDelivery(
        id, organizationId, incidentId, resourceId, monitorRuleId,
        typedEventType, typedReason, target, occurredAt, typedStatus, attemptCount,
        nextAttemptAt, claimedBy, claimedUntil, sentAt, lastErrorCode, createdAt, updatedAt,
        // A snapshot that fails to decode — an older or corrupt one — must not stop a delivery;
        // it falls back to the identifier-only message rather than failing the read.
        typedContext
      )

    private def typedContext: Option[NotificationContext] =
      context.flatMap(value => NotificationContextJson.decode(value).toOption)
  }
}
