package ru.bitec.app.ops
package bootstrap

import application.connector.ResourceConnectorRegistry
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
import domain.operation.ResourceOperationCode
import cats.effect.{IO, Resource}
import infrastructure.config.{AppConfig, NotificationConfig}
import integration.notification.{
  ManagedWebhookTransport,
  NotificationChannelCipher,
  OutboundDestinationPolicy,
  SmtpTransport,
  TelegramTransport,
  WebhookNotificationSender
}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import integration.docker.{DockerConnector, DockerJavaEngineClient}
import integration.ssh.{
  CompositeSshAuthenticationProvider,
  ConnectionSecretCipher,
  SshConnectionProbeAdapter,
  SshConnectionSyncBudget,
  SshConnector,
  SshjClient
}
import integration.ssh.docker.SshContainerOperationExecutor
import org.typelevel.doobie.ConnectionIO

/** External-system clients and the connector registry built on top of them.
  *
  * Every client is created once per runtime; per-operation lifecycles (SSH sessions, Docker
  * requests) stay inside the clients themselves, exactly as before.
  */
final case class IntegrationComponents(
  secretCipher: ConnectionSecretCipher,
  notificationChannelCipher: NotificationChannelCipher,
  sshConnectionProbe: SshConnectionProbe[IO],
  sshCredentialResolver: SshCredentialResolver[IO],
  resourceOperationExecutor: ResourceOperationExecutor[IO],
  resourceOperationBudget: ResourceOperationBudget,
  connectionSyncBudget: ConnectionSyncBudget,
  connectorRegistry: ResourceConnectorRegistry[IO]
)

object IntegrationModule {

  def build(config: AppConfig, persistence: PersistenceComponents): IntegrationComponents = {
    val secretCipher = ConnectionSecretCipher.fromConfig(config.secretEncryption)
    // The same key and the same primitive as an SSH credential; a different payload and a
    // different table.
    val notificationChannelCipher = NotificationChannelCipher.fromConfig(config.secretEncryption)

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

    IntegrationComponents(
      secretCipher = secretCipher,
      notificationChannelCipher = notificationChannelCipher,
      sshConnectionProbe = new SshConnectionProbeAdapter(sshClient),
      sshCredentialResolver = sshAuthenticationProvider,
      resourceOperationExecutor = sshContainerOperations,
      resourceOperationBudget = sshContainerOperations,
      connectionSyncBudget = new SshConnectionSyncBudget,
      connectorRegistry = connectorRegistry
    )
  }

  /** Everything the notification workers need in order to reach the outside world.
    *
    * One HTTP client for the lifetime of the application, shared by the webhook and the Telegram
    * transports; mail opens its own connection per message, because SMTP is a session. The
    * client is opened whether or not the deployment configures a webhook of its own: channels
    * live in the database, so whether anything will be sent is not known at startup.
    *
    * The legacy sender is still the one thing that depends on the environment: without its URL
    * there is no deployment webhook, and the worker that serves it does not start.
    */
  def notificationTransports(config: NotificationConfig): Resource[IO, NotificationTransports] = {
    val policy = OutboundDestinationPolicy.resolving(config.allowPrivateDestinations)
    EmberClientBuilder
      .default[IO]
      .withTimeout(config.requestTimeout)
      .build
      .map { client: Client[IO] =>
        NotificationTransports(
          legacyWebhookSender = config.webhookUrl.map(url =>
            new WebhookNotificationSender(client, url, config.requestTimeout)),
          webhook = new ManagedWebhookTransport(client, policy, config.requestTimeout),
          telegram = new TelegramTransport(client, config.requestTimeout),
          email = new SmtpTransport(policy, config.requestTimeout)
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
