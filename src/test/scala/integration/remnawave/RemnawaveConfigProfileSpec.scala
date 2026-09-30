package ru.bitec.app.ops
package integration.remnawave

import application.integration.IntegrationError
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import domain.configuration.CanonicalJson
import domain.integration.{IntegrationActionRemoteOutcome, IntegrationBaseUrl, RemnawaveCredential}
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import org.http4s.{HttpApp, Request, Response, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import scala.concurrent.duration._

final class RemnawaveConfigProfileSpec extends FunSuite {
  private val id = "33333333-3333-3333-3333-333333333333"
  private val base = IntegrationBaseUrl.parse("https://panel.example.test/prefix").toOption.get
  private val credential = RemnawaveCredential("secret-token", Some("caddy-key"))
  private val config = parse("""{"b":2,"a":{"privateKey":"SUPER-SECRET-PRIVATE-KEY"}}""").toOption.get
  private val envelope = Json.obj("response" -> Json.obj("uuid" -> Json.fromString(id),
    "config" -> config, "updatedAt" -> Json.fromString("2026-09-30T10:00:00Z"))).noSpaces
  private val getPath = s"/prefix/api/config-profiles/$id"
  private val patchPath = "/prefix/api/config-profiles"
  private case class Seen(method: String, path: String, bearer: Boolean, caddy: Boolean, body: String)

  private def run(handler: Request[IO] => IO[Response[IO]]): (RemnawaveClient, Ref[IO, List[Seen]]) = {
    val seen = Ref.of[IO, List[Seen]](Nil).unsafeRunSync()
    val app = HttpApp[IO] { request => request.as[String].flatMap { body =>
      seen.update(_ :+ Seen(request.method.name, request.uri.path.renderString,
        request.headers.get(CIString("Authorization")).exists(_.head.value == "Bearer secret-token"),
        request.headers.get(CIString("X-Api-Key")).exists(_.head.value == "caddy-key"), body)) *> handler(request)
    } }
    (new RemnawaveClient(Client.fromHttpApp(app), 2.seconds), seen)
  }

  test("full GET and PATCH use the exact endpoint, credentials and config-only body") {
    val (client, seen) = run(request => IO.pure(Response[IO](Status.Ok).withEntity(envelope)))
    val document = client.fetchConfigProfile(base, credential, id).unsafeRunSync()
    val outcome = client.updateConfigProfile(base, credential, id, config, CanonicalJson.sha256(config)).unsafeRunSync()
    assertEquals(document.externalId, id)
    assertEquals(document.config, config)
    assertEquals(outcome, IntegrationActionRemoteOutcome.Succeeded)
    val calls = seen.get.unsafeRunSync()
    assertEquals(calls.map(c => (c.method, c.path)), List(("GET", getPath), ("PATCH", patchPath)))
    assert(calls.forall(c => c.bearer && c.caddy))
    val body = parse(calls.last.body).toOption.get
    assertEquals(body.hcursor.get[String]("uuid").toOption, Some(id))
    assertEquals(body.hcursor.downField("config").focus, Some(config))
    assertEquals(body.hcursor.downField("name").focus, None)
  }

  test("malformed GET is rejected; ambiguous PATCH responses are UNKNOWN") {
    val (badGet, _) = run(_ => IO.pure(Response[IO](Status.Ok).withEntity("{}")))
    assertEquals(badGet.fetchConfigProfile(base, credential, id).attempt.unsafeRunSync()
      .left.toOption.collect { case error: IntegrationError => error.code }, Some("INTEGRATION_INVALID_RESPONSE"))
    List(Status.InternalServerError -> envelope, Status.Ok -> "{}", Status.Ok ->
      envelope.replace("SUPER-SECRET-PRIVATE-KEY", "another-key")).foreach { case (status, body) =>
      val (client, _) = run(_ => IO.pure(Response[IO](status).withEntity(body)))
      assertEquals(client.updateConfigProfile(base, credential, id, config, CanonicalJson.sha256(config)).unsafeRunSync(),
        IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN"))
    }
  }

  test("bounded full GET rejects oversized content and wrong UUID") {
    val (huge, _) = run(_ => IO.pure(Response[IO](Status.Ok).withEntity("x" * (RemnawaveClient.ConfigProfileMaxResponseBytes + 1))))
    assert(huge.fetchConfigProfile(base, credential, id).attempt.unsafeRunSync().isLeft)
    val (wrong, _) = run(_ => IO.pure(Response[IO](Status.Ok).withEntity(
      envelope.replace(id, "44444444-4444-4444-4444-444444444444"))))
    assert(wrong.fetchConfigProfile(base, credential, id).attempt.unsafeRunSync().isLeft)
  }
}
