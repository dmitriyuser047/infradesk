package ru.bitec.app.ops

import application.connector.{
  ResourceConnectorRegistry,
  SyncConnection
}
import application.discovery.{
  CreateDiscoveredResource,
  ReconcileDiscoveredResource,
  SyncDiscoveredSnapshot
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
  PostgresResourceTypeRepository,
  PostgresSyncSessionRepository
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

        val syncSessionRepository =
          new PostgresSyncSessionRepository

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

        val createDiscoveredResource =
          new CreateDiscoveredResource[ConnectionIO](
            resourceTypeRepository,
            externalRefRepository,
            persistExternalResource
          )

        val reconcileDiscoveredResource =
          new ReconcileDiscoveredResource[ConnectionIO](
            resourceRepository,
            externalRefRepository
          )

        val syncDiscoveredSnapshot =
          new SyncDiscoveredSnapshot[ConnectionIO](
            createDiscoveredResource,
            reconcileDiscoveredResource,
            externalRefRepository,
            resourceRepository,
            syncSessionRepository
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
