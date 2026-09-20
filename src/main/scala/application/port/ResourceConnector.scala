package ru.bitec.app.ops
package application.port

import application.discovery.DiscoveredResource
import domain.connection.{Connection, ConnectionConfig}

final case class ResourceConnectorResult(
                                          resources: List[DiscoveredResource],
                                          connectionConfig: ConnectionConfig
                                        )

trait ResourceConnector[F[_]] {

  def connectorType: String

  def discover(
                connection: Connection
              ): F[ResourceConnectorResult]
}