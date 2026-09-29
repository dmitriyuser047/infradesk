package ru.bitec.app.ops
package persistence.postgres

import application.port.{AuditEventRepository, TerminalSessionRepository}
import cats.syntax.all._
import domain.audit.{AuditAction, AuditEvent, AuditTargetType}
import domain.terminal._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

/** All methods run in the caller's short transaction, including lifecycle audit writes. */
final class PostgresTerminalSessionRepository(audit: AuditEventRepository[ConnectionIO])
  extends TerminalSessionRepository[ConnectionIO] {

  def claim(session: TerminalSession, userLimit: Int, organizationLimit: Int): ConnectionIO[TerminalClaimResult] = {
    val org = session.organizationId
    val actor = session.actorUserId
    val now = session.createdAt
    for {
      locked <- sql"select id from organization where id = $org and is_active for update".query[UUID].option
      result <- locked match {
        case None => (TerminalClaimResult.OrganizationUnavailable: TerminalClaimResult).pure[ConnectionIO]
        case Some(_) =>
          sql"""select count(*), count(*) filter (where actor_user_id = $actor)
            from terminal_session where organization_id = $org
            and state in ('OPENING','ACTIVE') and lease_expires_at > $now""".query[(Long, Long)].unique.flatMap {
            case (orgCount, userCount) if orgCount >= organizationLimit || userCount >= userLimit =>
              (TerminalClaimResult.CapacityRejected: TerminalClaimResult).pure[ConnectionIO]
            case _ =>
              sql"""insert into terminal_session
                (id, organization_id, connection_id, actor_user_id, auth_session_id, state,
                 lease_owner, lease_token, lease_expires_at, connection_updated_at, created_at)
                values (${session.id}, $org, ${session.connectionId}, $actor, ${session.authSessionId}, 'OPENING',
                  ${session.leaseOwner}, ${session.leaseToken}, ${session.leaseExpiresAt}, ${session.connectionUpdatedAt}, $now)
              """.update.run.as(TerminalClaimResult.Claimed(session): TerminalClaimResult)
          }
      }
    } yield result
  }

  def activate(org: UUID, id: UUID, token: UUID, now: Instant, until: Instant): ConnectionIO[Boolean] =
    sql"""update terminal_session set state = 'ACTIVE', opened_at = $now, lease_expires_at = $until
      where organization_id = $org and id = $id and lease_token = $token
      and state = 'OPENING' and lease_expires_at > $now
      and exists (select 1 from auth_session a join user_account u on u.id = a.user_id
        where a.id = terminal_session.auth_session_id and a.user_id = terminal_session.actor_user_id
        and a.revoked_at is null and a.expires_at > $now and u.is_active)
      and exists (select 1 from organization_membership m where m.user_id = terminal_session.actor_user_id
        and m.organization_id = $org and m.is_active and m.role = 'OWNER')
      and exists (select 1 from connection c where c.id = terminal_session.connection_id
        and c.organization_id = $org and c.is_active and c.connector_type = 'SSH'
        and c.updated_at = terminal_session.connection_updated_at)
      returning id, organization_id, actor_user_id""".query[(UUID, UUID, UUID)].option
      .flatMap(record(_, AuditAction.TerminalSessionOpened, now))

  def renew(org: UUID, id: UUID, token: UUID, now: Instant, until: Instant): ConnectionIO[TerminalRenewResult] =
    sql"""select case
        when a.id is null or a.user_id <> t.actor_user_id or a.revoked_at is not null or a.expires_at <= $now
          or u.id is null or not u.is_active then 'AUTH_SESSION_ENDED'
        when m.user_id is null or not m.is_active or m.role <> 'OWNER' then 'TERMINAL_PERMISSION_REVOKED'
        when c.id is null or not c.is_active or c.connector_type <> 'SSH'
          or c.updated_at <> t.connection_updated_at then 'CONNECTION_CHANGED'
        else '' end
      from terminal_session t
      left join auth_session a on a.id = t.auth_session_id
      left join user_account u on u.id = t.actor_user_id
      left join organization_membership m on m.user_id = t.actor_user_id and m.organization_id = t.organization_id
      left join connection c on c.id = t.connection_id and c.organization_id = t.organization_id
      where t.organization_id = $org and t.id = $id and t.lease_token = $token
        and t.state = 'ACTIVE' and t.lease_expires_at > $now
      for update of t""".query[String].option.flatMap {
      case None => (TerminalRenewResult.Lost: TerminalRenewResult).pure[ConnectionIO]
      case Some("") =>
        sql"""update terminal_session set lease_expires_at = $until
          where organization_id = $org and id = $id and lease_token = $token
          and state = 'ACTIVE' and lease_expires_at > $now""".update.run
          .map(count => if (count == 1) TerminalRenewResult.Renewed(until) else TerminalRenewResult.Lost)
      case Some(code) =>
        val reason = TerminalCloseReason.fromCode(code).getOrElse(TerminalCloseReason.ValidationFailed)
        transition(org, id, Some(token), now, reason, revoked = true)
          .map(changed => if (changed) TerminalRenewResult.Revoked(reason) else TerminalRenewResult.Lost)
    }

  def closeOwned(org: UUID, id: UUID, token: UUID, now: Instant, reason: TerminalCloseReason): ConnectionIO[Boolean] =
    transition(org, id, Some(token), now, reason, revoked = false)

  def revoke(org: UUID, id: UUID, now: Instant): ConnectionIO[Boolean] =
    transition(org, id, None, now, TerminalCloseReason.SessionRevoked, revoked = true)

  private def transition(org: UUID, id: UUID, token: Option[UUID], now: Instant,
    reason: TerminalCloseReason, revoked: Boolean): ConnectionIO[Boolean] = {
    val fence = token.fold(fr"")(value => fr"and lease_token = $value")
    val state = if (revoked) "REVOKED" else "CLOSED"
    (fr"update terminal_session set state = $state, closed_at = $now, close_reason = ${reason.code}" ++
      fr"where organization_id = $org and id = $id and state in ('OPENING','ACTIVE') and lease_expires_at > $now" ++
      fence ++ fr"returning id, organization_id, actor_user_id").query[(UUID, UUID, UUID)].option
      .flatMap(record(_, AuditAction.TerminalSessionClosed, now))
  }

  def reapExpired(now: Instant, limit: Int): ConnectionIO[Int] =
    sql"""with expired as (
        select id from terminal_session where state in ('OPENING','ACTIVE') and lease_expires_at <= $now
        order by lease_expires_at, id limit $limit for update skip locked
      ) update terminal_session t set state = 'CLOSED', closed_at = $now, close_reason = 'LEASE_EXPIRED'
      from expired e where t.id = e.id
      returning t.id, t.organization_id, t.actor_user_id""".query[(UUID, UUID, UUID)].to[List].flatMap { rows =>
      audit.saveAll(rows.map(event(_, AuditAction.TerminalSessionClosed, now))).as(rows.size)
    }

  private def event(row: (UUID, UUID, UUID), action: AuditAction, now: Instant): AuditEvent =
    AuditEvent(UUID.randomUUID(), row._2, row._3, action, AuditTargetType.TerminalSession, Some(row._1), now, now)

  private def record(row: Option[(UUID, UUID, UUID)], action: AuditAction, now: Instant): ConnectionIO[Boolean] =
    row.fold(false.pure[ConnectionIO])(value => audit.save(event(value, action, now)).as(true))
}
