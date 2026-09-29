package ru.bitec.app.ops
package application.integration

import application.auth.ActorContext
import application.port.{IntegrationCryptography, TransactionRunner}
import cats.effect.IO
import java.util.UUID

/** The database transaction commits test intent before decrypting and contacting Remnawave. */
final class TestIntegration[Tx[_]](management: IntegrationManagement[Tx],
  runner: TransactionRunner[IO, Tx], cipher: IntegrationCryptography,
  providers: IntegrationProviderRegistry[IO]) {
  def execute(actor: ActorContext, id: UUID): IO[IntegrationTestResult] =
    runner.run(management.prepareTest(actor, id)).flatMap { case (integration, secret) =>
      val provider = providers.find(integration.providerType).toRight(
        IntegrationError("INTEGRATION_PROVIDER_UNSUPPORTED", "Integration provider is unavailable"))
      IO.fromEither(provider).flatMap { selected =>
        IO.delay(cipher.decrypt(secret)).handleErrorWith(_ => IO.raiseError(
          IntegrationError("INTEGRATION_CREDENTIAL_INVALID", "Integration credential is invalid")))
          .flatMap(credential => selected.testConnection(IntegrationRuntimeContext(integration.id,
            integration.organizationId, integration.baseUrl, credential)))
      }
    }
}
