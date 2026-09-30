package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationError, IntegrationProviderRegistry, IntegrationRuntimeContext}
import bootstrap.IntegrationModule
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.integration.{IntegrationActionCode, IntegrationActionRemoteOutcome, IntegrationBaseUrl,
  IntegrationCapability, IntegrationProviderType, RemnawaveCredential}
import integration.http.OutboundDestinationPolicy
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

  test("registry rejects duplicate providers and advertises implemented capabilities") {
    val provider = new RemnawaveProvider(new RemnawaveClient(Client.fromHttpApp(HttpApp.notFound[IO]), 1.second))
    val registry = new IntegrationProviderRegistry[IO](List(provider))
    assertEquals(registry.find(IntegrationProviderType.Remnawave), Some(provider))
    assertEquals(provider.capabilities, Set[IntegrationCapability](IntegrationCapability.ConnectivityTest,
      IntegrationCapability.NodeDiscovery, IntegrationCapability.HostDiscovery,
      IntegrationCapability.ConfigProfileDiscovery, IntegrationCapability.MetricsRead,
      IntegrationCapability.SafeActions, IntegrationCapability.DesiredState,
      IntegrationCapability.ConfigProfileManagement))
    assert(provider.configProfiles.nonEmpty)
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

  test("node actions send exactly one authenticated POST to each upstream route") {
    val id = UUID.randomUUID()
    val actions = List(IntegrationActionCode.NodeEnable -> "enable",
      IntegrationActionCode.NodeDisable -> "disable", IntegrationActionCode.NodeRestart -> "restart")
    actions.foreach { case (action, suffix) =>
      val observed = (for {
        seen <- Ref.of[IO, List[(String, String, String, Boolean, Boolean, Boolean)]](Nil)
        app = HttpApp[IO] { request => request.as[String].flatMap { body =>
          val auth = request.headers.get(CIString("Authorization")).exists(_.head.value == s"Bearer $token")
          val caddy = request.headers.get(CIString("X-Api-Key")).exists(_.head.value == "stage24a-caddy-key")
          val contentType = request.headers.get(CIString("Content-Type")).exists(_.head.value == "application/json")
          seen.update(_ :+ (request.method.name, request.uri.path.renderString, body, auth, caddy, contentType)).as {
            if (action == IntegrationActionCode.NodeRestart) Response[IO](Status.Accepted)
            else Response[IO](Status.Ok).withEntity(s"{\"response\":{\"uuid\":\"$id\"}}")
          }
        }}
        result <- new RemnawaveClient(Client.fromHttpApp(app), 1.second).action(base, credential, id.toString, action)
        requests <- seen.get
      } yield (result, requests)).unsafeRunSync()
      assertEquals(observed._1, IntegrationActionRemoteOutcome.Succeeded)
      assertEquals(observed._2, List(("POST", s"/prefix/api/nodes/$id/actions/$suffix",
        if (action == IntegrationActionCode.NodeRestart) "{\"forceRestart\":false}" else "", true, true, true)))
    }
  }

  test("ambiguous writes stay unknown while definite rejections fail") {
    val id = UUID.randomUUID().toString
    def result(status: Status, body: String) = new RemnawaveClient(Client.fromHttpApp(
      HttpApp[IO](_ => IO.pure(Response[IO](status).withEntity(body)))), 1.second)
      .action(base, credential, id, IntegrationActionCode.NodeDisable).unsafeRunSync()
    assertEquals(result(Status.Unauthorized, "secret"),
      IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_AUTH_FAILED"))
    assertEquals(result(Status.ServiceUnavailable, "secret"),
      IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN"))
    assertEquals(result(Status.Ok, "bad json"),
      IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN"))
    val timeout = new RemnawaveClient(Client.fromHttpApp(HttpApp[IO](_ => IO.never[Response[IO]])), 30.millis)
      .action(base, credential, id, IntegrationActionCode.NodeRestart).unsafeRunSync()
    assertEquals(timeout, IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN"))
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

  test("an explicit base URL port must be 1-65535; no port means the scheme's default") {
    List("https://panel.example.com", "https://panel.example.com:443", "http://panel.example.com:8080",
      "http://panel.example.com:1", "http://panel.example.com:65535")
      .foreach(raw => assert(IntegrationBaseUrl.parse(raw).isRight, raw))
    List("http://panel.example.com:0", "https://panel.example.com:65536", "https://panel.example.com:99999")
      .foreach(raw => assert(IntegrationBaseUrl.parse(raw).isLeft, raw))
  }

  /** A stats envelope padded with an extra field to exactly `size` bytes of UTF-8. */
  private def envelopeOf(size: Int): String = {
    val head = "{\"response\":{\"uptime\":12,\"users\":{\"totalUsers\":3}},\"padding\":\""
    val tail = "\"}"
    head + "x" * (size - head.length - tail.length) + tail
  }

  // Bounded from outside as well: a probe that never returns fails the test instead of hanging it.
  private def probeWith(response: Response[IO]): Either[Option[String], Unit] =
    new RemnawaveClient(Client.fromHttpApp(HttpApp[IO](_ => IO.pure(response))), 5.seconds).probe(base, credential)
      .timeout(15.seconds).attempt.unsafeRunSync().left.map(error => Option(error).collect { case e: IntegrationError => e.code })

  private val limit = RemnawaveClient.MaxResponseBytes

  private def bounded(response: Response[IO]): Option[Int] =
    RemnawaveClient.boundedBody(response).timeout(15.seconds).unsafeRunSync().map(_.length)

  test("a response of exactly 64 KiB is accepted") {
    assertEquals(limit, 65536)
    assertEquals(bounded(Response[IO](Status.Ok).withEntity(envelopeOf(limit))), Some(limit))
    assertEquals(probeWith(Response[IO](Status.Ok).withEntity(envelopeOf(limit))), Right(()))
  }

  test("a response one byte over 64 KiB is an invalid response, with or without Content-Length") {
    // Announced: refused before the body is read.
    assertEquals(probeWith(Response[IO](Status.Ok).withEntity(envelopeOf(limit + 1))),
      Left(Some("INTEGRATION_INVALID_RESPONSE")))
    // Not announced: refused after at most limit + 1 raw bytes.
    val bytes = envelopeOf(limit + 1).getBytes("UTF-8")
    val streamed = Response[IO](Status.Ok).withBodyStream(fs2.Stream.chunk(fs2.Chunk.array(bytes)).covary[IO])
    assert(streamed.contentLength.isEmpty)
    assertEquals(bounded(streamed), None)
    assertEquals(probeWith(streamed), Left(Some("INTEGRATION_INVALID_RESPONSE")))
  }

  test("an endless body stops being read at the limit instead of filling memory") {
    // Read through the bounded reader itself: the in-memory test client drains an unread body when
    // it is released, which an endless one would never finish; the runtime client closes it.
    val pulled = Ref.of[IO, Long](0).unsafeRunSync()
    val block = fs2.Chunk.array(Array.fill[Byte](8192)('x'.toByte))
    val endless = Response[IO](Status.Ok).withBodyStream(
      fs2.Stream.repeatEval(pulled.update(_ + block.size).as(block)).flatMap(fs2.Stream.chunk))
    assertEquals(bounded(endless), None)
    assert(pulled.get.unsafeRunSync() <= limit + 8192L)
  }

  test("a small valid envelope succeeds; bytes that are not UTF-8 are an invalid response") {
    assertEquals(probeWith(Response[IO](Status.Ok).withEntity("{\"response\":{\"uptime\":1,\"users\":{\"totalUsers\":0}}}")), Right(()))
    val invalidUtf8 = "{\"response\":{\"uptime\":1,\"users\":{\"totalUsers\":0}},\"x\":\"".getBytes("UTF-8") ++
      Array(0xff.toByte, 0xfe.toByte) ++ "\"}".getBytes("UTF-8")
    assertEquals(probeWith(Response[IO](Status.Ok).withBodyStream(fs2.Stream.emits(invalidUtf8.toSeq))),
      Left(Some("INTEGRATION_INVALID_RESPONSE")))
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
    val action = IntegrationModule.integrationProviders(IntegrationsConfig(1.second,
      allowPrivateDestinations = false)).use { registry =>
      registry.find(IntegrationProviderType.Remnawave).get.executeAction(
        IntegrationRuntimeContext(UUID.randomUUID(), UUID.randomUUID(), local, credential),
        UUID.randomUUID().toString, IntegrationActionCode.NodeRestart)
    }.unsafeRunSync()
    assertEquals(action, IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_DESTINATION_NOT_ALLOWED"))
  }
}
