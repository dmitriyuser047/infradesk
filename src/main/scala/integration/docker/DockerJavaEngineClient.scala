package ru.bitec.app.ops
package integration.docker

import cats.effect.{Resource, Sync, Async}
import cats.syntax.all._
import cats.effect.syntax.all._
import domain.metric.{MetricCode, ResourceTelemetry}
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Statistics
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientImpl}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient

import java.time.Duration
import scala.jdk.CollectionConverters._

final class DockerJavaEngineClient[F[_]: Async]
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
      }.flatMap(_.parTraverseN(4)(container => enrich(client, container)))
    }

  private def enrich(client: DockerClient, container: DockerContainerSummary): F[DockerContainerSummary] =
    Async[F].blocking {
      val inspection = scala.util.Try(client.inspectContainerCmd(container.id).exec()).toOption
      val metadata = inspection.toList.flatMap { value =>
        val restarts = Option(value.getRestartCount).map(v => MetricCode.ContainerRestartCount.code -> BigDecimal(v.intValue))
        val health = Option(value.getState).flatMap(v => Option(v.getHealth)).flatMap(v => Option(v.getStatus))
          .filter(Set("healthy", "unhealthy", "starting")).map(v =>
            MetricCode.ContainerHealthy.code -> BigDecimal(if (v == "healthy") 1 else 0))
        List(restarts, health).flatten
      }.toMap
      val readings = if (container.state != "running") Map.empty[String, BigDecimal] else {
        val result = new AtomicReference[Statistics]()
        val received = new CountDownLatch(1)
        val callback = new ResultCallback.Adapter[Statistics] {
          override def onNext(value: Statistics): Unit = { result.set(value); received.countDown() }
          override def onError(error: Throwable): Unit = received.countDown()
        }
        val cmd = client.statsCmd(container.id).withNoStream(true)
        try {
          cmd.exec(callback)
          received.await(3, TimeUnit.SECONDS)
          Option(result.get()).map(DockerStatistics.metrics).getOrElse(Map.empty)
        } catch { case scala.util.control.NonFatal(_) => Map.empty[String, BigDecimal] }
        finally { callback.close(); cmd.close() }
      }
      container.copy(telemetry = ResourceTelemetry(readings ++ metadata))
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
