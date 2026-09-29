package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationError, IntegrationProviderRegistry, IntegrationRuntimeContext}
import bootstrap.IntegrationModule
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.integration.{IntegrationBaseUrl, IntegrationCapability, IntegrationProviderType, RemnawaveCredential}
import integration.notification.OutboundDestinationPolicy
import integration.ssh.SecretEncryptionConfig
import integration.secret.IntegrationCredentialCipher
import infrastructure.config.IntegrationsConfig
import munit.FunSuite
import org.http4s.{HttpApp, Request, Response, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import java.util.{Base64, UUID}
import scala.concurrent.duration._

final class RemnawaveProviderSpec extends FunSuite {
  private val token = "stage24a-private-token"
  private val credential = RemnawaveCredential(token, Some("stage24a-caddy-key"))
  private val base = IntegrationBaseUrl.parse("https://panel.example.test/prefix/").toOption.get

  test("base URL validation, normalization and safe endpoint construction") {
    assertEquals(base.value, "https://panel.example.test/prefix")
    assertEquals(base.statsEndpoint.toString, "https://panel.example.test/prefix/api/system/stats")
    List("ftp://panel.example.test", "http://", "http://user:pass@panel.example.test",
      "https://panel.example.test/?x=1", "https://panel.example.test/#f")
      .foreach(raw => assert(IntegrationBaseUrl.parse(raw).isLeft))
    assertEquals(IntegrationProviderType.fromCode("REMNAWAVE"), Right(IntegrationProviderType.Remnawave))
    assert(IntegrationProviderType.fromCode("UNKNOWN").isLeft)
    assert(!RemnawaveCredential("", None).valid)
    assert(!RemnawaveCredential("bad\nheader", None).valid)
  }

  test("registry rejects duplicate providers and advertises only implemented capability") {
    val provider = new RemnawaveProvider(new RemnawaveClient(Client.fromHttpApp(HttpApp.notFound[IO]), 1.second))
    val registry = new IntegrationProviderRegistry[IO](List(provider))
    assertEquals(registry.find(IntegrationProviderType.Remnawave), Some(provider))
    assertEquals(provider.capabilities, Set[IntegrationCapability](IntegrationCapability.ConnectivityTest))
    intercept[IllegalArgumentException](new IntegrationProviderRegistry[IO](List(provider, provider)))
  }

  test("read-only probe sends Bearer and optional Caddy key and accepts only stats envelope") {
    val checked = (for {
      seen <- Ref.of[IO, Boolean](false)
      app = HttpApp[IO] { request: Request[IO] =>
        val auth = request.headers.get(CIString("Authorization")).exists(_.head.value == s"Bearer $token")
        val caddy = request.headers.get(CIString("X-Api-Key")).exists(_.head.value == "stage24a-caddy-key")
        seen.set(request.method.name == "GET" && request.uri.path.renderString == "/prefix/api/system/stats" &&
          auth && caddy).as(Response[IO](Status.Ok).withEntity("{\"response\":{\"uptime\":12,\"users\":{\"totalUsers\":0}}}"))
      }
      provider = new RemnawaveProvider(new RemnawaveClient(Client.fromHttpApp(app), 1.second))
      result <- provider.testConnection(IntegrationRuntimeContext(UUID.randomUUID(), UUID.randomUUID(), base, credential))
      matched <- seen.get
    } yield (result, matched)).unsafeRunSync()
    assert(checked._2, "Remnawave request did not match its authenticated read-only contract")
    assert(checked._1.ok)
  }

  test("Caddy header is absent when no Caddy credential is configured") {
    val app = HttpApp[IO] { request =>
      IO.pure(Response[IO](if (request.headers.get(CIString("X-Api-Key")).isEmpty) Status.Ok
        else Status.BadRequest).withEntity("{\"response\":{\"uptime\":1,\"users\":{\"totalUsers\":0}}}"))
    }
    new RemnawaveClient(Client.fromHttpApp(app), 1.second)
      .probe(base, RemnawaveCredential(token, None)).unsafeRunSync()
  }

  test("status, invalid JSON, unexpected envelope and timeout have stable sanitized codes") {
    def failure(status: Status, body: String): String = {
      val app = HttpApp[IO](_ => IO.pure(Response[IO](status).withEntity(body)))
      new RemnawaveClient(Client.fromHttpApp(app), 1.second).probe(base, credential)
        .attempt.unsafeRunSync().left.toOption.collect { case error: IntegrationError => error.code }.get
    }
    List(Status.Unauthorized -> "INTEGRATION_AUTH_FAILED", Status.Forbidden -> "INTEGRATION_FORBIDDEN",
      Status.NotFound -> "INTEGRATION_ENDPOINT_NOT_FOUND", Status.TooManyRequests -> "INTEGRATION_RATE_LIMITED",
      Status.ServiceUnavailable -> "INTEGRATION_REMOTE_UNAVAILABLE").foreach { case (status, expected) =>
      assertEquals(failure(status, "ignored"), expected)
    }
    assertEquals(failure(Status.Ok, "not json"), "INTEGRATION_INVALID_RESPONSE")
    assertEquals(failure(Status.Ok, "{\"response\":{}}"), "INTEGRATION_INVALID_RESPONSE")
    assertEquals(failure(Status.Ok, "{\"response\":[]}"), "INTEGRATION_INVALID_RESPONSE")
    val timeoutApp = HttpApp[IO](_ => IO.never[Response[IO]])
    val timedOut = new RemnawaveClient(Client.fromHttpApp(timeoutApp), 30.millis)
      .probe(base, credential).attempt.unsafeRunSync().left.toOption.collect {
        case error: IntegrationError => error.code
      }
    assertEquals(timedOut, Some("INTEGRATION_TIMEOUT"))
  }

  test("dedicated cipher encrypts both keys and binds them to id and tenant") {
    val key = Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7))
    val cipher = IntegrationCredentialCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(
      Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key)).toOption.get)
    val org = UUID.randomUUID(); val id = UUID.randomUUID()
    val secret = cipher.encrypt(id, org, credential)
    assertEquals(cipher.decrypt(secret), credential)
    assert(!new String(secret.ciphertext, "UTF-8").contains(token))
    intercept[Exception](cipher.decrypt(secret.copy(organizationId = UUID.randomUUID())))
    intercept[IllegalArgumentException](cipher.decrypt(secret.copy(kind = "SSH_CREDENTIAL")))
  }

  test("integration destination policy blocks private addresses by default") {
    val policy = OutboundDestinationPolicy.resolving(allowPrivateNetworks = false)
    val allowed = OutboundDestinationPolicy.resolving(allowPrivateNetworks = true)
    assert(policy.pin("10.0.0.3").unsafeRunSync().isLeft)
    assert(allowed.pin("10.0.0.3").unsafeRunSync().isRight)
    assert(allowed.pin("169.254.169.254").unsafeRunSync().isLeft)
  }

  test("the runtime Remnawave client enforces the private destination policy") {
    val local = IntegrationBaseUrl.parse("http://127.0.0.1:1").toOption.get
    val result = IntegrationModule.integrationProviders(IntegrationsConfig(1.second,
      allowPrivateDestinations = false)).use { registry =>
      registry.find(IntegrationProviderType.Remnawave).get.testConnection(
        IntegrationRuntimeContext(UUID.randomUUID(), UUID.randomUUID(), local, credential))
        .attempt
    }.unsafeRunSync()
    assertEquals(result.left.toOption.collect { case error: IntegrationError => error.code },
      Some("INTEGRATION_DESTINATION_NOT_ALLOWED"))
  }
}
