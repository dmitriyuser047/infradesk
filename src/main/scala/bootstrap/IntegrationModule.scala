package ru.bitec.app.ops
package bootstrap

import application.connector.ResourceConnectorRegistry
import application.port.{
  NotificationSender,
  ResourceOperationBudget,
  ResourceOperationExecutor,
  SshConnectionProbe,
  SshPasswordResolver
}
import domain.operation.ResourceOperationCode
import cats.effect.{IO, Resource}
import infrastructure.config.{AppConfig, NotificationConfig}
import integration.notification.WebhookNotificationSender
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import integration.docker.{DockerConnector, DockerJavaEngineClient}
import integration.ssh.{
  CompositeSshAuthenticationProvider,
  ConnectionSecretCipher,
  SshConnectionProbeAdapter,
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
  sshConnectionProbe: SshConnectionProbe[IO],
  sshPasswordResolver: SshPasswordResolver[IO],
  resourceOperationExecutor: ResourceOperationExecutor[IO],
  resourceOperationBudget: ResourceOperationBudget,
  connectorRegistry: ResourceConnectorRegistry[IO]
)

object IntegrationModule {

  def build(config: AppConfig, persistence: PersistenceComponents): IntegrationComponents = {
    val secretCipher = ConnectionSecretCipher.fromConfig(config.secretEncryption)

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
      sshConnectionProbe = new SshConnectionProbeAdapter(sshClient),
      sshPasswordResolver = sshAuthenticationProvider,
      resourceOperationExecutor = sshContainerOperations,
      resourceOperationBudget = sshContainerOperations,
      connectorRegistry = connectorRegistry
    )
  }

  /** One HTTP client for the lifetime of the application, or none at all when no webhook is
    * configured: a deployment without notifications opens no client and starts no dispatcher.
    */
  def notificationSender(config: NotificationConfig): Resource[IO, Option[NotificationSender[IO]]] =
    config.webhookUrl match {
      case None => Resource.pure[IO, Option[NotificationSender[IO]]](None)
      case Some(url) =>
        EmberClientBuilder
          .default[IO]
          .withTimeout(config.requestTimeout)
          .build
          .map { client: Client[IO] =>
            Some(new WebhookNotificationSender(client, url, config.requestTimeout))
          }
    }
}
