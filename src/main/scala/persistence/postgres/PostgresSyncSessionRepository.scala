package ru.bitec.app.ops
package persistence.postgres

import application.port.SyncSessionRepository
import domain.sync.{SyncSession, SyncSessionStatus}

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresSyncSessionRepository extends SyncSessionRepository[ConnectionIO] {

  private final case class SyncSessionRow(
    id: UUID,
    organizationId: UUID,
    connectionId: UUID,
    startedAt: Instant,
    finishedAt: Option[Instant],
    status: String
  ) {
    def toDomain: Either[IllegalArgumentException, SyncSession] =
      SyncSessionStatus.fromCode(status).map { typedStatus =>
        SyncSession(id, organizationId, connectionId, startedAt, finishedAt, typedStatus)
      }
  }

  override def findLatestByConnection(
    organizationId: UUID,
    connectionId: UUID
  ): ConnectionIO[Option[SyncSession]] =
    sql"""
      select
        id,
        organization_id,
        connection_id,
        started_at,
        finished_at,
        status
      from sync_session
      where organization_id = $organizationId
        and connection_id = $connectionId
      order by started_at desc, id desc
      limit 1
    """
      .query[SyncSessionRow]
      .option
      .flatMap {
        case Some(row) => row.toDomain.map(value => Option(value)).liftTo[ConnectionIO]
        case None => none[SyncSession].pure[ConnectionIO]
      }

  override def create(session: SyncSession): ConnectionIO[Unit] =
    sql"""
      insert into sync_session (
        id, organization_id, connection_id, started_at, finished_at, status
      ) values (
        ${session.id}, ${session.organizationId}, ${session.connectionId},
        ${session.startedAt}, ${session.finishedAt}, ${session.status.code}
      )
    """.update.run.flatMap(rows => expectOne("create")(rows))

  override def complete(
                        organizationId: UUID,
                        id: UUID,
                        finishedAt: Instant
                      ): ConnectionIO[Unit] =
    finish(organizationId, id, finishedAt, SyncSessionStatus.Completed)

  override def fail(
                    organizationId: UUID,
                    id: UUID,
                    finishedAt: Instant
                  ): ConnectionIO[Unit] =
    finish(organizationId, id, finishedAt, SyncSessionStatus.Failed)

  private def finish(
                      organizationId: UUID,
                      id: UUID,
                      finishedAt: Instant,
                      status: SyncSessionStatus
                    ): ConnectionIO[Unit] =
    sql"""
      update sync_session
      set finished_at = $finishedAt, status = ${status.code}
      where organization_id = $organizationId
        and id = $id
        and status = ${SyncSessionStatus.Running.code}
    """.update.run.flatMap(rows => expectOne(s"finish as ${status.code}")(rows))

  private def expectOne(operation: String)(rows: Int): ConnectionIO[Unit] =
    if (rows == 1) ().pure[ConnectionIO]
    else new IllegalStateException(
      s"Expected to $operation 1 sync_session row, affected: $rows"
    ).raiseError[ConnectionIO, Unit]
}
