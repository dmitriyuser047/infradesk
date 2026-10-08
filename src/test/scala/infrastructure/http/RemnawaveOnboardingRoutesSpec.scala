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
    var confirmed: Option[Boolean] = None
    override def importCertificate(a: ActorContext,i: UUID,r: UUID,d: String,m: domain.integration.NodeTlsMaterial) = IO {
      calls+=1; Json.obj("id"->Json.fromString(UUID.randomUUID().toString),"domain"->Json.fromString(d))
    }
    def options(o: UUID,i: UUID) = IO { calls += 1; Json.obj() }
    def preview(a: ActorContext,i: UUID,input: OnboardingInput) = IO { calls += 1; received=Some(input); Json.obj() }
    def reconcile(a: ActorContext,i: UUID,r: UUID,action: String) = IO { calls += 1; Json.obj() }
    def start(a: ActorContext,i: UUID,p: UUID,r: UUID,confirmRecreate: Boolean): IO[RemnawaveNodeOnboardingRun] = IO {
      calls += 1; confirmed=Some(confirmRecreate)
      throw application.integration.IntegrationError("REMNAWAVE_ONBOARDING_RECREATE_CONFIRMATION_REQUIRED","Explicit approval is required")
    }
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
    assertEquals(api.received.map(_.nodeAddressMode),Some(domain.integration.NodeAddressMode.PublicIp))
  }
  test("generated protocol and HTTP-01 decode closed intent; imports are permission protected and never echo material") {
    val protocol=Json.obj("version"->Json.fromInt(1),"kind"->Json.fromString("HYSTERIA2"),"port"->Json.fromInt(443),"serverName"->Json.fromString("example.org"))
    val request=Json.obj("certificateId"->Json.fromString(UUID.randomUUID().toString),"email"->Json.fromString("operator@example.org"),"agreeTerms"->Json.True)
    val body=input.mapObject(_.remove("configProfileId").remove("activeInboundIds").add("protocol",protocol).add("tlsHttp01",request))
    val api=new Api
    assertEquals(response(api,"/preview",body.noSpaces).status,Status.Ok)
    assertEquals(api.received.map(_.configProfileId),Some(OnboardingInput.GeneratedProfileId))
    assertEquals(api.received.flatMap(_.tlsHttp01).map(_.email),Some("operator@example.org"))
    for(bad <- List(request.mapObject(_.add("agreeTerms",Json.False)),request.mapObject(_.add("privateKey",Json.fromString("secret"))))) {
      val rejected=new Api
      assertEquals(response(rejected,"/preview",body.mapObject(_.add("tlsHttp01",bad)).noSpaces).status,Status.BadRequest)
      assertEquals(rejected.calls,0)
    }
    val certificate=Json.obj("resourceId"->input.hcursor.downField("resourceId").focus.get,"domain"->Json.fromString("example.org"),
      "certificatePem"->Json.fromString("CERTIFICATE-FIXTURE"),"privateKeyPem"->Json.fromString("PRIVATE-KEY-FIXTURE"))
    val imported=new Api
    val out=response(imported,"/certificates",certificate.noSpaces)
    assertEquals(out.status,Status.Created)
    assert(!out.as[String].unsafeRunSync().contains("FIXTURE"))
    val forbidden=new Api
    assertEquals(response(forbidden,"/certificates",certificate.noSpaces,OrganizationRole.Member).status,Status.Forbidden)
    assertEquals(forbidden.calls,0)
  }
  test("a domain address requires an explicit mode; public IP mode permits an empty suggestion and rejects legacy bypass") {
    val domainBody=input.mapObject(_.add("address",Json.fromString("node.example.test")).add("nodeAddressMode",Json.fromString("DOMAIN")))
    val api=new Api
    assertEquals(response(api,"/preview",domainBody.noSpaces).status,Status.Ok)
    assertEquals(api.received.map(_.nodeAddressMode),Some(domain.integration.NodeAddressMode.Domain))
    assertEquals(response(new Api,"/preview",input.mapObject(_.add("address",Json.fromString(""))).noSpaces).status,Status.Ok)
    assertEquals(response(new Api,"/preview",input.mapObject(_.add("nodeAddressMode",Json.fromString("LEGACY"))).noSpaces).status,Status.BadRequest)
  }
  test("AUTO is the default without CIDRs; browser forwarding headers cannot supply Panel source") {
    val api=new Api
    val body=input.mapObject(_.remove("panelCidrs"))
    val route=new RemnawaveOnboardingRoutes[IO](api,AuthorizationFixtures.authorization,NoOpLogger[IO]).routes.orNotFound
    val request=AuthorizationFixtures.as(Request[IO](Method.POST,Uri.unsafeFromString(root+"/preview"))
      .putHeaders(_root_.org.http4s.Header.Raw(_root_.org.typelevel.ci.CIString("X-Forwarded-For"),"2.27.26.18"))
      .withEntity(body.noSpaces),org,OrganizationRole.Owner)
    assertEquals(route.run(request).unsafeRunSync().status,Status.Ok)
    assertEquals(api.received.map(_.panelSourceMode),Some(domain.integration.PanelSourceMode.Auto))
    assertEquals(api.received.map(_.panelCidrs),Some(Nil))
    val spoofed=body.mapObject(_.add("panelSourceMode",Json.fromString("AUTO")).add("panelCidrs",Json.arr(Json.fromString("2.27.26.18/32"))))
    assertEquals(response(new Api,"/preview",spoofed.noSpaces).status,Status.BadRequest)
  }
  test("strict body rejects extra keys, missing keys, invalid UUIDs, oversized lists and body") {
    val invalid = List(input.mapObject(_.add("shell",Json.fromString("arbitrary"))),
      input.mapObject(_.remove("address")), input.mapObject(_.add("resourceId",Json.fromString("bad"))),
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
  test("reconciliation is closed, permission protected, and separate from start") {
    val path=s"/runs/${UUID.randomUUID()}/reconcile"
    val api=new Api
    assertEquals(response(api,path,"{\"action\":\"RECOVER\"}").status,Status.Ok)
    assertEquals(api.calls,1)
    assertEquals(api.confirmed,None)
    List("{\"action\":\"RECREATE\"}","{\"action\":\"RECOVER\",\"confirm\":true}").foreach { body =>
      val invalid=new Api
      assertEquals(response(invalid,path,body).status,Status.BadRequest)
      assertEquals(invalid.calls,0)
    }
    assertEquals(response(new Api,path,"{\"action\":\"RECOVER\"}",OrganizationRole.Member).status,Status.Forbidden)
  }
  test("start forwards explicit recreation approval and rejects nonboolean confirmation") {
    val base=Json.obj("planId" -> Json.fromString(UUID.randomUUID().toString),"requestId" -> Json.fromString(UUID.randomUUID().toString))
    val without=new Api
    assertEquals(response(without,"/runs",base.noSpaces).status,Status.Conflict)
    assertEquals(without.confirmed,Some(false))
    val withApproval=new Api
    assertEquals(response(withApproval,"/runs",base.mapObject(_.add("confirmRecreate",Json.True)).noSpaces).status,Status.Conflict)
    assertEquals(withApproval.confirmed,Some(true))
    val malformed=new Api
    assertEquals(response(malformed,"/runs",base.mapObject(_.add("confirmRecreate",Json.fromString("true"))).noSpaces).status,Status.BadRequest)
    assertEquals(malformed.calls,0)
  }
}
