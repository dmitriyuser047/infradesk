package ru.bitec.app.ops
package infrastructure.http

import application.integration.{IntegrationManagement, IntegrationProvider, IntegrationProviderRegistry,
  IntegrationRuntimeContext, IntegrationTestResult, TestIntegration}
import application.port.{IntegrationRepository, IntegrationSecret, IntegrationSecretRepository, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.integration.{Integration, IntegrationCapability, IntegrationProviderType}
import infrastructure.runtime.{SystemIdGenerator, SystemTimeProvider}
import integration.secret.IntegrationCredentialCipher
import integration.ssh.SecretEncryptionConfig
import io.circe.Json
import munit.FunSuite
import org.http4s.{Header, Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityDecoder._
import org.typelevel.ci.CIString
import support.{AuthorizationFixtures, RecordingAuditEventRepository, TestAuditRecorder}
import java.util.{Base64, UUID}

final class IntegrationRoutesSpec extends FunSuite {
  private val org = UUID.randomUUID()
  private val root = s"/api/v1/organizations/$org/integrations"
  private val token = "stage24a-route-private-token"
  private val key = Base64.getEncoder.encodeToString(Array.fill[Byte](32)(4))

  private class MemoryIntegrations extends IntegrationRepository[IO] {
    private var values = Map.empty[UUID, Integration]
    var listReads = 0
    override def listByOrganization(organizationId: UUID): IO[List[Integration]] = IO {
      listReads += 1; values.values.filter(_.organizationId == organizationId).toList
    }
    override def findById(organizationId: UUID, id: UUID): IO[Option[Integration]] =
      IO.pure(values.get(id).filter(_.organizationId == organizationId))
    override def findByIdForUpdate(organizationId: UUID, id: UUID): IO[Option[Integration]] = findById(organizationId, id)
    override def save(value: Integration): IO[Unit] = IO { values += value.id -> value }
    override def delete(organizationId: UUID, id: UUID): IO[Unit] = IO { values -= id }
  }
  private class MemorySecrets extends IntegrationSecretRepository[IO] {
    private var values = Map.empty[UUID, IntegrationSecret]
    override def save(value: IntegrationSecret): IO[Unit] = IO { values += value.id -> value }
    override def find(org: UUID, id: UUID): IO[Option[IntegrationSecret]] =
      IO.pure(values.get(id).filter(_.organizationId == org))
    override def delete(org: UUID, id: UUID): IO[Unit] = IO { values -= id }
  }

  test("owner can create, list and request a test; responses and audit never contain token") {
    val integrations = new MemoryIntegrations
    val secrets = new MemorySecrets
    val journal = new RecordingAuditEventRepository
    val cipher = IntegrationCredentialCipher.fromConfig(SecretEncryptionConfig.fromEnvironment(
      Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key)).toOption.get)
    val management = new IntegrationManagement[IO](integrations, secrets, new SystemIdGenerator,
      new SystemTimeProvider, cipher, TestAuditRecorder(journal))
    var auditedBeforeProbe = false
    val provider = new IntegrationProvider[IO] {
      override val providerType = IntegrationProviderType.Remnawave
      override val displayName = "Remnawave"
      override val capabilities: Set[IntegrationCapability] = Set(IntegrationCapability.ConnectivityTest)
      override def testConnection(context: IntegrationRuntimeContext): IO[IntegrationTestResult] = IO {
        auditedBeforeProbe = journal.recorded.exists(_.action.code == "INTEGRATION_TEST_REQUESTED")
        IntegrationTestResult(ok = true, providerType, 3)
      }
    }
    val registry = new IntegrationProviderRegistry[IO](List(provider))
    val runner = new TransactionRunner[IO, IO] { override def run[A](program: IO[A]): IO[A] = program }
    val routes = new IntegrationRoutes[IO](management, new TestIntegration[IO](management, runner, cipher, registry),
      registry, runner, AuthorizationFixtures.authorization).routes.orNotFound
    def request(method: Method, path: String, body: Option[String] = None,
      role: OrganizationRole = OrganizationRole.Owner) = {
      val base = Request[IO](method, Uri.unsafeFromString(path))
      AuthorizationFixtures.as(body.fold(base)(value => base.withEntity(value).putHeaders(
        Header.Raw(CIString("Content-Type"), "application/json"))), org, role)
    }
    val create = request(Method.POST, root, Some(s"""{"name":"Main","providerType":"REMNAWAVE",
      "baseUrl":"https://panel.example.test","credentials":{"apiToken":"$token","caddyApiKey":null}}"""))
    val created = routes.run(create).unsafeRunSync()
    assertEquals(created.status, Status.Created)
    val createdJson = created.as[Json].unsafeRunSync()
    val id = createdJson.hcursor.get[String]("id").toOption.get
    assertEquals(createdJson.hcursor.get[Boolean]("enabled"), Right(false))
    assertEquals(createdJson.hcursor.downField("credential").get[Boolean]("apiTokenConfigured"), Right(true))
    assert(!createdJson.noSpaces.contains(token))
    val member = routes.run(request(Method.GET, root, role = OrganizationRole.Member)).unsafeRunSync()
    assertEquals(member.status, Status.Forbidden)
    assertEquals(integrations.listReads, 0)
    val list = routes.run(request(Method.GET, root)).unsafeRunSync()
    assertEquals(list.status, Status.Ok)
    assert(!list.as[Json].unsafeRunSync().noSpaces.contains(token))
    val tested = routes.run(request(Method.POST, s"$root/$id/test")).unsafeRunSync()
    assertEquals(tested.status, Status.Ok)
    assertEquals(tested.as[Json].unsafeRunSync().hcursor.get[Boolean]("ok"), Right(true))
    assert(auditedBeforeProbe)
    assert(!journal.recorded.toString.contains(token))
    val invalid = routes.run(request(Method.POST, root, Some(s"""{"name":"Bad","providerType":"REMNAWAVE",
      "baseUrl":"http://127.0.0.1/?x=1","credentials":{"apiToken":"$token"}}"""))).unsafeRunSync()
    assertEquals(invalid.status, Status.BadRequest)
    val unsupported = routes.run(request(Method.POST, root, Some(s"""{"name":"Bad","providerType":"UNKNOWN",
      "baseUrl":"https://panel.example.test","credentials":{"apiToken":"$token"}}"""))).unsafeRunSync()
    assertEquals(unsupported.status, Status.BadRequest)
  }
}
