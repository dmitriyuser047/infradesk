package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationError, IntegrationRuntimeContext}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.configuration.CanonicalJson
import domain.integration._
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import org.http4s.{HttpApp, Method, Request, Response, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import java.util.UUID
import scala.concurrent.duration._

final class RemnawaveNodeProvisioningSpec extends FunSuite {
  private val base = IntegrationBaseUrl.parse("https://panel.example.test/prefix").toOption.get
  private val auth = RemnawaveCredential("private-api-token", Some("private-caddy-key"))
  private val context = IntegrationRuntimeContext(UUID.randomUUID(), UUID.randomUUID(), base, auth)
  private val intent = NodeCreateIntent("Finland-03", "fi03.example.test", 2222,
    UUID.randomUUID(), List(UUID.randomUUID()), UUID.randomUUID())
  private val nodeId = UUID.randomUUID()
  private val registrationSecret = java.util.Base64.getEncoder.encodeToString(Json.obj(
    "nodeCertPem" -> Json.fromString("node-certificate"), "nodeKeyPem" -> Json.fromString("private-registration-key"),
    "caCertPem" -> Json.fromString("ca-certificate"), "jwtPublicKey" -> Json.fromString("jwt-public-key"))
    .noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  private def node(value: NodeCreateIntent = intent, id: UUID = nodeId): Json = Json.obj(
    "uuid" -> Json.fromString(id.toString), "name" -> Json.fromString(value.name),
    "address" -> Json.fromString(value.address), "port" -> Json.fromInt(value.port),
    "isConnected" -> Json.True, "isConnecting" -> Json.False, "isDisabled" -> Json.False,
    "configProfile" -> Json.obj("activeConfigProfileUuid" -> Json.fromString(value.configProfileId.toString),
      "activeInbounds" -> Json.arr(value.activeInboundIds.map(id => Json.obj("uuid" -> Json.fromString(id.toString))): _*)),
    "tags" -> Json.arr(Json.fromString(RemnawaveNodeApi.correlationTag(value.correlationId))))

  private def metadata(version: String, commit: Option[String] = None): String = Json.obj("response" -> Json.obj(
    "version" -> Json.fromString(version), "git" -> Json.obj("backend" -> Json.obj(
      "commitSha" -> Json.fromString(commit.getOrElse(RemnawaveNodeApi.releases.get(version).map(_.commit)
        .getOrElse("0" * 40))))))).noSpaces
  private def response(body: Json): Response[IO] = Response[IO](Status.Ok).withEntity(body.noSpaces)
  private def listing(items: List[Json]): Response[IO] = response(Json.obj("response" -> Json.arr(items: _*)))
  private def envelope(item: Json): Response[IO] = response(Json.obj("response" -> item))

  private def setup(version: String = "3.4.4", items: List[Json] = Nil,
    write: Request[IO] => IO[Response[IO]] = _ => IO.pure(envelope(node())),
    keyField: String = "secretKey", metadataBody: Option[String] = None):
      (RemnawaveNodeProvisioning, RemnawaveProvider, Ref[IO, List[(String, String, String)]]) = {
    val seen = Ref.of[IO, List[(String, String, String)]](Nil).unsafeRunSync()
    val app = HttpApp[IO] { request =>
      request.as[String].flatMap { body =>
        require(request.headers.get(CIString("Authorization")).exists(_.head.value == s"Bearer ${auth.apiToken}"),
          "The adapter did not send the expected Authorization header")
        require(request.headers.get(CIString("X-Api-Key")).exists(_.head.value == auth.caddyApiKey.get),
          "The adapter did not send the expected Caddy header")
        val path = request.uri.path.renderString
        seen.update(_ :+ (request.method.name, path, body)) *> (path match {
          case "/prefix/api/system/metadata" => IO.pure(Response[IO](Status.Ok).withEntity(metadataBody.getOrElse(metadata(version))))
          case "/prefix/api/system/stats" => IO.pure(Response[IO](Status.Ok).withEntity("{\"response\":{\"uptime\":1,\"users\":{\"totalUsers\":0}}}"))
          case "/prefix/api/nodes" if request.method == Method.GET => IO.pure(listing(items))
          case "/prefix/api/hosts" => IO.pure(listing(Nil))
          case "/prefix/api/config-profiles" if request.method == Method.GET =>
            IO.pure(envelope(Json.obj("configProfiles" -> Json.arr())))
          case path if path == s"/prefix/api/nodes/$nodeId" && request.method == Method.GET => IO.pure(envelope(node()))
          case "/prefix/api/keygen" => IO.pure(envelope(Json.obj(keyField -> Json.fromString(registrationSecret))))
          case _ => write(request)
        })
      }
    }
    val client = new RemnawaveClient(Client.fromHttpApp(app), 2.seconds)
    (new RemnawaveNodeProvisioning(client), new RemnawaveProvider(client), seen)
  }

  test("reviewed released patches share generation adapters and never claim create idempotency") {
    assertEquals(RemnawaveNodeApi.releases.size, 16)
    RemnawaveNodeApi.releases.values.foreach { release =>
      val (transport, _, _) = setup(release.version)
      val actual = transport.inspect(context).unsafeRunSync()
      assert(actual.provisioningReady, release.version)
      assertEquals(actual.apiGeneration, Some(release.adapter.code))
      assert(!actual.capabilities(NodeProvisioningCapability.CreateIdempotency))
      assert(actual.capabilities(NodeProvisioningCapability.CreateReconciliation))
    }
    assertEquals(RemnawaveNodeApi.releases("3.0.0").adapter, RemnawaveNodeApi.releases("3.4.4").adapter)
    assertEquals(RemnawaveNodeApi.releases("2.8.0").adapter, RemnawaveNodeApi.releases("2.8.1").adapter)
  }

  test("each supported generation sends the same typed create DTO exactly once") {
    List("2.8.0", "2.8.1", "3.0.0", "3.4.4").foreach { version =>
      val (transport, _, seen) = setup(version)
      val reviewed = transport.inspect(context).unsafeRunSync()
      val result = transport.createNode(context, intent, reviewed).unsafeRunSync()
      assertEquals(result, NodeCreateOutcome.Created(RemnawaveNodeApi.node(node().hcursor).get))
      val calls = seen.get.unsafeRunSync().filter(_._1 == "POST")
      assertEquals(calls.size, 1)
      assertEquals(calls.head._2, "/prefix/api/nodes")
      assertEquals(parse(calls.head._3).toOption.get, RemnawaveNodeApi.createPayload(intent))
      val tags = parse(calls.head._3).toOption.get.hcursor.get[List[String]]("tags").toOption.get
      assert(tags.forall(_.matches("[A-Z0-9_:]{1,36}")), "Correlation tags must satisfy the upstream create schema")
      assert(!calls.head._3.contains(auth.apiToken))
    }
  }

  test("unknown versions, future patches, prereleases, custom builds and malformed metadata fail closed") {
    List("3.4.5", "3.5.0", "3.5-custom", "4.0.0", "3.4.4-beta.1", "2.7.4").foreach { version =>
      val (transport, _, seen) = setup(version, List(node()))
      val api = transport.inspect(context).unsafeRunSync()
      assert(!api.provisioningReady)
      assertEquals(api.capabilities, Set[NodeProvisioningCapability](
        NodeProvisioningCapability.Inventory, NodeProvisioningCapability.Status))
      assertEquals(transport.createNode(context, intent, api).unsafeRunSync(),
        NodeCreateOutcome.Rejected("INTEGRATION_API_CONTRACT_UNCONFIRMED"))
      assertEquals(transport.installationData(context, api).attempt.unsafeRunSync().left.toOption
        .collect { case e: IntegrationError => e.code }, Some("INTEGRATION_API_CONTRACT_UNCONFIRMED"))
      assertEquals(transport.reconcileCreate(context, intent, api).attempt.unsafeRunSync().left.toOption
        .collect { case e: IntegrationError => e.code }, Some("INTEGRATION_API_CONTRACT_UNCONFIRMED"))
      assertEquals(transport.getNode(context, nodeId).unsafeRunSync().externalId, nodeId)
      assert(!seen.get.unsafeRunSync().exists(c => c._1 != "GET" || c._2.endsWith("/keygen")))
    }
    List(metadata("3.4.4", Some("f" * 40)), "{}", "not json").foreach { raw =>
      val (transport, _, seen) = setup(metadataBody = Some(raw))
      val api = transport.inspect(context).unsafeRunSync()
      assert(!api.provisioningReady)
      transport.createNode(context, intent, api).unsafeRunSync()
      assert(!seen.get.unsafeRunSync().exists(_._1 == "POST"))
    }
  }

  test("unconfirmed provisioning does not block sync with a valid Stage 24 inventory contract") {
    val inventoryNode = node().deepMerge(Json.obj(
      "lastStatusChange" -> Json.Null, "isTrafficTrackingActive" -> Json.False,
      "trafficLimitBytes" -> Json.Null, "trafficUsedBytes" -> Json.Null,
      "countryCode" -> Json.fromString("FI"), "providerUuid" -> Json.Null,
      "provider" -> Json.Null, "versions" -> Json.Null, "system" -> Json.Null,
      "xrayUptime" -> Json.fromInt(0), "usersOnline" -> Json.fromInt(0)))
    val (transport, provider, seen) = setup("3.5-custom", List(inventoryNode))
    assert(!transport.inspect(context).unsafeRunSync().provisioningReady)
    seen.set(Nil).unsafeRunSync()
    val inventory = provider.observe(context).unsafeRunSync()
    assertEquals(inventory.objects.map(_.externalId), List(nodeId.toString))
    assertEquals(inventory.completeObjectTypes, IntegrationObjectType.All.toSet)
    assertEquals(seen.get.unsafeRunSync().map(c => (c._1, c._2)).toSet, Set(
      ("GET", "/prefix/api/nodes"), ("GET", "/prefix/api/hosts"), ("GET", "/prefix/api/config-profiles")))
  }

  private val unconfirmedMetadata = List(metadata("3.5-custom"), metadata("3.4.4", Some("f" * 40)), "{}", "not json")

  test("provisioning blocked but enable, disable and restart still use their own validated action contract") {
    unconfirmedMetadata.foreach { raw =>
      val (transport, provider, seen) = setup(metadataBody = Some(raw), write = request =>
        IO.pure(if (request.uri.path.renderString.endsWith("/restart")) Response[IO](Status.Accepted)
          else envelope(Json.obj("uuid" -> Json.fromString(nodeId.toString)))))
      val api = transport.inspect(context).unsafeRunSync()
      assert(!api.provisioningReady)
      assertEquals(transport.createNode(context, intent, api).unsafeRunSync(),
        NodeCreateOutcome.Rejected("INTEGRATION_API_CONTRACT_UNCONFIRMED"))
      seen.set(Nil).unsafeRunSync()
      List(IntegrationActionCode.NodeEnable, IntegrationActionCode.NodeDisable,
        IntegrationActionCode.NodeRestart).foreach { action =>
        assertEquals(provider.executeAction(context, nodeId.toString, action).unsafeRunSync(),
          IntegrationActionRemoteOutcome.Succeeded)
      }
      assertEquals(seen.get.unsafeRunSync(), List(
        ("POST", s"/prefix/api/nodes/$nodeId/actions/enable", ""),
        ("POST", s"/prefix/api/nodes/$nodeId/actions/disable", ""),
        ("POST", s"/prefix/api/nodes/$nodeId/actions/restart", "{\"forceRestart\":false}")))
    }
  }

  test("provisioning blocked but config deployment still validates its independent PATCH contract") {
    val profileId = intent.configProfileId.toString
    val config = Json.obj("inbounds" -> Json.arr())
    unconfirmedMetadata.foreach { raw =>
      val (transport, provider, seen) = setup(metadataBody = Some(raw), write = _ => IO.pure(envelope(Json.obj(
        "uuid" -> Json.fromString(profileId), "config" -> config,
        "updatedAt" -> Json.fromString("2026-10-03T10:00:00Z")))))
      val api = transport.inspect(context).unsafeRunSync()
      assert(!api.provisioningReady)
      assertEquals(transport.createNode(context, intent, api).unsafeRunSync(),
        NodeCreateOutcome.Rejected("INTEGRATION_API_CONTRACT_UNCONFIRMED"))
      seen.set(Nil).unsafeRunSync()
      assertEquals(provider.updateConfigProfile(context, profileId, config, CanonicalJson.sha256(config)).unsafeRunSync(),
        IntegrationActionRemoteOutcome.Succeeded)
      assertEquals(seen.get.unsafeRunSync(), List(("PATCH", "/prefix/api/config-profiles",
        Json.obj("uuid" -> Json.fromString(profileId), "config" -> config).noSpaces)))
    }
  }

  test("registration data uses only the generation's declared field and is redacted") {
    List("2.8.1" -> "pubKey", "3.4.4" -> "secretKey").foreach { case (version, field) =>
      val (transport, _, _) = setup(version, keyField = field)
      val reviewed = transport.inspect(context).unsafeRunSync()
      val result = transport.installationData(context, reviewed).unsafeRunSync()
      assertEquals(result.secretKey, registrationSecret)
      assert(!result.toString.contains(result.secretKey))
      val (wrong, _, _) = setup(version, keyField = if (field == "pubKey") "secretKey" else "pubKey")
      assert(wrong.installationData(context, reviewed).attempt.unsafeRunSync().isLeft)
    }
    assertEquals(RemnawaveNodeApi.ProfileSecretKey.installationData(
      "{\"response\":{\"secretKey\":\"not-a-certificate-payload\"}}"), None)
  }

  test("a server upgrade after preview prevents create and keygen before the next approval") {
    val (old, _, _) = setup("2.8.1")
    val reviewed = old.inspect(context).unsafeRunSync()
    val (fresh, _, seen) = setup("3.4.4")
    assertEquals(fresh.createNode(context, intent, reviewed).unsafeRunSync(),
      NodeCreateOutcome.Rejected("INTEGRATION_API_CONTRACT_CHANGED"))
    assert(fresh.installationData(context, reviewed).attempt.unsafeRunSync().isLeft)
    assert(!seen.get.unsafeRunSync().exists(c => c._1 == "POST" || c._2.endsWith("/keygen")))
  }

  test("ambiguous creation is unknown; restart reconciles by reviewed intent and never repeats POST") {
    val posts = Ref.of[IO, Int](0).unsafeRunSync()
    val (transport, _, _) = setup(write = _ => posts.update(_ + 1) *> IO.never[Response[IO]])
    val reviewed = transport.inspect(context).unsafeRunSync()
    assertEquals(transport.createNode(context, intent, reviewed).unsafeRunSync(),
      NodeCreateOutcome.Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN"))
    val (restarted, _, seen) = setup(items = List(node()), write = _ => posts.update(_ + 1).as(envelope(node())))
    assertEquals(restarted.reconcileCreate(context, intent, reviewed).unsafeRunSync(),
      NodeCreateReconciliation.Confirmed(RemnawaveNodeApi.node(node().hcursor).get))
    assertEquals(restarted.createNode(context, intent, reviewed).unsafeRunSync(),
      NodeCreateOutcome.Rejected("INTEGRATION_NODE_CONFLICT"))
    assertEquals(posts.get.unsafeRunSync(), 1)
    assert(!seen.get.unsafeRunSync().exists(_._1 == "POST"))
    val (absent, _, absentCalls) = setup()
    assertEquals(absent.reconcileCreate(context, intent, reviewed).unsafeRunSync(), NodeCreateReconciliation.NotProven)
    assert(!absentCalls.get.unsafeRunSync().exists(_._1 == "POST"))
  }

  test("same display name is insufficient to prove correlation; mismatching fields and duplicates fail") {
    val reviewed = setup()._1.inspect(context).unsafeRunSync()
    List(node(intent.copy(correlationId = UUID.randomUUID())), node(intent.copy(port = 3333)),
      node(intent.copy(configProfileId = UUID.randomUUID()))).foreach { wrong =>
      assertEquals(setup(items = List(wrong))._1.reconcileCreate(context, intent, reviewed).unsafeRunSync(),
        NodeCreateReconciliation.NotProven)
    }
    assert(setup(items = List(node(), node()))._1.findNodes(context).attempt.unsafeRunSync().isLeft)
    assert(setup(items = List(node().mapObject(_.remove("port"))))._1.inspect(context).unsafeRunSync().blocker.nonEmpty)
  }

  test("HTTP errors distinguish rejection from uncertain mutation and never expose response bodies") {
    val reviewed = setup()._1.inspect(context).unsafeRunSync()
    List(Status.BadRequest -> NodeCreateOutcome.Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN"),
      Status.NotFound -> NodeCreateOutcome.Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN"),
      Status.Unauthorized -> NodeCreateOutcome.Rejected("INTEGRATION_AUTH_FAILED"),
      Status.Conflict -> NodeCreateOutcome.Rejected("INTEGRATION_NODE_CONFLICT"),
      Status.ServiceUnavailable -> NodeCreateOutcome.Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN"),
      Status.Ok -> NodeCreateOutcome.Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN")).foreach { case (status, expected) =>
      val (transport, _, _) = setup(write = _ => IO.pure(Response[IO](status).withEntity("private-registration-data")))
      assertEquals(transport.createNode(context, intent, reviewed).unsafeRunSync(), expected)
    }
    val (duplicate, _, _) = setup(write = _ => IO.pure(Response[IO](Status.BadRequest)
      .withEntity("{\"errorCode\":\"A033\",\"message\":\"private-detail\"}")))
    assertEquals(duplicate.createNode(context, intent, reviewed).unsafeRunSync(),
      NodeCreateOutcome.Rejected("INTEGRATION_NODE_CONFLICT"))
    val (partial, _, _) = setup(write = _ => IO.pure(Response[IO](Status.NotFound)
      .withEntity("{\"errorCode\":\"A124\",\"message\":\"private-detail\"}")))
    assertEquals(partial.createNode(context, intent, reviewed).unsafeRunSync(),
      NodeCreateOutcome.Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN"))
  }

  test("invalid typed input never sends a mutation") {
    val (transport, _, seen) = setup()
    val reviewed = transport.inspect(context).unsafeRunSync()
    List(intent.copy(name = " x "), intent.copy(name = "x" * 31), intent.copy(port = 0), intent.copy(port = 65536),
      intent.copy(address = "https://node.test"), intent.copy(address = "user@node.test"),
      intent.copy(address = "node.test/path"), intent.copy(address = "node.test?query"),
      intent.copy(activeInboundIds = List(intent.activeInboundIds.head, intent.activeInboundIds.head))).foreach { invalid =>
      assertEquals(transport.createNode(context, invalid, reviewed).unsafeRunSync(),
        NodeCreateOutcome.Rejected("INTEGRATION_NODE_INVALID_REQUEST"))
    }
    assert(!seen.get.unsafeRunSync().exists(_._1 == "POST"))
  }

  test("connectivity reports compatibility separately from successful read-only probing") {
    val (_, provider, _) = setup("3.4.5")
    val result = provider.testConnection(context).unsafeRunSync()
    assert(result.ok)
    assert(!result.nodeApi.get.provisioningReady)
    assert(provider.nodeProvisioning.nonEmpty)
  }
}
