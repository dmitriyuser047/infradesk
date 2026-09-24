package ru.bitec.app.ops
package dev

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
import application.resource.{PersistExternalResource, RecordResourceObservations}
import cats.effect.{IO, IOApp}
import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import domain.connection.{
  Connection,
  ConnectionConfig,
  ConnectionScope
}
import infrastructure.database.{
  Database,
  DatabaseConfig,
  DoobieTransactionRunner
}
import infrastructure.runtime.{
  SystemIdGenerator,
  SystemTimeProvider
}
import integration.ssh.{
  EnvironmentSshAuthenticationProvider,
  EnvironmentSecrets,
  SshConnector,
  SshjClient
}
import persistence.postgres.{
  PostgresConnectionRepository,
  PostgresExternalRefRepository,
  PostgresMetricObservationRepository,
  PostgresResourceTypeRepository,
  ProductionResourceCodec,
  PostgresSyncSessionRepository
}

import java.util.UUID

object DevSshSync extends IOApp.Simple {

  private val OrganizationId =
    UUID.fromString("20000000-0000-0000-0000-000000000001")

  private val ProjectId =
    UUID.fromString("30000000-0000-0000-0000-000000000001")

  private val EnvironmentId =
    UUID.fromString("40000000-0000-0000-0000-000000000001")

  private val ConnectionId =
    UUID.fromString("60000000-0000-0000-0000-000000000003")

  override def run: IO[Unit] =
    IO.fromEither(DatabaseConfig.fromEnvironment(sys.env)).flatMap { databaseConfig =>
      Database.transactor(databaseConfig).use { xa =>
        val resourceRepository =
          ProductionResourceCodec.resourceRepository

        val resourceTypeRepository =
          new PostgresResourceTypeRepository

        val connectionRepository =
          new PostgresConnectionRepository

        val externalRefRepository =
          new PostgresExternalRefRepository

        val syncSessionRepository =
          new PostgresSyncSessionRepository

        val metricObservationRepository =
          new PostgresMetricObservationRepository

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

        val recordResourceObservations =
          new RecordResourceObservations[ConnectionIO](
            metricObservationRepository
          )

        val historyRecorder =
          new _root_.ru.bitec.app.ops.application.history.HistoryRecorder[ConnectionIO](
            new _root_.ru.bitec.app.ops.persistence.postgres.PostgresHistoryEventRepository,
            new _root_.ru.bitec.app.ops.infrastructure.database.ConnectionIOIdGenerator,
            new _root_.ru.bitec.app.ops.infrastructure.database.ConnectionIOTimeProvider
          )

        val syncDiscoveredSnapshot =
          new SyncDiscoveredSnapshot[ConnectionIO](
            createDiscoveredResource,
            reconcileDiscoveredResource,
            externalRefRepository,
            resourceRepository,
            syncSessionRepository,
            recordResourceObservations,
            historyRecorder
          )

        val sshClient =
          new SshjClient[IO]

        val authenticationProvider =
          new EnvironmentSshAuthenticationProvider[IO](EnvironmentSecrets.fromEnvironment(sys.env))

        val sshConnector =
          new SshConnector[IO](
            sshClient,
            authenticationProvider
          )

        val connectorRegistry =
          new ResourceConnectorRegistry[IO](
            List(sshConnector)
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
            recordResourceObservations,
            historyRecorder,
            _root_.org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("dev.ssh-sync")
          )

        val syncConnectionById =
          new SyncConnectionById[IO, ConnectionIO](
            connectionRepository,
            transactionRunner,
            syncConnection
          )

        for {
          host <- env("INFRADESK_SSH_HOST")
          username <- env("INFRADESK_SSH_USER")

          now <- timeProvider.now

          connection = Connection(
            id = ConnectionId,
            organizationId = OrganizationId,
            scope = ConnectionScope.Environment(
              ProjectId,
              EnvironmentId
            ),
            connectorType = SshConnector.ConnectorType,
            code = "SVINPEAK_PROD",
            name = "Svinpeak Production VPS",
            config = ConnectionConfig(
              Map(
                "host" -> host,
                "port" -> "22",
                "username" -> username,
                "connectTimeoutSeconds" -> "10",
                "commandTimeoutSeconds" -> "30"
              )
            ),
            secretRef = Some(
              "env:INFRADESK_SSH_PASSWORD"
            ),
            isActive = true,
            createdAt = now,
            updatedAt = now
          )

          _ <- transactionRunner.run(
            connectionRepository
              .findById(OrganizationId, ConnectionId)
              .flatMap {
                case Some(_) =>
                  ().pure[ConnectionIO]

                case None =>
                  connectionRepository.save(connection)
              }
          )

          result <- syncConnectionById.execute(
            OrganizationId,
            ConnectionId
          )

          _ <- result.resources.traverse_ { resource =>
            IO.println(
              s"${resource.id} | ${resource.code} | ${resource.name}"
            )
          }
        } yield ()
      }
    }

  private def env(name: String): IO[String] =
    IO.fromOption(sys.env.get(name))(
      new IllegalStateException(
        s"Environment variable $name is not set"
      )
    )
}
