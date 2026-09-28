package ru.bitec.app.ops
package persistence.postgres

import application.port.{ConfigurationDeploymentSource, ConfigurationDeploymentSourceQuery}
import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

final class PostgresConfigurationDeploymentSourceQuery extends ConfigurationDeploymentSourceQuery[ConnectionIO] {
  private val connections = new PostgresConnectionRepository

  override def resolve(organizationId: UUID, resourceId: UUID,
                       connectionId: UUID): ConnectionIO[ConfigurationDeploymentSource] =
    connections.findById(organizationId, connectionId).flatMap {
      case None => (ConfigurationDeploymentSource.Missing: ConfigurationDeploymentSource).pure[ConnectionIO]
      case Some(connection) if !connection.isActive || connection.connectorType != "SSH" =>
        (ConfigurationDeploymentSource.Missing: ConfigurationDeploymentSource).pure[ConnectionIO]
      case Some(connection) =>
        sql"""select exists(select 1 from external_ref
               where organization_id = $organizationId and resource_id = $resourceId
                 and connection_id = $connectionId)""".query[Boolean].unique.map {
          case true => ConfigurationDeploymentSource.Ready(connection): ConfigurationDeploymentSource
          case false => ConfigurationDeploymentSource.NotSource: ConfigurationDeploymentSource
        }
    }
}
