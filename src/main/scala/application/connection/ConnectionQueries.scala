package ru.bitec.app.ops
package application.connection

import application.port.{ConnectionRepository, ConnectionScheduleRepository, SyncSessionRepository}
import cats.Monad
import cats.syntax.all._
import domain.connection.{Connection, ConnectionSchedule}
import domain.sync.SyncSession

import java.util.UUID

final case class ConnectionOverview(
  connection: Connection,
  schedule: Option[ConnectionSchedule],
  lastSync: Option[SyncSession]
)

final case class GetConnection[Tx[_]: Monad](
  connectionRepository: ConnectionRepository[Tx],
  syncSessionRepository: SyncSessionRepository[Tx],
  connectionScheduleRepository: ConnectionScheduleRepository[Tx]
) {
  def execute(
    organizationId: UUID,
    connectionId: UUID
  ): Tx[Option[ConnectionOverview]] =
    connectionRepository.findById(organizationId, connectionId).flatMap {
      case Some(connection) =>
        overview(connection).map(Some(_))
      case None =>
        none[ConnectionOverview].pure[Tx]
    }

  private def overview(connection: Connection): Tx[ConnectionOverview] =
    for {
      lastSync <- syncSessionRepository.findLatestByConnection(
        connection.organizationId,
        connection.id
      )
      schedule <- connectionScheduleRepository.findByConnection(
        connection.organizationId,
        connection.id
      )
    } yield ConnectionOverview(connection, schedule, lastSync)
}

final case class ListConnections[Tx[_]: Monad](
  connectionRepository: ConnectionRepository[Tx],
  syncSessionRepository: SyncSessionRepository[Tx],
  connectionScheduleRepository: ConnectionScheduleRepository[Tx]
) {
  def execute(organizationId: UUID): Tx[List[ConnectionOverview]] =
    connectionRepository.findByOrganization(organizationId).flatMap {
      _.traverse { connection =>
        for {
          lastSync <- syncSessionRepository.findLatestByConnection(
            organizationId,
            connection.id
          )
          schedule <- connectionScheduleRepository.findByConnection(
            organizationId,
            connection.id
          )
        } yield ConnectionOverview(connection, schedule, lastSync)
      }
    }
}
