package ru.bitec.app.ops

import application.connector.{
  ResourceConnectorRegistry,
  SyncConnection
}
import application.discovery.{
  CreateDiscoveredResource,
  ResolveDiscoveredResource,
  SyncDiscoveredResource
}
import application.resource.PersistExternalResource
import cats.effect.{IO, IOApp}
import org.typelevel.doobie.ConnectionIO
import infrastructure.database.{
  Database,
  DatabaseConfig,
  DoobieTransactionRunner
}
import infrastructure.runtime.{
  SystemIdGenerator,
  SystemTimeProvider
}
import integration.docker.{
  DockerConnector,
  DockerJavaEngineClient
}
import persistence.postgres.{
  PostgresExternalRefRepository,
  PostgresResourceRepository,
  PostgresResourceTypeRepository
}

object Main extends IOApp.Simple {

  override def run: IO[Unit] =
    DatabaseConfig.load.flatMap { config =>
      Database.transactor(config).use { xa =>
        val resourceRepository =
          new PostgresResourceRepository

        val resourceTypeRepository =
          new PostgresResourceTypeRepository

        val externalRefRepository =
          new PostgresExternalRefRepository

        val transactionRunner =
          new DoobieTransactionRunner(xa)

        val idGenerator =
          new SystemIdGenerator

        val timeProvider =
          new SystemTimeProvider

        val persistExternalResource =
          new PersistExternalResource[ConnectionIO](
            resourceRepository,
            externalRefRepository
          )

        val resolveDiscoveredResource =
          new ResolveDiscoveredResource[ConnectionIO](
            externalRefRepository
          )

        val createDiscoveredResource =
          new CreateDiscoveredResource[ConnectionIO](
            resourceTypeRepository,
            persistExternalResource
          )

        val syncDiscoveredResource =
          new SyncDiscoveredResource[IO, ConnectionIO](
            resolveDiscoveredResource,
            createDiscoveredResource,
            resourceRepository,
            externalRefRepository,
            transactionRunner,
            idGenerator,
            timeProvider
          )

        val dockerEngineClient =
          new DockerJavaEngineClient[IO]

        val dockerConnector =
          new DockerConnector[IO](
            dockerEngineClient
          )

        val connectorRegistry =
          new ResourceConnectorRegistry[IO](
            List(dockerConnector)
          )

        IO.never
      }
    }
}