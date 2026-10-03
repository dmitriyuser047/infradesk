package ru.bitec.app.ops
package infrastructure.http

import application.auth.ActorContext
import application.integration.RemnawaveOnboardingApi
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.integration.{OnboardingInput,RemnawaveNodeOnboardingRun}
import io.circe.Json
import munit.FunSuite
import org.http4s.{Method,Request,Status,Uri}
import org.typelevel.log4cats.noop.NoOpLogger
import support.AuthorizationFixtures
import java.util.UUID

final class RemnawaveOnboardingRoutesSpec extends FunSuite {
  private val org = UUID.randomUUID()
  private val integration = UUID.randomUUID()
  private val root = s"/api/v1/organizations/$org/integrations/$integration/remnawave-node-onboarding"
  private final class Api extends RemnawaveOnboardingApi {
    var calls = 0
    var received: Option[OnboardingInput] = None
    def options(o: UUID,i: UUID) = IO { calls += 1; Json.obj() }
    def preview(a: ActorContext,i: UUID,input: OnboardingInput) = IO { calls += 1; received=Some(input); Json.obj() }
    def start(a: ActorContext,i: UUID,p: UUID,r: UUID): IO[RemnawaveNodeOnboardingRun] = IO.raiseError(new AssertionError("Invalid start reached service"))
    def detail(o: UUID,i: UUID,r: UUID) = IO { calls += 1; Json.obj() }
    def history(o: UUID,i: UUID) = IO { calls += 1; Json.obj() }
  }
  private val input = Json.obj("resourceId" -> Json.fromString(UUID.randomUUID().toString),
    "nodeName" -> Json.fromString("edge-01"),"address" -> Json.fromString("192.0.2.1"),"nodePort" -> Json.fromInt(2222),
    "configProfileId" -> Json.fromString(UUID.randomUUID().toString),
    "activeInboundIds" -> Json.arr(Json.fromString(UUID.randomUUID().toString)),
    "panelCidrs" -> Json.arr(Json.fromString("198.51.100.0/24")),"desiredState" -> Json.fromString("ENABLED"))
  private def response(api: Api,path: String,body: String,role: OrganizationRole = OrganizationRole.Owner) = {
    val route = new RemnawaveOnboardingRoutes[IO](api,AuthorizationFixtures.authorization,NoOpLogger[IO]).routes.orNotFound
    route.run(AuthorizationFixtures.as(Request[IO](Method.POST,Uri.unsafeFromString(root+path)).withEntity(body),org,role)).unsafeRunSync()
  }
  test("preview accepts only the closed typed input and forwards approved basic fields") {
    val api=new Api
    assertEquals(response(api,"/preview",input.noSpaces).status,Status.Ok)
    assertEquals(api.received.map(_.nodePort),Some(2222))
    assertEquals(api.received.map(_.desiredState),Some("ENABLED"))
  }
  test("strict body rejects extra keys, missing keys, invalid UUIDs, oversized lists and body") {
    val invalid = List(input.mapObject(_.add("shell",Json.fromString("arbitrary"))),
      input.mapObject(_.remove("panelCidrs")), input.mapObject(_.add("resourceId",Json.fromString("bad"))),
      input.mapObject(_.add("activeInboundIds",Json.arr(List.fill(257)(Json.fromString(UUID.randomUUID().toString)): _*))),
      input.mapObject(_.add("nodeName",Json.fromString("x"*17000))))
    invalid.foreach { body =>
      val api=new Api
      assertEquals(response(api,"/preview",body.noSpaces).status,Status.BadRequest)
      assertEquals(api.calls,0)
    }
    assertEquals(response(new Api,"/preview","{broken").status,Status.BadRequest)
    assertEquals(response(new Api,"/runs","{\"planId\":\"bad\",\"requestId\":\"bad\"}").status,Status.BadRequest)
  }
  test("preview requires all mutation permissions before body parsing or service access") {
    List(OrganizationRole.Member).foreach { role =>
      val api=new Api
      assertEquals(response(api,"/preview",input.noSpaces,role).status,Status.Forbidden)
      assertEquals(api.calls,0)
    }
  }
  test("history and detail require organization access and preserve safe read permissions") {
    val api=new Api
    val route=new RemnawaveOnboardingRoutes[IO](api,AuthorizationFixtures.authorization,NoOpLogger[IO]).routes.orNotFound
    val req=Request[IO](Method.GET,Uri.unsafeFromString(root+"/runs"))
    assertEquals(route.run(AuthorizationFixtures.as(req,org,OrganizationRole.Member)).unsafeRunSync().status,Status.Ok)
    assertEquals(route.run(req).unsafeRunSync().status,Status.InternalServerError)
  }
}
