package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationError, IntegrationRuntimeContext}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.integration._
import munit.FunSuite
import org.http4s.{HttpApp, Request, Response, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import serialization.integration.IntegrationSummaryJson

import java.util.UUID
import scala.concurrent.duration._

final class RemnawaveInventorySpec extends FunSuite {
  private val token = "stage24b-private-token"
  private val caddy = "stage24b-caddy-key"
  private val base = IntegrationBaseUrl.parse("https://panel.example.test/prefix").toOption.get
  private val context = IntegrationRuntimeContext(UUID.randomUUID(), UUID.randomUUID(), base,
    RemnawaveCredential(token, Some(caddy)))

  // Secrets a real panel returns next to the fields InfraDesk reads.
  private val proxyUrl = "socks5://proxy-user:proxy-secret@10.9.9.9:1080"
  private val rawInbound = "RAW-INBOUND-PRIVATE-KEY"
  private val xrayConfig = "XRAY-CONFIG-PRIVATE-KEY"

  private val nodesBody =
    s"""{"response":[
      {"uuid":"11111111-1111-1111-1111-111111111111","name":"Frankfurt","address":"203.0.113.10","port":2222,
       "isConnected":true,"isDisabled":false,"isConnecting":false,"lastStatusChange":"2026-09-01T10:00:00.000Z",
       "xrayVersion":"25.9.11","nodeVersion":"2.1.0","xrayUptime":"3600","isTrafficTrackingActive":true,
       "trafficLimitBytes":1000,"trafficUsedBytes":250,"usersOnline":7,"countryCode":"DE","cpuCount":4,
       "cpuModel":"EPYC","totalRam":"8 GB","proxyUrl":"$proxyUrl","tags":["eu","prod"],
       "configProfile":{"activeConfigProfileUuid":"33333333-3333-3333-3333-333333333333","activeInbounds":[{"rawInbound":"$rawInbound"}]},
       "provider":{"uuid":"44444444-4444-4444-4444-444444444444","name":"Hetzner"}},
      {"uuid":"22222222-2222-2222-2222-222222222222","name":"Idle","address":"203.0.113.11",
       "isConnected":false,"isDisabled":true,"isConnecting":false}
    ]}"""
  private val hostsBody =
    s"""{"response":[{"uuid":"55555555-5555-5555-5555-555555555555","remark":"Main host","address":"vpn.example.test",
      "port":443,"isDisabled":false,"isHidden":false,"securityLayer":"TLS",
      "inbound":{"configProfileUuid":"33333333-3333-3333-3333-333333333333","configProfileInboundUuid":"66666666-6666-6666-6666-666666666666"},
      "xhttpExtraParams":{"secret":"$xrayConfig"},"muxParams":{"x":1},"sockoptParams":{"y":2},"finalMask":"$xrayConfig",
      "nodes":["11111111-1111-1111-1111-111111111111"],"tag":"primary"}]}"""
  private val profilesBody =
    s"""{"response":{"total":1,"configProfiles":[{"uuid":"33333333-3333-3333-3333-333333333333","name":"Default",
      "viewPosition":1,"config":{"inbounds":[{"settings":{"secret":"$xrayConfig"}}]},
      "inbounds":[{"uuid":"66666666-6666-6666-6666-666666666666","profileUuid":"33333333-3333-3333-3333-333333333333",
        "tag":"VLESS_TCP","type":"vless","network":"tcp","security":"reality","port":443,"rawInbound":{"k":"$rawInbound"}}],
      "nodes":[{"uuid":"11111111-1111-1111-1111-111111111111","name":"Frankfurt"}],
      "createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-09-01T00:00:00.000Z"}]}}"""

  private final case class Seen(method: String, path: String, authorized: Boolean, caddyKey: Boolean)

  private def panel(bodies: Map[String, IO[Response[IO]]], seen: Ref[IO, List[Seen]]): HttpApp[IO] =
    HttpApp[IO] { request: Request[IO] =>
      val path = request.uri.path.renderString
      seen.update(_ :+ Seen(request.method.name, path,
        request.headers.get(CIString("Authorization")).exists(_.head.value == s"Bearer $token"),
        request.headers.get(CIString("X-Api-Key")).exists(_.head.value == caddy))) *>
        bodies.getOrElse(path, IO.pure(Response[IO](Status.NotFound)))
    }

  private def ok(body: String) = IO.pure(Response[IO](Status.Ok).withEntity(body))
  private val healthy = Map("/prefix/api/nodes" -> ok(nodesBody), "/prefix/api/hosts" -> ok(hostsBody),
    "/prefix/api/config-profiles" -> ok(profilesBody))

  private def observe(bodies: Map[String, IO[Response[IO]]], timeout: FiniteDuration = 2.seconds,
    maxBytes: Int = 1024 * 1024, maxObjects: Int = 100): (Either[Throwable, IntegrationObservation], List[Seen]) =
    (for {
      seen <- Ref.of[IO, List[Seen]](Nil)
      provider = new RemnawaveProvider(new RemnawaveClient(Client.fromHttpApp(panel(bodies, seen)), timeout,
        maxBytes, maxObjects))
      result <- provider.observe(context).timeout(20.seconds).attempt
      requests <- seen.get
    } yield (result, requests)).unsafeRunSync()

  private def codeOf(result: Either[Throwable, IntegrationObservation]): Option[String] =
    result.left.toOption.collect { case error: IntegrationError => error.code }

  test("one snapshot is exactly three authenticated GETs, one per listing") {
    val (result, requests) = observe(healthy)
    assert(result.isRight, result.toString)
    assertEquals(requests.map(_.path).sorted,
      List("/prefix/api/config-profiles", "/prefix/api/hosts", "/prefix/api/nodes"))
    assert(requests.forall(value => value.method == "GET" && value.authorized && value.caddyKey), requests.toString)
    val observation = result.toOption.get
    assertEquals(observation.completeObjectTypes, IntegrationObjectType.All.toSet)
    assertEquals(observation.count(IntegrationObjectType.Node), 2)
    assertEquals(observation.count(IntegrationObjectType.Host), 1)
    assertEquals(observation.count(IntegrationObjectType.ConfigProfile), 1)
  }

  test("the projection is typed and keeps no proxy URL, raw inbound, Xray config or transport params") {
    val observation = observe(healthy)._1.toOption.get
    val node = observation.objects.collectFirst { case ObservedIntegrationObject(_, "11111111-1111-1111-1111-111111111111", name,
      summary: RemnawaveNodeSummary) => (name, summary) }.get
    assertEquals(node._1, "Frankfurt")
    assertEquals(node._2.state, RemnawaveNodeState.Connected)
    assertEquals(node._2.port, Some(2222))
    assertEquals(node._2.xrayUptimeSeconds, Some(3600L))
    assertEquals(node._2.activeConfigProfileUuid, Some("33333333-3333-3333-3333-333333333333"))
    assertEquals(node._2.providerName, Some("Hetzner"))
    assertEquals(node._2.tags, List("eu", "prod"))
    val idle = observation.objects.collectFirst { case ObservedIntegrationObject(_, "22222222-2222-2222-2222-222222222222", _,
      summary: RemnawaveNodeSummary) => summary }.get
    assertEquals(idle.state, RemnawaveNodeState.Disabled)
    assertEquals(idle.port, None)
    val host = observation.objects.collectFirst { case ObservedIntegrationObject(_, _, _, s: RemnawaveHostSummary) => s }.get
    assertEquals(host.configProfileInboundUuid, Some("66666666-6666-6666-6666-666666666666"))
    assertEquals(host.nodeUuids, List("11111111-1111-1111-1111-111111111111"))
    assertEquals(host.tags, List("primary"))
    val profile = observation.objects.collectFirst { case ObservedIntegrationObject(_, _, _, s: RemnawaveConfigProfileSummary) => s }.get
    assertEquals(profile.inbounds.map(_.tag), List("VLESS_TCP"))
    assertEquals(profile.nodeUuids, List("11111111-1111-1111-1111-111111111111"))
    // Everything that could be persisted, logged or published.
    val stored = observation.objects.map(value => IntegrationSummaryJson.encode(value.summary).noSpaces).mkString +
      observation.toString
    List(proxyUrl, "proxy-secret", rawInbound, xrayConfig, "xhttpExtraParams", "muxParams", "sockoptParams",
      "finalMask", "rawInbound", "proxyUrl", token, caddy).foreach(secret => assert(!stored.contains(secret), secret))
  }

  test("empty listings are a valid, complete, empty snapshot") {
    val empty = Map("/prefix/api/nodes" -> ok("""{"response":[]}"""), "/prefix/api/hosts" -> ok("""{"response":[]}"""),
      "/prefix/api/config-profiles" -> ok("""{"response":{"total":0,"configProfiles":[]}}"""))
    val observation = observe(empty)._1.toOption.get
    assertEquals(observation.objects, Nil)
    assertEquals(observation.completeObjectTypes, IntegrationObjectType.All.toSet)
  }

  test("a malformed or unwrapped listing fails the whole snapshot as an invalid response") {
    List(
      "not json",
      """{"nodes":[]}""",
      """{"response":{}}""",
      """{"response":[{"name":"no uuid","address":"a"}]}""",
      """{"response":[{"uuid":"x","address":"a"}]}""",
      """{"response":[{"uuid":42,"name":"n","address":"a"}]}"""
    ).foreach { body =>
      val (result, _) = observe(healthy + ("/prefix/api/nodes" -> ok(body)))
      assertEquals(codeOf(result), Some("INTEGRATION_INVALID_RESPONSE"), body)
    }
  }

  test("any failing endpoint fails the snapshot with its sanitized code") {
    List(Status.Unauthorized -> "INTEGRATION_AUTH_FAILED", Status.Forbidden -> "INTEGRATION_FORBIDDEN",
      Status.NotFound -> "INTEGRATION_ENDPOINT_NOT_FOUND", Status.TooManyRequests -> "INTEGRATION_RATE_LIMITED",
      Status.BadGateway -> "INTEGRATION_REMOTE_UNAVAILABLE").foreach { case (status, code) =>
      val (result, _) = observe(healthy + ("/prefix/api/hosts" ->
        IO.pure(Response[IO](status).withEntity(s"echo $token $proxyUrl"))))
      assertEquals(codeOf(result), Some(code), status.toString)
      assert(!result.left.toOption.get.getMessage.contains(token))
    }
    val (timedOut, _) = observe(healthy + ("/prefix/api/config-profiles" -> IO.never[Response[IO]]), timeout = 50.millis)
    assertEquals(codeOf(timedOut), Some("INTEGRATION_TIMEOUT"))
  }

  test("a listing over the byte limit or a snapshot over the object limit is an invalid response") {
    val (oversized, _) = observe(healthy, maxBytes = 512)
    assertEquals(codeOf(oversized), Some("INTEGRATION_INVALID_RESPONSE"))
    val (tooMany, _) = observe(healthy, maxObjects = 3)
    assertEquals(codeOf(tooMany), Some("INTEGRATION_INVALID_RESPONSE"))
    assert(observe(healthy, maxObjects = 4)._1.isRight)
    val invalidUtf8 = IO.pure(Response[IO](Status.Ok).withEntity(Array[Byte]('{', 0xC3.toByte, 0x28, '}')))
    assertEquals(codeOf(observe(healthy + ("/prefix/api/nodes" -> invalidUtf8))._1), Some("INTEGRATION_INVALID_RESPONSE"))
  }
}
