package ru.bitec.app.ops

import application.connector.{
  ResourceConnectorRegistry,
  SyncConnection,
  SyncConnectionById
}
import application.discovery.{
  CreateDiscoveredResource,
  ReconcileDiscoveredResource,
  SyncDiscoveredSnapshot
}
import application.monitor.EvaluateMonitorRules
import application.resource.{PersistExternalResource, RecordResourceObservations}
import application.scheduler.SyncScheduler
import cats.effect.{IO, IOApp}
import org.typelevel.doobie.ConnectionIO
import infrastructure.database.{
  Database,
  DatabaseConfig,
  ConnectionIOIdGenerator,
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
import integration.ssh.{
  EnvironmentSshAuthenticationProvider,
  SshConnector,
  SshjClient
}
import persistence.postgres.{
  PostgresExternalRefRepository,
  PostgresConnectionRepository,
  PostgresConnectionScheduleRepository,
  PostgresMetricObservationRepository,
  PostgresIncidentRepository,
  PostgresMonitorRuleRepository,
  PostgresMonitorRuleStateRepository,
  PostgresResourceRepository,
  PostgresResourceTypeRepository,
  PostgresSyncSessionRepository
}

import scala.concurrent.duration._

object Main extends IOApp.Simple {

  override def run: IO[Unit] =
    DatabaseConfig.load.flatMap { config =>
      Database.transactor(config).use { xa =>
        val resourceRepository =
          new PostgresResourceRepository

        val resourceTypeRepository =
          new PostgresResourceTypeRepository

        val connectionRepository =
          new PostgresConnectionRepository

        val connectionScheduleRepository =
          new PostgresConnectionScheduleRepository

        val externalRefRepository =
          new PostgresExternalRefRepository

        val syncSessionRepository =
          new PostgresSyncSessionRepository

        val metricObservationRepository =
          new PostgresMetricObservationRepository

        val monitorRuleRepository =
          new PostgresMonitorRuleRepository

        val monitorRuleStateRepository =
          new PostgresMonitorRuleStateRepository

        val incidentRepository =
          new PostgresIncidentRepository

        val transactionRunner =
          new DoobieTransactionRunner(xa)

        val idGenerator =
          new SystemIdGenerator

        val transactionIdGenerator =
          new ConnectionIOIdGenerator

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

        val recordResourceObservations =
          new RecordResourceObservations[ConnectionIO](
            metricObservationRepository
          )

        val evaluateMonitorRules =
          new EvaluateMonitorRules[ConnectionIO](
            monitorRuleRepository,
            monitorRuleStateRepository,
            metricObservationRepository,
            incidentRepository,
            transactionIdGenerator
          )

        val syncDiscoveredSnapshot =
          new SyncDiscoveredSnapshot[ConnectionIO](
            createDiscoveredResource,
            reconcileDiscoveredResource,
            externalRefRepository,
            resourceRepository,
            syncSessionRepository,
            recordResourceObservations
          )

        val dockerEngineClient =
          new DockerJavaEngineClient[IO]

        val dockerConnector =
          new DockerConnector[IO](
            dockerEngineClient
          )

        val sshClient =
          new SshjClient[IO]

        val sshAuthenticationProvider =
          new EnvironmentSshAuthenticationProvider[IO]

        val sshConnector =
          new SshConnector[IO](
            sshClient,
            sshAuthenticationProvider
          )

        val connectorRegistry =
          new ResourceConnectorRegistry[IO](
            List(dockerConnector, sshConnector)
          )

        val syncConnection =
          new SyncConnection[IO, ConnectionIO](
            connectorRegistry,
            syncDiscoveredSnapshot,
            connectionRepository,
            syncSessionRepository,
            transactionRunner,
            idGenerator,
            timeProvider,
            recordResourceObservations
          )

        val syncConnectionById =
          new SyncConnectionById[IO, ConnectionIO](
            connectionRepository,
            transactionRunner,
            syncConnection
          )

        val syncScheduler =
          new SyncScheduler[IO, ConnectionIO](
            connectionScheduleRepository,
            syncConnectionById,
            transactionRunner,
            timeProvider,
            evaluateMonitorRules
          )

        syncScheduler.run(1.second, limit = 100)
      }
    }
}
