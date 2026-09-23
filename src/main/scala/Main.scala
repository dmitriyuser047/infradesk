package ru.bitec.app.ops

import application.connector.{
  ResourceConnectorRegistry,
  SyncConnection,
  SyncConnectionById,
  RunConnectionSync
}
import application.discovery.{
  CreateDiscoveredResource,
  ReconcileDiscoveredResource,
  SyncDiscoveredSnapshot
}
import application.monitor.EvaluateMonitorRules
import application.connection.{GetConnection, ListConnections, SshConnectionManagement, ListConnectionSyncSessions, GetConnectionSyncSession, RunManualConnectionSync}
import application.navigation.{GetOrganization, ListEnvironments, ListProjects}
import application.workspace.{CreateEnvironment, CreateProject}
import application.auth.{Authentication, BCryptPasswordHasher, BootstrapAdmin, BootstrapConfig, Login, SessionTokens}
import application.resource.{GetResource, GetResourceMetricHistory, ListEnvironmentResources, PersistExternalResource, RecordResourceObservations}
import application.scheduler.SyncScheduler
import cats.data.Kleisli
import cats.effect.{IO, IOApp}
import com.comcast.ip4s.{Host, Port}
import infrastructure.http.ResourceRoutes
import infrastructure.http.IncidentRoutes
import infrastructure.http.MonitorRuleRoutes
import infrastructure.http.ConnectionRoutes
import infrastructure.http.ConnectionSyncRoutes
import infrastructure.http.SshConnectionMutationRoutes
import infrastructure.http.NavigationRoutes
import infrastructure.http.WorkspaceMutationRoutes
import infrastructure.http.{AuthBoundary, AuthRoutes, AuthSettings}
import infrastructure.http.HealthRoutes
import infrastructure.http.middleware.{HttpRequestLogging, RequestIdMiddleware}
import application.monitor.{ListMonitorRules, CreateMonitorRule, UpdateMonitorRule}
import application.incident.{GetIncident, ListIncidents}
import cats.syntax.semigroupk._
import org.http4s.{HttpApp, Method, Request}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.doobie.ConnectionIO
import infrastructure.database.{
  Database,
  DatabaseConfig,
  ConnectionIOIdGenerator,
  ConnectionIOTimeProvider,
  DoobieTransactionRunner,
  PostgresReadinessCheck
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
  CompositeSshAuthenticationProvider,
  ConnectionSecretCipher,
  SshConnectionProbeAdapter,
  SshConnector,
  SshjClient
}
import persistence.postgres.{
  PostgresExternalRefRepository,
  PostgresConnectionRepository,
  PostgresConnectionScheduleRepository,
  PostgresConnectionSecretRepository,
  PostgresMetricObservationRepository,
  PostgresIncidentRepository,
  PostgresMonitorRuleRepository,
  PostgresMonitorRuleStateRepository,
  PostgresEnvironmentRepository,
  PostgresOrganizationRepository,
  PostgresProjectRepository,
  PostgresResourceRepository,
  PostgresResourceTypeRepository,
  PostgresSyncSessionRepository,
  PostgresAuthSessionRepository,
  PostgresOrganizationMembershipRepository,
  PostgresUserAccountRepository
}

import scala.concurrent.duration._
import scala.util.Try

object Main extends IOApp.Simple {

  override def run: IO[Unit] =
    for {
      config <- DatabaseConfig.load
      authSettings <- IO.fromEither(AuthSettings.fromEnvironment(sys.env))
      bootstrapConfig <- IO.fromEither(BootstrapConfig.fromEnvironment(sys.env))
      secretCipher <- IO.fromEither(ConnectionSecretCipher.fromEnvironment(sys.env))
      _ <- Database.transactor(config).use { xa =>
        val httpLogger = Slf4jLogger.getLoggerFromName[IO]("infrastructure.http.requests")
        val healthLogger = Slf4jLogger.getLoggerFromName[IO]("infrastructure.http.health")
        val syncLogger = Slf4jLogger.getLoggerFromName[IO]("application.connector.SyncConnection")
        val schedulerLogger = Slf4jLogger.getLoggerFromName[IO]("application.scheduler.SyncScheduler")
        val monitorLogger = Slf4jLogger.getLoggerFromName[ConnectionIO]("application.monitor.EvaluateMonitorRules")
        val resourceRepository =
          new PostgresResourceRepository

        val organizationRepository =
          new PostgresOrganizationRepository

        val projectRepository =
          new PostgresProjectRepository

        val environmentRepository =
          new PostgresEnvironmentRepository

        val resourceTypeRepository =
          new PostgresResourceTypeRepository

        val connectionRepository =
          new PostgresConnectionRepository

        val connectionScheduleRepository =
          new PostgresConnectionScheduleRepository

        val connectionSecretRepository = new PostgresConnectionSecretRepository

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

        val userAccountRepository = new PostgresUserAccountRepository
        val authSessionRepository = new PostgresAuthSessionRepository
        val membershipRepository = new PostgresOrganizationMembershipRepository

        val transactionRunner =
          new DoobieTransactionRunner(xa)

        val idGenerator =
          new SystemIdGenerator

        val transactionIdGenerator =
          new ConnectionIOIdGenerator
        val transactionTimeProvider = new ConnectionIOTimeProvider

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
            transactionIdGenerator,
            monitorLogger
          )

        val getResource =
          GetResource[ConnectionIO](resourceRepository)

        val listEnvironmentResources =
          ListEnvironmentResources[ConnectionIO](resourceRepository)
        val getResourceMetricHistory =
          GetResourceMetricHistory[ConnectionIO](resourceRepository, metricObservationRepository)

        val resourceRoutes =
          new ResourceRoutes[ConnectionIO](getResource, listEnvironmentResources, getResourceMetricHistory, transactionRunner)

        val getIncident = GetIncident[ConnectionIO](incidentRepository)
        val listIncidents = ListIncidents[ConnectionIO](incidentRepository)
        val incidentRoutes = new IncidentRoutes[ConnectionIO](getIncident, listIncidents, transactionRunner)
        val monitorRuleRoutes = new MonitorRuleRoutes[ConnectionIO](
          ListMonitorRules(resourceRepository, monitorRuleRepository),
          CreateMonitorRule(resourceRepository, monitorRuleRepository, transactionIdGenerator, transactionTimeProvider),
          UpdateMonitorRule(monitorRuleRepository, transactionTimeProvider),
          transactionRunner
        )
        val getConnection = GetConnection[ConnectionIO](connectionRepository, syncSessionRepository, connectionScheduleRepository)
        val listConnections = ListConnections[ConnectionIO](connectionRepository, syncSessionRepository, connectionScheduleRepository)
        val connectionRoutes = new ConnectionRoutes[ConnectionIO](getConnection, listConnections, transactionRunner)
        val sshClient = new SshjClient[IO]
        val sshAuthenticationProvider = new CompositeSshAuthenticationProvider[ConnectionIO](
          connectionSecretRepository, transactionRunner, secretCipher
        )
        val sshConnectionManagement = new SshConnectionManagement[ConnectionIO](
          connectionRepository, connectionScheduleRepository, connectionSecretRepository,
          projectRepository, environmentRepository, transactionRunner,
          new SshConnectionProbeAdapter(sshClient), sshAuthenticationProvider, secretCipher
        )
        val sshMutationRoutes = new SshConnectionMutationRoutes[ConnectionIO](sshConnectionManagement)
        val navigationRoutes = new NavigationRoutes[ConnectionIO](
          GetOrganization(organizationRepository),
          ListProjects(organizationRepository, projectRepository),
          ListEnvironments(organizationRepository, projectRepository, environmentRepository),
          transactionRunner
        )
        val workspaceMutationRoutes = new WorkspaceMutationRoutes[ConnectionIO](
          new CreateProject(organizationRepository, projectRepository, transactionIdGenerator, transactionTimeProvider),
          new CreateEnvironment(projectRepository, environmentRepository, transactionIdGenerator, transactionTimeProvider),
          transactionRunner
        )

        val passwordHasher = new BCryptPasswordHasher
        val tokens = new SessionTokens
        val authentication = new Authentication[ConnectionIO](
          authSessionRepository, membershipRepository, transactionRunner, tokens
        )
        val authRoutes = new AuthRoutes[ConnectionIO](
          new Login(userAccountRepository, authSessionRepository, transactionRunner,
            passwordHasher, tokens, authSettings.ttlSeconds),
          authentication,
          authSettings
        )
        val bootstrapAdmin = new BootstrapAdmin[ConnectionIO](
          userAccountRepository, membershipRepository, organizationRepository,
          transactionRunner, passwordHasher
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
            recordResourceObservations,
            syncLogger
          )

        val syncConnectionById =
          new SyncConnectionById[IO, ConnectionIO](
            connectionRepository,
            transactionRunner,
            syncConnection
          )

        val runConnectionSync = new RunConnectionSync[IO, ConnectionIO](
          syncConnectionById, evaluateMonitorRules, transactionRunner, timeProvider, syncLogger
        )

        val connectionSyncRoutes = new ConnectionSyncRoutes[ConnectionIO](
          new ListConnectionSyncSessions(connectionRepository, syncSessionRepository),
          new GetConnectionSyncSession(syncSessionRepository),
          new RunManualConnectionSync(runConnectionSync, syncSessionRepository, transactionRunner),
          transactionRunner
        )

        val businessApp = (resourceRoutes.routes <+> incidentRoutes.routes <+>
          monitorRuleRoutes.routes <+> connectionRoutes.routes <+> sshMutationRoutes.routes <+>
          connectionSyncRoutes.routes <+> navigationRoutes.routes <+> workspaceMutationRoutes.routes).orNotFound
        val protectedApp = new AuthBoundary(authRoutes, authentication, businessApp).app
        val platformApp = new HealthRoutes(new PostgresReadinessCheck(xa), healthLogger).routes.orNotFound
        val app: HttpApp[IO] = Kleisli { request: Request[IO] =>
          val path = request.uri.path.renderString
          if (request.method == Method.GET && (path == "/health" || path == "/ready"))
            platformApp.run(request)
          else protectedApp.run(request)
        }
        val observedApp = RequestIdMiddleware(HttpRequestLogging(app, httpLogger))

        val syncScheduler =
          new SyncScheduler[IO, ConnectionIO](
            connectionScheduleRepository,
            runConnectionSync,
            transactionRunner,
            timeProvider,
            schedulerLogger
          )

        bootstrapAdmin.run(bootstrapConfig).flatMap { _ =>
          EmberServerBuilder
            .default[IO]
            .withHost(httpHost)
            .withPort(httpPort)
            .withHttpApp(observedApp)
            .build
            .use(_ => syncScheduler.run(1.second, limit = 100))
        }
      }
    } yield ()

  private val httpHost: Host =
    sys.env
      .get("INFRADESK_HTTP_HOST")
      .flatMap(Host.fromString)
      .getOrElse(defaultHttpHost)

  private val httpPort: Port =
    sys.env
      .get("INFRADESK_HTTP_PORT")
      .flatMap(value => Try(value.toInt).toOption)
      .flatMap(Port.fromInt)
      .getOrElse(defaultHttpPort)

  private lazy val defaultHttpHost: Host =
    Host.fromString("0.0.0.0").getOrElse(
      throw new IllegalStateException("Unable to construct default HTTP host")
    )

  private lazy val defaultHttpPort: Port =
    Port.fromInt(8080).getOrElse(
      throw new IllegalStateException("Unable to construct default HTTP port")
    )
}
