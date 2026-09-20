package ru.bitec.app.ops
package application.connector

import application.port.ResourceConnector

final class ResourceConnectorRegistry[F[_]](
                                             connectors: List[ResourceConnector[F]]
                                           ) {

  private val connectorsByType: Map[String, ResourceConnector[F]] = {
    val grouped = connectors.groupBy(_.connectorType)

    val duplicates =
      grouped.collect {
        case (connectorType, values) if values.size > 1 =>
          connectorType
      }

    require(
      duplicates.isEmpty,
      s"Duplicate connector types: ${duplicates.mkString(", ")}"
    )

    grouped.map {
      case (connectorType, values) =>
        connectorType -> values.head
    }
  }

  def find(connectorType: String): Option[ResourceConnector[F]] =
    connectorsByType.get(connectorType)
}