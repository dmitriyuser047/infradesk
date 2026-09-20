package ru.bitec.app.ops
package integration.docker

import cats.effect.{Resource, Sync}

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientImpl}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient

import java.time.Duration
import scala.jdk.CollectionConverters._

final class DockerJavaEngineClient[F[_]: Sync]
  extends DockerEngineClient[F] {

  override def listContainers(
                               config: DockerConnectionConfig
                             ): F[List[DockerContainerSummary]] =
    dockerClient(config).use { client =>
      Sync[F].blocking {
        client
          .listContainersCmd()
          .withShowAll(true)
          .exec()
          .asScala
          .toList
          .map { container =>
            DockerContainerSummary(
              id = container.getId,
              names = Option(container.getNames)
                .map(_.toList)
                .getOrElse(Nil),
              image = Option(container.getImage).getOrElse(""),
              state = Option(container.getState).getOrElse(""),
              status = Option(container.getStatus).getOrElse("")
            )
          }
      }
    }

  private def dockerClient(
                            config: DockerConnectionConfig
                          ): Resource[F, DockerClient] =
    Resource.make {
      Sync[F].blocking(createClient(config))
    } { client =>
      Sync[F].blocking(client.close())
    }

  private def createClient(
                            config: DockerConnectionConfig
                          ): DockerClient = {
    val configBuilder =
      DefaultDockerClientConfig
        .createDefaultConfigBuilder()
        .withDockerHost(config.endpoint)

    config.apiVersion.foreach(configBuilder.withApiVersion)

    val clientConfig = configBuilder.build()

    val httpClient =
      new ApacheDockerHttpClient.Builder()
        .dockerHost(clientConfig.getDockerHost)
        .sslConfig(clientConfig.getSSLConfig)
        .maxConnections(10)
        .connectionTimeout(Duration.ofSeconds(5))
        .responseTimeout(Duration.ofSeconds(30))
        .build()

    DockerClientImpl.getInstance(clientConfig, httpClient)
  }
}