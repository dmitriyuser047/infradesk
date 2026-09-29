package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationProvider, IntegrationRuntimeContext, IntegrationTestResult}
import cats.effect.IO
import domain.integration.{IntegrationCapability, IntegrationProviderType, RemnawaveCredential}

final class RemnawaveProvider(client: RemnawaveClient) extends IntegrationProvider[IO] {
  override val providerType: IntegrationProviderType = IntegrationProviderType.Remnawave
  override val displayName = "Remnawave"
  override val capabilities: Set[IntegrationCapability] = Set(IntegrationCapability.ConnectivityTest)

  override def testConnection(context: IntegrationRuntimeContext): IO[IntegrationTestResult] =
    context.credential match {
      case credential: RemnawaveCredential => IO.monotonic.flatMap(started =>
        client.probe(context.baseUrl, credential) *> IO.monotonic.map(finished =>
          IntegrationTestResult(ok = true, providerType, (finished - started).toMillis)))
      case _ => IO.raiseError(RemnawaveErrors.error("INTEGRATION_PROVIDER_UNSUPPORTED"))
    }
}
