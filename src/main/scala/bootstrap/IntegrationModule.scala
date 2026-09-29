package ru.bitec.app.ops
package bootstrap

import application.connector.ResourceConnectorRegistry
import application.integration.IntegrationProviderRegistry
import application.port.{
  ConnectionSyncBudget,
  EmailNotificationTransport,
  NotificationSender,
  ResourceOperationBudget,
  ResourceOperationExecutor,
  SshConnectionProbe,
  SshCredentialResolver,
  TelegramNotificationTransport,
  WebhookNotificationTransport
}
import application.connection.OpenSshTerminal
import domain.operation.ResourceOperationCode
import cats.effect.{IO, Resource}
import cats.syntax.all._
import infrastructure.config.{AppConfig, IntegrationsConfig, NotificationConfig}
import integration.http.{OutboundDestinationPolicy, ValidatingSocketGroup}
import integration.notification.{
  ManagedWebhookTransport,
  NotificationChannelCipher,
  SmtpTransport,
  TelegramTransport,
  WebhookNotificationSender
}
import fs2.io.net.Network
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import integration.docker.{DockerConnector, DockerJavaEngineClient}
import integration.ssh.{
  CompositeSshAuthenticationProvider,
  ConnectionSecretCipher,
  SshConnectionProbeAdapter,
  SshConnectionSyncBudget,
  SshConnector,
  SshjClient,
  SshjConfigurationTransport
}
import integration.ssh.docker.SshContainerOperationExecutor
import integration.remnawave.{RemnawaveClient, RemnawaveProvider}
import integration.secret.IntegrationCredentialCipher
import org.typelevel.doobie.ConnectionIO
import org.typelevel.log4cats.Logger

/** External-system clients and the connector registry built on top of them.
  *
  * Every client is created once per runtime; per-operation lifecycles (SSH sessions, Docker
  * requests) stay inside the clients themselves, exactly as before.
  */
final case class IntegrationComponents(
  secretCipher: ConnectionSecretCipher,
  notificationChannelCipher: NotificationChannelCipher,
  integrationCredentialCipher: IntegrationCredentialCipher,
  integrationProviderRegistry: IntegrationProviderRegistry[IO],
  sshConnectionProbe: SshConnectionProbe[IO],
  sshCredentialResolver: SshCredentialResolver[IO],
  resourceOperationExecutor: ResourceOperationExecutor[IO],
  resourceOperationBudget: ResourceOperationBudget,
  connectionSyncBudget: ConnectionSyncBudget,
  openSshTerminal: OpenSshTerminal[ConnectionIO],
  configurationTransport: application.port.RemoteConfigurationTransport[IO],
  connectorRegistry: ResourceConnectorRegistry[IO]
)

object IntegrationModule {

  def build(config: AppConfig, persistence: PersistenceComponents,
    providers: IntegrationProviderRegistry[IO]): IO[IntegrationComponents] = {
    val secretCipher = ConnectionSecretCipher.fromConfig(config.secretEncryption)
    // The same key and the same primitive as an SSH credential; a different payload and a
    // different table.
    val notificationChannelCipher = NotificationChannelCipher.fromConfig(config.secretEncryption)
    val integrationCredentialCipher = IntegrationCredentialCipher.fromConfig(config.secretEncryption)

    val sshClient = new SshjClient[IO]

    val sshAuthenticationProvider =
      new CompositeSshAuthenticationProvider[ConnectionIO](
        persistence.connectionSecretRepository,
        persistence.transactionRunner,
        secretCipher,
        config.sshEnvironmentSecrets
      )

    // One adapter serves both roles: it runs the command and states how long an attempt can take.
    val sshContainerOperations =
      new SshContainerOperationExecutor[IO](sshClient, sshAuthenticationProvider)

    val connectorRegistry =
      new ResourceConnectorRegistry[IO](
        List(
          new DockerConnector[IO](new DockerJavaEngineClient[IO]),
          new SshConnector[IO](sshClient, sshAuthenticationProvider)
        )
      )

    OpenSshTerminal.create(
      persistence.connectionRepository,
      persistence.transactionRunner,
      sshClient,
      sshAuthenticationProvider,
      config.terminal.maxConcurrentSessions
    ).map { openSshTerminal => IntegrationComponents(
      secretCipher = secretCipher,
      notificationChannelCipher = notificationChannelCipher,
      integrationCredentialCipher = integrationCredentialCipher,
      integrationProviderRegistry = providers,
      sshConnectionProbe = new SshConnectionProbeAdapter(sshClient),
      sshCredentialResolver = sshAuthenticationProvider,
      resourceOperationExecutor = sshContainerOperations,
      resourceOperationBudget = sshContainerOperations,
      connectionSyncBudget = new SshConnectionSyncBudget,
      openSshTerminal = openSshTerminal,
      configurationTransport = new SshjConfigurationTransport(sshClient, sshAuthenticationProvider,
        config.configurationDeployment.sftpTimeout),
      connectorRegistry = connectorRegistry
    ) }
  }

  /** One validated HTTP client for all product integration tests in this runtime. */
  def integrationProviders(config: IntegrationsConfig): Resource[IO, IntegrationProviderRegistry[IO]] = {
    val policy = OutboundDestinationPolicy.resolving(config.allowPrivateDestinations,
      config.requestTimeout)
    EmberClientBuilder.default[IO].withTimeout(config.requestTimeout)
      .withSocketGroup(new ValidatingSocketGroup(Network[IO], policy)).build.map { client =>
        new IntegrationProviderRegistry[IO](List(new RemnawaveProvider(
          new RemnawaveClient(client, config.requestTimeout))))
      }
  }

  /** Everything the notification workers need in order to reach the outside world.
    *
    * Two HTTP clients, both opened once, whether or not anything ends up using them: channels
    * live in the database, so what will be sent is not known at startup. The Telegram endpoint
    * is fixed and the deployment's own webhook is operator configuration, so both share an
    * ordinary client; a channel's webhook is a destination a tenant chose, so it gets its own
    * client, built on a `SocketGroup` that resolves and validates that destination itself, at
    * the moment it connects, rather than trusting a check made earlier against a name that could
    * since answer differently. Mail opens its own connection per message, because SMTP is a
    * session, and validates its own destination the same way, inside the SMTP handshake itself.
    *
    * The legacy sender is still the one thing that depends on the environment: without its URL
    * there is no deployment webhook, and the worker that serves it does not start.
    */
  def notificationTransports(
    config: NotificationConfig,
    logger: Logger[IO]
  ): Resource[IO, NotificationTransports] = {
    val policy = OutboundDestinationPolicy.resolving(
      config.allowPrivateDestinations,
      config.requestTimeout
    )
    (
      EmberClientBuilder.default[IO].withTimeout(config.requestTimeout).build,
      EmberClientBuilder.default[IO]
        .withTimeout(config.requestTimeout)
        .withSocketGroup(new ValidatingSocketGroup(Network[IO], policy))
        .build
    ).mapN { case (client: Client[IO], validatedClient: Client[IO]) =>
      NotificationTransports(
        legacyWebhookSender = config.webhookUrl.map(url =>
          new WebhookNotificationSender(client, url, config.requestTimeout)),
        webhook = new ManagedWebhookTransport(validatedClient, config.requestTimeout),
        telegram = new TelegramTransport(client, config.requestTimeout),
        email = new SmtpTransport(policy, config.requestTimeout, Some(logger))
      )
    }
  }
}

/** The outbound side of notifications, assembled once per runtime. */
final case class NotificationTransports(
  /** Absent when the deployment configures no webhook of its own. */
  legacyWebhookSender: Option[NotificationSender[IO]],
  webhook: WebhookNotificationTransport[IO],
  telegram: TelegramNotificationTransport[IO],
  email: EmailNotificationTransport[IO]
)
