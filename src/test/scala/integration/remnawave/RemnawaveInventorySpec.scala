package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationError, IntegrationRuntimeContext}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.integration._
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import org.http4s.{HttpApp, Request, Response, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import serialization.integration.IntegrationSummaryJson

import java.time.Instant
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

  private val nodeA = "11111111-1111-1111-1111-111111111111"
  private val nodeB = "22222222-2222-2222-2222-222222222222"
  private val profileId = "33333333-3333-3333-3333-333333333333"
  private val providerId = "44444444-4444-4444-4444-444444444444"
  private val hostId = "55555555-5555-5555-5555-555555555555"
  private val inboundId = "66666666-6666-6666-6666-666666666666"

  private def json(raw: String): Json = parse(raw).fold(throw _, identity)

  /** A node exactly as `NodesSchema` describes it, including fields InfraDesk never reads. */
  private val frankfurt: Json = json(
    s"""{"uuid":"$nodeA","id":1,"name":"Frankfurt","address":"203.0.113.10","port":2222,"proxyUrl":"$proxyUrl",
      "isConnected":true,"isDisabled":false,"isConnecting":false,"lastStatusChange":"2026-09-01T10:00:00.000Z",
      "lastStatusMessage":null,"isTrafficTrackingActive":true,"trafficResetDay":1,"trafficLimitBytes":1000,
      "trafficUsedBytes":250,"notifyPercent":null,"viewPosition":1,"countryCode":"DE","consumptionMultiplier":1,
      "nodeConsumptionMultiplier":1,"tags":["eu","prod"],"integrationUuids":[],"ips":{},
      "createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-09-01T00:00:00.000Z",
      "configProfile":{"activeConfigProfileUuid":"$profileId","activeInbounds":[{"uuid":"$inboundId",
        "profileUuid":"$profileId","tag":"VLESS","type":"vless","network":"tcp","security":"reality","port":443,
        "rawInbound":{"k":"$rawInbound"}}]},
      "providerUuid":"$providerId","provider":{"uuid":"$providerId","name":"Hetzner","faviconLink":null,
        "loginUrl":null,"createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-08-01T00:00:00.000Z"},
      "activePluginUuid":null,
      "system":{"info":{"arch":"x64","cpus":4,"cpuModel":"EPYC","memoryTotal":8589934592,"hostname":"h",
        "platform":"linux","release":"6","type":"Linux","version":"1","networkInterfaces":[]},
        "stats":{"memoryFree":1,"memoryUsed":1,"uptime":1,"loadAvg":[],"interface":null}},
      "versions":{"xray":"25.9.11","node":"2.1.0"},"xrayUptime":3600,"usersOnline":7,"note":null}""")
  private val idle: Json = frankfurt.mapObject(_.add("uuid", Json.fromString(nodeB)).add("name", Json.fromString("Idle"))
    .add("port", Json.Null).add("isConnected", Json.False).add("isDisabled", Json.True).add("versions", Json.Null)
    .add("system", Json.Null).add("provider", Json.Null).add("providerUuid", Json.Null)
    .add("lastStatusChange", Json.Null).add("trafficLimitBytes", Json.Null).add("trafficUsedBytes", Json.Null)
    .add("configProfile", json("""{"activeConfigProfileUuid":null,"activeInbounds":[]}""")))
  private val host: Json = json(
    s"""{"uuid":"$hostId","viewPosition":1,"remark":"Main host","address":"vpn.example.test","port":443,"path":null,
      "sni":null,"host":null,"alpn":null,"fingerprint":null,"isDisabled":false,"securityLayer":"TLS",
      "xhttpExtraParams":{"secret":"$xrayConfig"},"muxParams":{"x":1},"sockoptParams":{"y":2},"finalMask":"$xrayConfig",
      "inbound":{"configProfileUuid":"$profileId","configProfileInboundUuid":"$inboundId"},"serverDescription":null,
      "tags":["primary"],"isHidden":false,"overrideSniFromAddress":false,"keepSniBlank":false,"vlessRouteId":null,
      "pinnedPeerCertSha256":null,"verifyPeerCertByName":null,"shuffleHost":false,"mihomoX25519":false,
      "mihomoIpVersion":null,"nodes":["$nodeA"],"xrayJsonTemplateUuid":null,"excludeFromSubscriptionTypes":[],
      "mapper":{},"internalSquads":{"mode":"ALL","squads":[]}}""")
  private val profile: Json = json(
    s"""{"uuid":"$profileId","viewPosition":1,"name":"Default","tags":[],
      "config":{"inbounds":[{"settings":{"secret":"$xrayConfig"}}]},
      "inbounds":[{"uuid":"$inboundId","profileUuid":"$profileId","tag":"VLESS_TCP","type":"vless","network":"tcp",
        "security":"reality","port":443,"rawInbound":{"k":"$rawInbound"}}],
      "nodes":[{"uuid":"$nodeA","name":"Frankfurt","countryCode":"DE"}],
      "createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-09-01T00:00:00.000Z"}""")

  private def listing(items: Json*): String = Json.obj("response" -> Json.arr(items: _*)).noSpaces
  private def profiles(items: Json*): String =
    Json.obj("response" -> Json.obj("total" -> Json.fromInt(items.size), "configProfiles" -> Json.arr(items: _*))).noSpaces

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
  private val NodesPath = "/prefix/api/nodes"
  private val HostsPath = "/prefix/api/hosts"
  private val ProfilesPath = "/prefix/api/config-profiles"
  private val healthy = Map(NodesPath -> ok(listing(frankfurt, idle)), HostsPath -> ok(listing(host)),
    ProfilesPath -> ok(profiles(profile)))

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

  private def invalid(path: String, body: String): Boolean =
    codeOf(observe(healthy + (path -> ok(body)))._1).contains("INTEGRATION_INVALID_RESPONSE")

  private def without(value: Json, field: String): Json = value.mapObject(_.remove(field))
  private def replaced(value: Json, field: String, next: Json): Json = value.mapObject(_.add(field, next))

  test("one snapshot is exactly three authenticated GETs, one per listing") {
    val (result, requests) = observe(healthy)
    assert(result.isRight, result.toString)
    assertEquals(requests.map(_.path).sorted, List(ProfilesPath, HostsPath, NodesPath))
    assert(requests.forall(value => value.method == "GET" && value.authorized && value.caddyKey), requests.toString)
    val observation = result.toOption.get
    assertEquals(observation.completeObjectTypes, IntegrationObjectType.All.toSet)
    assertEquals(observation.count(IntegrationObjectType.Node), 2)
    assertEquals(observation.count(IntegrationObjectType.Host), 1)
    assertEquals(observation.count(IntegrationObjectType.ConfigProfile), 1)
  }

  test("the projection follows the contract and keeps no proxy URL, raw inbound, Xray config or transport params") {
    val observation = observe(healthy)._1.toOption.get
    def nodeOf(id: String) = observation.objects.collectFirst {
      case ObservedIntegrationObject(_, `id`, name, summary: RemnawaveNodeSummary) => (name, summary) }.get
    val (name, node) = nodeOf(nodeA)
    assertEquals(name, "Frankfurt")
    assertEquals(node.state, RemnawaveNodeState.Connected)
    assertEquals((node.port, node.xrayVersion, node.nodeVersion), (Some(2222), Some("25.9.11"), Some("2.1.0")))
    assertEquals((node.xrayUptimeSeconds, node.usersOnline, node.countryCode), (3600L, 7L, "DE"))
    assertEquals((node.cpuCount, node.cpuModel, node.memoryTotalBytes), (Some(4), Some("EPYC"), Some(8589934592L)))
    assertEquals((node.providerUuid, node.providerName), (Some(providerId), Some("Hetzner")))
    assertEquals(node.activeConfigProfileUuid, Some(profileId))
    assertEquals(node.lastStatusChange, Some(Instant.parse("2026-09-01T10:00:00Z")))
    // Contract nulls are kept as absent values, never replaced by defaults.
    val idleNode = nodeOf(nodeB)._2
    assertEquals(idleNode.state, RemnawaveNodeState.Disabled)
    assertEquals((idleNode.port, idleNode.xrayVersion, idleNode.cpuCount, idleNode.providerName),
      (None, None, None, None))
    val hostSummary = observation.objects.collectFirst { case ObservedIntegrationObject(_, _, _, s: RemnawaveHostSummary) => s }.get
    assertEquals((hostSummary.port, hostSummary.securityLayer, hostSummary.nodeUuids), (443, "TLS", List(nodeA)))
    assertEquals(hostSummary.configProfileInboundUuid, Some(inboundId))
    val profileSummary = observation.objects.collectFirst {
      case ObservedIntegrationObject(_, _, _, s: RemnawaveConfigProfileSummary) => s }.get
    assertEquals(profileSummary.inbounds.map(i => (i.tag, i.inboundType)), List(("VLESS_TCP", "vless")))
    assertEquals((profileSummary.viewPosition, profileSummary.nodeUuids), (1, List(nodeA)))
    val stored = observation.objects.map(value => IntegrationSummaryJson.encode(value.summary).noSpaces).mkString +
      observation.toString
    List(proxyUrl, "proxy-secret", rawInbound, xrayConfig, "xhttpExtraParams", "muxParams", "sockoptParams",
      "finalMask", "rawInbound", "proxyUrl", token, caddy).foreach(secret => assert(!stored.contains(secret), secret))
  }

  test("a missing required node field is contract drift, never a default") {
    List("uuid", "name", "address", "isConnected", "isConnecting", "isDisabled", "isTrafficTrackingActive",
      "countryCode", "tags", "configProfile", "xrayUptime", "usersOnline").foreach { field =>
      assert(invalid(NodesPath, listing(without(frankfurt, field))), field)
    }
    // A nullable field may be null, but it must be there.
    List("port", "lastStatusChange", "trafficLimitBytes", "trafficUsedBytes", "providerUuid", "provider", "versions",
      "system").foreach(field => assert(invalid(NodesPath, listing(without(frankfurt, field))), field))
    // The wrong type is as invalid as a missing field.
    List("isConnected" -> Json.fromString("true"), "isDisabled" -> Json.Null, "port" -> Json.fromString("2222"),
      "usersOnline" -> Json.fromInt(-1), "tags" -> Json.arr(Json.fromInt(1)),
      "versions" -> json("""{"xray":"25"}"""), "lastStatusChange" -> Json.fromString("yesterday"))
      .foreach { case (field, value) => assert(invalid(NodesPath, listing(replaced(frankfurt, field, value))), field) }
  }

  test("a missing required host or profile field is contract drift") {
    List("uuid", "remark", "address", "port", "isDisabled", "isHidden", "securityLayer", "inbound", "nodes", "tags",
      "serverDescription").foreach(field => assert(invalid(HostsPath, listing(without(host, field))), field))
    List("uuid", "name", "viewPosition", "createdAt", "updatedAt", "nodes", "inbounds")
      .foreach(field => assert(invalid(ProfilesPath, profiles(without(profile, field))), field))
    val inbound = profile.hcursor.downField("inbounds").downN(0).focus.get
    List("uuid", "tag", "type", "network", "security", "port").foreach { field =>
      assert(invalid(ProfilesPath, profiles(replaced(profile, "inbounds", Json.arr(without(inbound, field))))), field)
    }
  }

  test("identities and references must be canonical UUIDs; case is normalized") {
    List("definitely-not-a-uuid", "1-1-1-1-1", "", "11111111111111111111111111111111").foreach { value =>
      assert(invalid(NodesPath, listing(replaced(frankfurt, "uuid", Json.fromString(value)))), value)
    }
    assert(invalid(HostsPath, listing(replaced(host, "nodes", Json.arr(Json.fromString("node-a"))))))
    assert(invalid(NodesPath, listing(replaced(frankfurt, "providerUuid", Json.fromString("hetzner")))))
    val upper = observe(healthy + (NodesPath -> ok(listing(replaced(frankfurt, "uuid", Json.fromString(nodeA.toUpperCase)))))
    )._1.toOption.get
    assertEquals(upper.objects.filter(_.objectType == IntegrationObjectType.Node).map(_.externalId), List(nodeA))
  }

  test("related lists are kept whole, and one bad item rejects the listing instead of being dropped") {
    val many = (1 to 500).map(n => f"00000000-0000-0000-0000-$n%012d").toList
    val wide = replaced(host, "nodes", Json.fromValues(many.map(Json.fromString)))
    val observation = observe(healthy + (HostsPath -> ok(listing(wide))))._1.toOption.get
    val nodes = observation.objects.collectFirst { case ObservedIntegrationObject(_, _, _, s: RemnawaveHostSummary) => s.nodeUuids }
    assertEquals(nodes, Some(many))
    assert(invalid(HostsPath, listing(replaced(host, "nodes", Json.fromValues((many :+ "bad").map(Json.fromString))))))
  }

  test("empty listings are a valid, complete, empty snapshot; a wrong envelope is not") {
    val empty = Map(NodesPath -> ok(listing()), HostsPath -> ok(listing()), ProfilesPath -> ok(profiles()))
    val observation = observe(empty)._1.toOption.get
    assertEquals(observation.objects, Nil)
    assertEquals(observation.completeObjectTypes, IntegrationObjectType.All.toSet)
    List("not json", """{"nodes":[]}""", """{"response":{}}""", """{"response":{"nodes":[]}}""")
      .foreach(body => assert(invalid(NodesPath, body), body))
    assert(invalid(ProfilesPath, listing(profile)), "profiles are wrapped in configProfiles")
  }

  test("any failing endpoint fails the snapshot with its sanitized code") {
    List(Status.Unauthorized -> "INTEGRATION_AUTH_FAILED", Status.Forbidden -> "INTEGRATION_FORBIDDEN",
      Status.NotFound -> "INTEGRATION_ENDPOINT_NOT_FOUND", Status.TooManyRequests -> "INTEGRATION_RATE_LIMITED",
      Status.BadGateway -> "INTEGRATION_REMOTE_UNAVAILABLE").foreach { case (status, code) =>
      val (result, _) = observe(healthy + (HostsPath -> IO.pure(Response[IO](status).withEntity(s"echo $token $proxyUrl"))))
      assertEquals(codeOf(result), Some(code), status.toString)
      assert(!result.left.toOption.get.getMessage.contains(token))
    }
    val (timedOut, _) = observe(healthy + (ProfilesPath -> IO.never[Response[IO]]), timeout = 50.millis)
    assertEquals(codeOf(timedOut), Some("INTEGRATION_TIMEOUT"))
  }

  test("a listing over the byte limit or a snapshot over the object limit is an invalid response") {
    assertEquals(codeOf(observe(healthy, maxBytes = 512)._1), Some("INTEGRATION_INVALID_RESPONSE"))
    assertEquals(codeOf(observe(healthy, maxObjects = 3)._1), Some("INTEGRATION_INVALID_RESPONSE"))
    assert(observe(healthy, maxObjects = 4)._1.isRight)
    val invalidUtf8 = IO.pure(Response[IO](Status.Ok).withEntity(Array[Byte]('{', 0xC3.toByte, 0x28, '}')))
    assertEquals(codeOf(observe(healthy + (NodesPath -> invalidUtf8))._1), Some("INTEGRATION_INVALID_RESPONSE"))
  }

}
