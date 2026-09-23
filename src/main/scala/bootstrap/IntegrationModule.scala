package ru.bitec.app.ops
package bootstrap

import application.connector.ResourceConnectorRegistry
import application.port.{SshConnectionProbe, SshPasswordResolver}
import cats.effect.IO
import infrastructure.config.AppConfig
import integration.docker.{DockerConnector, DockerJavaEngineClient}
import integration.ssh.{
  CompositeSshAuthenticationProvider,
  ConnectionSecretCipher,
  SshConnectionProbeAdapter,
  SshConnector,
  SshjClient
}
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
      connectorRegistry = connectorRegistry
    )
  }
}
