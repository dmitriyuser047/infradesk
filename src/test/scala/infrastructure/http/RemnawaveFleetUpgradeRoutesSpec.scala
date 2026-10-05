package ru.bitec.app.ops
package infrastructure.http

import application.auth.ActorContext
import application.integration._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.integration._
import io.circe.Json
import java.util.UUID
import munit.FunSuite
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.log4cats.noop.NoOpLogger
import support.{AuthorizationFixtures, NodeUpgradeFixtures}

final class RemnawaveFleetUpgradeRoutesSpec extends FunSuite {
  private val org = UUID.randomUUID()
  private val integration = UUID.randomUUID()
  private val fleet = UUID.randomUUID()
  private val root = s"/api/v1/organizations/$org/integrations/$integration/remnawave-fleets/$fleet"
  private class Api extends RemnawaveFleetUpgradeApi {
    var calls = List.empty[String]
    var received: Option[NodeUpgradePreviewInput] = None
    private val run = NodeUpgradeFixtures.run()
    def status(o: UUID, i: UUID, f: UUID) = IO { calls :+= "status"; Json.obj() }
    def select(a: ActorContext, i: UUID, f: UUID, id: String) = IO {
      calls :+= "select"
      FleetNodeReleaseRevision(UUID.randomUUID(), a.organizationId, i, f, 1, NodeUpgradeFixtures.target, NodeUpgradeFixtures.panel, a.userId, java.time.Instant.now())
    }
    def preview(a: ActorContext, i: UUID, f: UUID, input: NodeUpgradePreviewInput) = IO { calls :+= "preview"; received = Some(input); NodeUpgradePreview("BLOCKED", Nil, None) }
    def start(a: ActorContext, i: UUID, f: UUID, p: UUID, r: UUID) = IO { calls :+= "start"; run }
    def history(o: UUID, i: UUID, f: UUID) = IO { calls :+= "history"; List(run) }
    def detail(o: UUID, i: UUID, f: UUID, id: UUID) = IO { calls :+= "detail"; NodeUpgradeDetail(run, Nil, Nil) }
    def control(a: ActorContext, i: UUID, f: UUID, id: UUID, command: String, scope: FleetRollbackScope) = IO { calls :+= command; run }
  }
  private def response(api: Api, path: String, body: String, role: OrganizationRole = OrganizationRole.Owner, method: Method = Method.POST) = {
    val route = new RemnawaveFleetUpgradeRoutes[IO](api, AuthorizationFixtures.authorization, NoOpLogger[IO]).routes.orNotFound
    route.run(AuthorizationFixtures.as(Request[IO](method, Uri.unsafeFromString(root + path)).withEntity(body), org, role)).unsafeRunSync()
  }
  private val preview = Json.obj("releaseRevisionId" -> Json.fromString(UUID.randomUUID().toString), "canaryMemberIds" -> Json.arr(),
    "waveSize" -> Json.fromInt(1), "automaticRollback" -> Json.True, "pauseAfterCanary" -> Json.True)
  test("closed preview accepts only release revision and typed policy") {
    val api = new Api
    assertEquals(response(api, "/node-upgrades/preview", preview.noSpaces).status, Status.Ok)
    assertEquals(api.received.map(_.waveSize), Some(1))
    assertEquals(api.calls, List("preview"))
  }
  test("closed DTO rejects shell, image, registry, missing keys, invalid UUID, invalid wave and oversized input before service") {
    val invalid = List(preview.mapObject(_.add("image", Json.fromString("evil:latest"))), preview.mapObject(_.add("shell", Json.fromString("anything"))),
      preview.mapObject(_.remove("pauseAfterCanary")), preview.mapObject(_.add("waveSize", Json.fromInt(0))),
      preview.mapObject(_.add("releaseRevisionId", Json.fromString("bad"))), preview.mapObject(_.add("canaryMemberIds", Json.arr(List.fill(501)(Json.fromString(UUID.randomUUID().toString)): _*))))
    invalid.foreach { body => val api = new Api; assertEquals(response(api, "/node-upgrades/preview", body.noSpaces).status, Status.BadRequest); assert(api.calls.isEmpty) }
    val api = new Api
    assertEquals(response(api, "/node-release-target", "{\"releaseId\":\"node-3.4.1\",\"registry\":\"evil\"}").status, Status.BadRequest)
    assertEquals(response(api, "/node-upgrades", "{\"planId\":\"bad\",\"requestId\":\"bad\"}").status, Status.BadRequest)
    assertEquals(response(api, "/node-upgrades/preview", "x" * 8193).status, Status.BadRequest)
    assert(api.calls.isEmpty)
  }
  test("selection is metadata only and member cannot start or control any upgrade") {
    val api = new Api
    assertEquals(response(api, "/node-release-target", "{\"releaseId\":\"node-3.4.1\"}").status, Status.Ok)
    assertEquals(api.calls, List("select"))
    List("/node-release-target", "/node-upgrades/preview", "/node-upgrades", s"/node-upgrades/${UUID.randomUUID()}/pause", s"/node-upgrades/${UUID.randomUUID()}/resume", s"/node-upgrades/${UUID.randomUUID()}/rollback").foreach { path =>
      assertEquals(response(api, path, "{}", OrganizationRole.Member).status, Status.Forbidden)
    }
    assertEquals(api.calls, List("select"))
  }
  test("read-only members can inspect catalog, history and detail") {
    val api = new Api
    List("/node-releases", "/node-upgrades", s"/node-upgrades/${UUID.randomUUID()}").foreach { path =>
      assertEquals(response(api, path, "", OrganizationRole.Member, Method.GET).status, Status.Ok)
    }
    assertEquals(api.calls, List("status", "history", "detail"))
  }
}
