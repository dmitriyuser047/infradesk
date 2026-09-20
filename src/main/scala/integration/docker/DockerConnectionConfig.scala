package ru.bitec.app.ops
package integration.docker

import domain.connection.ConnectionConfig

final case class DockerConnectionConfig(
                                         endpoint: String,
                                         apiVersion: Option[String]
                                       )

object DockerConnectionConfig {

  private val EndpointKey = "endpoint"
  private val ApiVersionKey = "apiVersion"

  def from(config: ConnectionConfig): Either[IllegalArgumentException, DockerConnectionConfig] =
    config.get(EndpointKey) match {
      case Some(endpoint) if endpoint.trim.nonEmpty =>
        Right(
          DockerConnectionConfig(
            endpoint = endpoint.trim,
            apiVersion = config.get(ApiVersionKey).map(_.trim).filter(_.nonEmpty)
          )
        )

      case _ =>
        Left(
          new IllegalArgumentException(
            s"Docker connection config requires '$EndpointKey'"
          )
        )
    }
}