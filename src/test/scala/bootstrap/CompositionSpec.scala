package ru.bitec.app.ops
package bootstrap

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import infrastructure.config.AppConfig
import infrastructure.http.middleware.RequestIdMiddleware
import io.circe.Json
import munit.FunSuite
import org.http4s.{HttpApp, Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import org.typelevel.doobie.Transactor

import java.util.Base64
import java.util.UUID

/** Wiring smoke test: the composition modules assemble into the real HttpApp, and that app keeps
  * the platform / business route precedence.
  *
  * The transactor points at a URL no driver accepts, so any accidental database access fails
  * instead of silently succeeding. Assembly itself must not touch the database.
  */
final class CompositionSpec extends FunSuite {

  private val config = AppConfig.fromEnvironment(Map(
    "INFRADESK_DB_URL" -> "jdbc:postgresql://localhost:5432/infradesk",
    "INFRADESK_DB_USER" -> "infradesk",
    "INFRADESK_DB_PASSWORD" -> "composition-test-password",
    "INFRADESK_SECRET_MASTER_KEY_BASE64" -> Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7))
  )).toOption.get

  private val unusableTransactor: Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = "jdbc:infradesk-composition-test:none",
      user = "",
      password = "",
      logHandler = None
    )

  private lazy val app: HttpApp[IO] = {
    val resourceTypes = PersistenceModule.resourceDefinitionRegistry
      .getOrElse(fail("Expected the shipped resource type registry to build"))
    val persistence = PersistenceModule.build(unusableTransactor, resourceTypes)
    val integrations = IntegrationModule.integrationProviders(config.integrations).use(providers =>
      IntegrationModule.build(config, persistence, providers)).unsafeRunSync()
    val application =
      ApplicationModule.build(config, persistence, integrations, AppLoggers.slf4j, UUID.randomUUID(),
        new support.RecordingTransports().transports)
    HttpModule.build(persistence, application, config.auth, AppLoggers.slf4j)
  }

  test("health stays public and is served without a database query") {
    val response = app.run(Request[IO](Method.GET, Uri.unsafeFromString("/health"))).unsafeRunSync()
    assertEquals(response.status, Status.Ok)
    assertEquals(response.as[Json].unsafeRunSync().hcursor.get[String]("status"), Right("UP"))
  }

  test("ready stays a platform route instead of being rejected by the auth boundary") {
    val response = app.run(Request[IO](Method.GET, Uri.unsafeFromString("/ready"))).unsafeRunSync()
    assertNotEquals(response.status, Status.Unauthorized)
    assertEquals(response.status, Status.ServiceUnavailable)
    assertEquals(response.as[Json].unsafeRunSync().hcursor.get[String]("status"), Right("NOT_READY"))
  }

  test("business routes stay behind the auth boundary") {
    val organizationId = UUID.randomUUID()
    val environmentId = UUID.randomUUID()
    val response = app.run(Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/environments/$environmentId/resources")
    )).unsafeRunSync()
    assertEquals(response.status, Status.Unauthorized)
  }

  test("configuration assignment routes stay behind the auth boundary") {
    val organizationId = UUID.randomUUID()
    List(Method.GET -> "", Method.POST -> "", Method.POST -> "/preview", Method.DELETE -> s"/${UUID.randomUUID()}")
      .foreach { case (method, suffix) =>
        val response = app.run(Request[IO](method,
          Uri.unsafeFromString(s"/api/v1/organizations/$organizationId/configuration-assignments$suffix"))).unsafeRunSync()
        assertEquals(response.status, Status.Unauthorized, s"$method $suffix")
      }
  }

  test("assembled app carries the request observability middleware") {
    val response = app.run(Request[IO](Method.GET, Uri.unsafeFromString("/health"))).unsafeRunSync()
    assert(response.headers.headers.exists(_.name == RequestIdMiddleware.HeaderName))
  }
}
