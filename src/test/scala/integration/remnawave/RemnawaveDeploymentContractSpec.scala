package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationError, IntegrationRuntimeContext}
import bootstrap.IntegrationModule
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.integration._
import infrastructure.config.IntegrationsConfig
import munit.FunSuite
import java.util.UUID
import scala.concurrent.duration._

/** Opt-in, authenticated reads of the real deployment using the production adapters and SSRF policy.
  * No create, keygen, action, configuration write, SSH, or VPS canary is performed here.
  */
final class RemnawaveDeploymentContractSpec extends FunSuite {
  test("real deployment metadata, inventory and Node status confirm the selected adapter") {
    assume(sys.env.get("INFRADESK_RUN_REMNAWAVE_CONTRACT_TESTS").contains("true"),
      "Set INFRADESK_RUN_REMNAWAVE_CONTRACT_TESTS=true and the contract-test credentials for authenticated reads")
    val base = IntegrationBaseUrl.parse(sys.env.getOrElse("INFRADESK_REMNAWAVE_CONTRACT_BASE_URL",
      "https://ax7k2.velesoracle.com")).toOption.getOrElse(fail("Invalid contract-test base URL"))
    val credential = RemnawaveCredential(sys.env.getOrElse("INFRADESK_REMNAWAVE_CONTRACT_API_TOKEN",
      fail("Missing contract-test API token")), sys.env.get("INFRADESK_REMNAWAVE_CONTRACT_CADDY_KEY"))
    val context = IntegrationRuntimeContext(UUID.randomUUID(), UUID.randomUUID(), base, credential)
    val result = IntegrationModule.integrationProviders(IntegrationsConfig(10.seconds,
      allowPrivateDestinations = false)).use { registry =>
      val provider = registry.find(IntegrationProviderType.Remnawave).get
      val transport = provider.nodeProvisioning.get
      for {
        tested <- provider.testConnection(context)
        inventory <- provider.observe(context)
        nodes <- transport.findNodes(context)
        _ <- nodes.headOption.traverse_(node => transport.getNode(context, node.externalId).flatMap { found =>
          IO.raiseUnless(found.externalId == node.externalId)(RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE"))
        })
      } yield (tested, inventory, nodes)
    }.timeout(60.seconds).handleErrorWith {
      case error: IntegrationError => IO.raiseError(new IllegalStateException(error.code))
      case _ => IO.raiseError(new IllegalStateException("DEPLOYMENT_CONTRACT_READ_FAILED"))
    }.unsafeRunSync()
    val api = result._1.nodeApi.getOrElse(fail("Compatibility evidence was not returned"))
    assert(api.provisioningReady, api.blocker.getOrElse("Compatibility was not confirmed"))
    sys.env.get("INFRADESK_REMNAWAVE_CONTRACT_EXPECTED_VERSION").foreach(expected =>
      assertEquals(api.serverVersion, Some(expected)))
    val imported = result._2.objects.filter(_.objectType == IntegrationObjectType.Node).map(_.externalId).toSet
    assertEquals(result._3.map(_.externalId.toString).toSet, imported)
    // Only sanitized evidence is reported; never serialize a raw HTTP response or credential.
    println(s"Remnawave contract confirmed: version=${api.serverVersion.getOrElse("unknown")} " +
      s"generation=${api.apiGeneration.getOrElse("unknown")} commit=${api.sourceCommit.getOrElse("unknown")}")
  }
}
